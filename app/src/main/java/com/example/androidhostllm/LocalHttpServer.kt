package com.example.androidhostllm

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class LocalHttpServer(
    private val liteRtLmManager: LiteRtLmManager,
    private val bindHost: String,
    port: Int,
    private val requireApiKey: Boolean,
    private val apiKey: String?,
    private val serverMode: String,
) : NanoHTTPD(bindHost, port) {

    private val modelId = "local-litert-lm"

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) return corsPreflightResponse()

        val path = normalizePath(session.uri.orEmpty())
        return when {
            session.method == Method.GET && path == "/" -> routesResponse(session)
            session.method == Method.GET && path == "/routes" -> routesResponse(session)
            session.method == Method.GET && path == "/v1" -> routesResponse(session)
            session.method == Method.GET && path == "/health" -> healthResponse()
            session.method == Method.GET && (path == "/v1/models" || path == "/models") -> modelsResponse()
            session.method == Method.GET && path == "/debug/routes" -> debugRoutesResponse()
            session.method == Method.POST && path == "/v1/chat/completions" -> chatCompletionResponse(session)
            session.method == Method.GET && path == "/v1/chat/completions" -> methodNotAllowedResponse()
            else -> jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "Not found"))
        }
    }

    fun startServer() {
        start(SOCKET_READ_TIMEOUT, false)
    }

    fun stopServer() {
        stop()
    }

    private fun normalizePath(path: String): String {
        if (path.isBlank()) return "/"
        val withoutQuery = path.substringBefore('?')
        val withLeadingSlash = if (withoutQuery.startsWith('/')) withoutQuery else "/$withoutQuery"
        return if (withLeadingSlash.length > 1) withLeadingSlash.trimEnd('/') else "/"
    }

    private fun routesResponse(session: IHTTPSession): Response {
        return jsonResponse(Response.Status.OK, routeHelpJson(session))
    }

    private fun routeHelpJson(session: IHTTPSession): JSONObject {
        val host = session.headers["host"] ?: session.headers["Host"] ?: "$bindHost:${listeningPort}"
        return JSONObject()
            .put("status", "ok")
            .put("message", "LiteRT-LM local server is running")
            .put("baseUrl", "http://$host/v1")
            .put(
                "routes",
                JSONObject()
                    .put("health", "GET /health")
                    .put("models", "GET /v1/models")
                    .put("chat", "POST /v1/chat/completions")
            )
            .put("note", "Use POST for /v1/chat/completions. Browser GET requests do not run inference.")
    }

    private fun healthResponse(): Response {
        return jsonResponse(
            Response.Status.OK,
            JSONObject()
                .put("status", "ok")
                .put("modelLoaded", liteRtLmManager.isLoaded())
                .put("serverMode", serverMode)
        )
    }

    private fun modelsResponse(): Response {
        return jsonResponse(
            Response.Status.OK,
            JSONObject()
                .put("object", "list")
                .put(
                    "data",
                    JSONArray().put(
                        JSONObject()
                            .put("id", modelId)
                            .put("object", "model")
                            .put("created", 0)
                            .put("owned_by", "local-device")
                    )
                )
        )
    }

    private fun debugRoutesResponse(): Response {
        return jsonResponse(
            Response.Status.OK,
            JSONObject()
                .put("status", "ok")
                .put("routesEnabled", true)
                .put("cors", true)
                .put("privateNetworkAccess", true)
                .put("modelsEndpoint", true)
                .put("streamingCompat", true)
        )
    }

    private fun methodNotAllowedResponse(): Response {
        return jsonResponse(
            Response.Status.METHOD_NOT_ALLOWED,
            JSONObject().put("error", "Method not allowed. Use POST /v1/chat/completions.")
        )
    }

    private fun chatCompletionResponse(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Unauthorized"))
        }
        if (!liteRtLmManager.isLoaded()) {
            return jsonResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                JSONObject().put("error", "Model is not loaded")
            )
        }

        val body = try {
            readBody(session)
        } catch (error: Exception) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "Could not read request body: ${error.message}")
            )
        }

        val requestJson = try {
            JSONObject(body)
        } catch (error: JSONException) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "Malformed JSON or missing JSON body")
            )
        }

        val prompt = extractPrompt(requestJson)
        if (prompt.isNullOrBlank()) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "Missing prompt or user message content")
            )
        }

        val stream = requestJson.optBoolean("stream", false)
        val nowSeconds = System.currentTimeMillis() / 1000
        val completionId = "chatcmpl-local-$nowSeconds"

        val result = runBlocking { liteRtLmManager.generate(prompt) }
        return result.fold(
            onSuccess = { output ->
                if (stream) {
                    streamingChatCompletionResponse(completionId, nowSeconds, output)
                } else {
                    jsonResponse(Response.Status.OK, chatCompletionJson(completionId, nowSeconds, output))
                }
            },
            onFailure = { error ->
                jsonResponse(
                    Response.Status.INTERNAL_ERROR,
                    JSONObject().put("error", "Generation failed: ${error.message}")
                )
            }
        )
    }

    private fun chatCompletionJson(completionId: String, created: Long, output: String): JSONObject {
        return JSONObject()
            .put("id", completionId)
            .put("object", "chat.completion")
            .put("created", created)
            .put("model", modelId)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put(
                            "message",
                            JSONObject()
                                .put("role", "assistant")
                                .put("content", output)
                        )
                        .put("finish_reason", "stop")
                )
            )
            .put("response", output)
    }

    private fun streamingChatCompletionResponse(completionId: String, created: Long, output: String): Response {
        val contentChunk = JSONObject()
            .put("id", completionId)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", modelId)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put(
                            "delta",
                            JSONObject()
                                .put("role", "assistant")
                                .put("content", output)
                        )
                        .put("finish_reason", JSONObject.NULL)
                )
            )

        val stopChunk = JSONObject()
            .put("id", completionId)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", modelId)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("delta", JSONObject())
                        .put("finish_reason", "stop")
                )
            )

        val body = buildString {
            append("data: ").append(contentChunk.toString()).append("\n\n")
            append("data: ").append(stopChunk.toString()).append("\n\n")
            append("data: [DONE]\n\n")
        }

        return newFixedLengthResponse(Response.Status.OK, "text/event-stream", body).apply {
            addCorsHeaders()
            addHeader("Cache-Control", "no-cache")
            addHeader("Connection", "keep-alive")
        }
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        if (!requireApiKey) return true
        val expected = apiKey?.takeIf { it.isNotBlank() } ?: return false
        val headers = session.headers
        val authorization = headers["authorization"] ?: headers["Authorization"]
        val bearer = authorization?.removePrefix("Bearer ")?.takeIf { it != authorization }
        val xApiKey = headers["x-api-key"] ?: headers["X-API-Key"]
        return constantTimeEquals(expected, bearer) || constantTimeEquals(expected, xApiKey)
    }

    private fun constantTimeEquals(expected: String, actual: String?): Boolean {
        if (actual == null) return false
        val expectedBytes = expected.toByteArray(Charsets.UTF_8)
        val actualBytes = actual.toByteArray(Charsets.UTF_8)
        var diff = expectedBytes.size xor actualBytes.size
        val max = maxOf(expectedBytes.size, actualBytes.size)
        for (i in 0 until max) {
            val e = if (i < expectedBytes.size) expectedBytes[i].toInt() else 0
            val a = if (i < actualBytes.size) actualBytes[i].toInt() else 0
            diff = diff or (e xor a)
        }
        return diff == 0
    }

    private fun readBody(session: IHTTPSession): String {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        return files["postData"].orEmpty()
    }

    private fun extractPrompt(json: JSONObject): String? {
        val messages = json.optJSONArray("messages")
        if (messages != null) {
            for (index in messages.length() - 1 downTo 0) {
                val message = messages.optJSONObject(index) ?: continue
                if (message.optString("role") == "user") {
                    return message.optString("content").takeIf { it.isNotBlank() }
                }
            }
        }
        return json.optString("prompt").takeIf { it.isNotBlank() }
    }

    private fun corsPreflightResponse(): Response {
        return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "").apply {
            addCorsHeaders()
        }
    }

    private fun jsonResponse(status: Response.Status, body: JSONObject): Response {
        return newFixedLengthResponse(status, "application/json", body.toString()).apply {
            addCorsHeaders()
        }
    }

    private fun Response.addCorsHeaders() {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-API-Key")
        addHeader("Access-Control-Max-Age", "86400")
        addHeader("Access-Control-Allow-Private-Network", "true")
    }
}
