package com.example.androidhostllm

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class LocalHttpServer(
    private val liteRtLmManager: LiteRtLmManager,
    private val appPreferences: AppPreferences,
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
            session.method == Method.GET && path == "/debug/perf" -> performanceResponse()
            session.method == Method.GET && path == "/debug/perf/history" -> performanceHistoryResponse()
            session.method == Method.GET && path == "/debug/config" -> configResponse()
            session.method == Method.POST && path == "/debug/config" -> updateConfigResponse(session)
            session.method == Method.POST && path == "/debug/benchmark" -> benchmarkResponse(session)
            session.method == Method.POST && path == "/v1/conversation/reset" -> resetConversationResponse(session)
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
                    .put("resetConversation", "POST /v1/conversation/reset")
                    .put("performance", "GET /debug/perf")
                    .put("performanceHistory", "GET /debug/perf/history")
                    .put("config", "GET/POST /debug/config")
                    .put("benchmark", "POST /debug/benchmark")
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
                .put("streamingIncremental", true)
                .put("resetConversationEndpoint", "POST /v1/conversation/reset")
                .put("performanceEndpoint", "GET /debug/perf")
                .put("performanceHistoryEndpoint", "GET /debug/perf/history")
                .put("configEndpoint", "GET/POST /debug/config")
                .put("benchmarkEndpoint", "POST /debug/benchmark")
        )
    }

    private fun performanceResponse(): Response {
        return jsonResponse(Response.Status.OK, liteRtLmManager.performanceJson())
    }

    private fun performanceHistoryResponse(): Response {
        return jsonResponse(Response.Status.OK, liteRtLmManager.performanceHistoryJson())
    }

    private fun configResponse(): Response {
        return jsonResponse(Response.Status.OK, configJson())
    }

    private fun updateConfigResponse(session: IHTTPSession): Response {
        val requestJson = readJsonRequest(session) ?: return jsonResponse(
            Response.Status.BAD_REQUEST,
            JSONObject().put("error", "Malformed JSON or missing JSON body")
        )

        val warnings = JSONArray()

        if (requestJson.has("conversationMode") && !requestJson.isNull("conversationMode")) {
            val value = parseConversationMode(requestJson.optString("conversationMode")) ?: return invalidConfigValue("conversationMode")
            liteRtLmManager.setConversationMode(value)
            appPreferences.saveConversationMode(value)
        }

        if (requestJson.has("responseMode") && !requestJson.isNull("responseMode")) {
            val value = parseResponseMode(requestJson.optString("responseMode")) ?: return invalidConfigValue("responseMode")
            liteRtLmManager.setResponseMode(value)
            appPreferences.saveResponseMode(value)
        }

        if (requestJson.has("generationTimeoutSeconds") && !requestJson.isNull("generationTimeoutSeconds")) {
            val timeoutSeconds = requestJson.optInt("generationTimeoutSeconds", -1)
            if (timeoutSeconds !in 10..600) {
                return jsonResponse(
                    Response.Status.BAD_REQUEST,
                    JSONObject().put("error", "generationTimeoutSeconds must be between 10 and 600")
                )
            }
            liteRtLmManager.setGenerationTimeoutSeconds(timeoutSeconds)
            appPreferences.saveGenerationTimeoutSeconds(timeoutSeconds)
        }

        if (requestJson.has("speculativeDecodingRequested") && !requestJson.isNull("speculativeDecodingRequested")) {
            val value = requestJson.optBoolean("speculativeDecodingRequested")
            liteRtLmManager.setSpeculativeDecodingRequested(value)
            appPreferences.saveSpeculativeDecodingRequested(value)
            warnings.put("MTP setting changed; reload model for this to take effect.")
        }

        return jsonResponse(
            Response.Status.OK,
            JSONObject()
                .put("status", "ok")
                .put("config", configJson())
                .put("warnings", warnings)
        )
    }

    private fun benchmarkResponse(session: IHTTPSession): Response {
        if (!liteRtLmManager.isLoaded()) {
            return jsonResponse(Response.Status.SERVICE_UNAVAILABLE, JSONObject().put("error", "Model is not loaded"))
        }
        val requestJson = readJsonRequest(session) ?: return jsonResponse(
            Response.Status.BAD_REQUEST,
            JSONObject().put("error", "Malformed JSON or missing JSON body")
        )
        val prompt = requestJson.optString("prompt").takeIf { it.isNotBlank() } ?: return jsonResponse(
            Response.Status.BAD_REQUEST,
            JSONObject().put("error", "prompt is required")
        )
        val iterations = requestJson.optInt("iterations", 1).coerceIn(1, 5)
        val stream = requestJson.optBoolean("stream", true)
        val resetBeforeEach = requestJson.optBoolean("resetBeforeEach", false)
        val conversationModeOverride = if (requestJson.has("conversationMode") && !requestJson.isNull("conversationMode")) {
            parseConversationMode(requestJson.optString("conversationMode")) ?: return invalidConfigValue("conversationMode")
        } else null
        val responseModeOverride = if (requestJson.has("responseMode") && !requestJson.isNull("responseMode")) {
            parseResponseMode(requestJson.optString("responseMode")) ?: return invalidConfigValue("responseMode")
        } else null

        val results = JSONArray()
        repeat(iterations) { index ->
            val effectiveResponseMode = responseModeOverride ?: liteRtLmManager.responseMode()
            val effectiveConversationMode = conversationModeOverride ?: liteRtLmManager.conversationMode()
            if (resetBeforeEach) {
                val resetResult = runBlocking { liteRtLmManager.resetConversation() }
                if (resetResult.isFailure) {
                    results.put(benchmarkErrorJson(index + 1, effectiveConversationMode, effectiveResponseMode, resetResult.exceptionOrNull()))
                    return@repeat
                }
            }

            val prompted = liteRtLmManager.applyResponseModeHint(prompt, effectiveResponseMode)
            val result = runBlocking {
                if (stream) {
                    liteRtLmManager.generateStreaming(
                        prompt = prompted,
                        onChunk = { },
                        conversationModeOverride = effectiveConversationMode,
                        responseModeOverride = effectiveResponseMode,
                    )
                } else {
                    liteRtLmManager.generate(
                        prompt = prompted,
                        conversationModeOverride = effectiveConversationMode,
                        responseModeOverride = effectiveResponseMode,
                    )
                }
            }
            val snapshot = liteRtLmManager.performanceSnapshot()
            results.put(benchmarkResultJson(index + 1, snapshot, effectiveConversationMode, effectiveResponseMode, result.exceptionOrNull()))
        }

        return jsonResponse(
            Response.Status.OK,
            JSONObject()
                .put("status", "ok")
                .put("iterations", iterations)
                .put("results", results)
                .put("average", benchmarkAverageJson(results))
        )
    }

    private fun resetConversationResponse(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Unauthorized"))
        }
        val result = runBlocking { liteRtLmManager.resetConversation() }
        return result.fold(
            onSuccess = {
                jsonResponse(
                    Response.Status.OK,
                    JSONObject()
                        .put("status", "ok")
                        .put("message", "Conversation reset")
                )
            },
            onFailure = { error ->
                jsonResponse(
                    Response.Status.BAD_REQUEST,
                    JSONObject().put("error", error.message ?: "Conversation reset failed")
                )
            }
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
        val promptedWithStyle = liteRtLmManager.applyResponseModeHint(prompt)

        val stream = requestJson.optBoolean("stream", false)
        val nowSeconds = System.currentTimeMillis() / 1000
        val completionId = "chatcmpl-local-$nowSeconds"

        if (stream) {
            return streamingChatCompletionResponse(completionId, nowSeconds, promptedWithStyle)
        }

        val result = runBlocking { liteRtLmManager.generate(promptedWithStyle) }
        return result.fold(
            onSuccess = { output ->
                jsonResponse(Response.Status.OK, chatCompletionJson(completionId, nowSeconds, output))
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

    private fun streamingChatCompletionResponse(completionId: String, created: Long, prompt: String): Response {
        val input = PipedInputStream(STREAM_PIPE_BUFFER_BYTES)
        val output = PipedOutputStream(input)

        Thread {
            OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                fun writeEvent(payload: String) {
                    writer.write("data: ")
                    writer.write(payload)
                    writer.write("\n\n")
                    writer.flush()
                }

                try {
                    writeEvent(chatCompletionChunkJson(completionId, created, role = "assistant").toString())
                    val result = runBlocking {
                        liteRtLmManager.generateStreaming(
                            prompt = prompt,
                            onChunk = { chunk ->
                                writeEvent(chatCompletionChunkJson(completionId, created, content = chunk).toString())
                            },
                        )
                    }
                    result.fold(
                        onSuccess = {
                            writeEvent(chatCompletionChunkJson(completionId, created, finishReason = "stop").toString())
                            writeEvent("[DONE]")
                        },
                        onFailure = { error ->
                            writeEvent(streamingErrorJson(error).toString())
                            writeEvent("[DONE]")
                        }
                    )
                } catch (_: Throwable) {
                    // The client may have disconnected; closing the pipe stops the streaming response.
                }
            }
        }.apply {
            name = "LiteRT-LM-SSE-$completionId"
            isDaemon = true
            start()
        }

        return newChunkedResponse(Response.Status.OK, "text/event-stream", input).apply {
            addCorsHeaders()
            addHeader("Cache-Control", "no-cache")
            addHeader("Connection", "keep-alive")
            addHeader("X-Accel-Buffering", "no")
        }
    }

    private fun configJson(): JSONObject {
        return JSONObject()
            .put("modelLoaded", liteRtLmManager.isLoaded())
            .put("backendStatus", liteRtLmManager.backendStatus())
            .put("speculativeDecodingRequested", liteRtLmManager.speculativeDecodingRequested())
            .put("speculativeDecodingEnabled", liteRtLmManager.speculativeDecodingEnabled())
            .put("conversationMode", liteRtLmManager.conversationMode().name)
            .put("responseMode", liteRtLmManager.responseMode().name)
            .put("generationTimeoutSeconds", liteRtLmManager.generationTimeoutSeconds())
            .put("serverMode", serverMode)
            .put("streamingSupported", true)
            .put("modelId", modelId)
    }

    private fun benchmarkResultJson(
        iteration: Int,
        snapshot: PerformanceSnapshot,
        conversationMode: ConversationMode,
        responseMode: ResponseMode,
        error: Throwable?,
    ): JSONObject {
        return JSONObject()
            .put("iteration", iteration)
            .put("firstChunkLatencyMs", snapshot.lastFirstChunkLatencyMs ?: JSONObject.NULL)
            .put("generationDurationMs", snapshot.lastGenerationDurationMs ?: JSONObject.NULL)
            .put("outputChars", snapshot.lastOutputChars)
            .put("approxCharsPerSecond", formatDouble(snapshot.lastApproxCharsPerSecond))
            .put("chunkCount", snapshot.lastChunkCount)
            .put("backendStatus", snapshot.backendStatus)
            .put("speculativeDecodingEnabled", snapshot.speculativeDecodingEnabled)
            .put("conversationMode", conversationMode.name)
            .put("responseMode", responseMode.name)
            .put("error", error?.message ?: JSONObject.NULL)
    }

    private fun benchmarkErrorJson(
        iteration: Int,
        conversationMode: ConversationMode,
        responseMode: ResponseMode,
        error: Throwable?,
    ): JSONObject {
        return JSONObject()
            .put("iteration", iteration)
            .put("firstChunkLatencyMs", JSONObject.NULL)
            .put("generationDurationMs", JSONObject.NULL)
            .put("outputChars", 0)
            .put("approxCharsPerSecond", 0.0)
            .put("chunkCount", 0)
            .put("backendStatus", liteRtLmManager.backendStatus())
            .put("speculativeDecodingEnabled", liteRtLmManager.speculativeDecodingEnabled())
            .put("conversationMode", conversationMode.name)
            .put("responseMode", responseMode.name)
            .put("error", error?.message ?: "Benchmark setup failed")
    }

    private fun benchmarkAverageJson(results: JSONArray): JSONObject {
        var firstChunkTotal = 0.0
        var firstChunkCount = 0
        var durationTotal = 0.0
        var durationCount = 0
        var outputCharsTotal = 0.0
        var outputCharsCount = 0
        var charsPerSecondTotal = 0.0
        var charsPerSecondCount = 0
        var chunkCountTotal = 0.0
        var chunkCountCount = 0

        for (index in 0 until results.length()) {
            val result = results.optJSONObject(index) ?: continue
            if (!result.isNull("firstChunkLatencyMs")) {
                firstChunkTotal += result.optDouble("firstChunkLatencyMs")
                firstChunkCount += 1
            }
            if (!result.isNull("generationDurationMs")) {
                durationTotal += result.optDouble("generationDurationMs")
                durationCount += 1
            }
            if (result.isNull("error")) {
                outputCharsTotal += result.optDouble("outputChars")
                outputCharsCount += 1
                charsPerSecondTotal += result.optDouble("approxCharsPerSecond")
                charsPerSecondCount += 1
                chunkCountTotal += result.optDouble("chunkCount")
                chunkCountCount += 1
            }
        }

        return JSONObject()
            .put("firstChunkLatencyMs", averageOrNull(firstChunkTotal, firstChunkCount))
            .put("generationDurationMs", averageOrNull(durationTotal, durationCount))
            .put("outputChars", averageOrNull(outputCharsTotal, outputCharsCount))
            .put("approxCharsPerSecond", averageOrNull(charsPerSecondTotal, charsPerSecondCount))
            .put("chunkCount", averageOrNull(chunkCountTotal, chunkCountCount))
    }

    private fun averageOrNull(total: Double, count: Int): Any {
        return if (count > 0) formatDouble(total / count) else JSONObject.NULL
    }

    private fun formatDouble(value: Double): Double = String.format(java.util.Locale.US, "%.1f", value).toDouble()

    private fun parseConversationMode(value: String): ConversationMode? = runCatching { ConversationMode.valueOf(value) }.getOrNull()

    private fun parseResponseMode(value: String): ResponseMode? = runCatching { ResponseMode.valueOf(value) }.getOrNull()

    private fun invalidConfigValue(field: String): Response {
        return jsonResponse(
            Response.Status.BAD_REQUEST,
            JSONObject().put("error", "Invalid $field value")
        )
    }

    private fun chatCompletionChunkJson(
        completionId: String,
        created: Long,
        role: String? = null,
        content: String? = null,
        finishReason: String? = null,
    ): JSONObject {
        val delta = JSONObject()
        if (role != null) delta.put("role", role)
        if (content != null) delta.put("content", content)

        return JSONObject()
            .put("id", completionId)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", modelId)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("delta", delta)
                        .put("finish_reason", finishReason ?: JSONObject.NULL)
                )
            )
    }

    private fun streamingErrorJson(error: Throwable): JSONObject {
        return JSONObject()
            .put("error", JSONObject().put("message", "Generation failed: ${error.message}"))
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

    private fun readJsonRequest(session: IHTTPSession): JSONObject? {
        return try {
            JSONObject(readBody(session))
        } catch (_: Exception) {
            null
        }
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

    private companion object {
        const val STREAM_PIPE_BUFFER_BYTES = 64 * 1024
    }
}
