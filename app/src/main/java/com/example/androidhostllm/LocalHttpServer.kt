package com.example.androidhostllm

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class LocalHttpServer(
    private val liteRtLmManager: LiteRtLmManager,
    port: Int = 8080,
) : NanoHTTPD("127.0.0.1", port) {

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.method == Method.GET && session.uri == "/health" -> healthResponse()
            session.method == Method.POST && session.uri == "/v1/chat/completions" -> chatCompletionResponse(session)
            else -> jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "Not found"))
        }
    }

    fun startServer() {
        start(SOCKET_READ_TIMEOUT, false)
    }

    fun stopServer() {
        stop()
    }

    private fun healthResponse(): Response {
        return jsonResponse(
            Response.Status.OK,
            JSONObject()
                .put("status", "ok")
                .put("modelLoaded", liteRtLmManager.isLoaded())
        )
    }

    private fun chatCompletionResponse(session: IHTTPSession): Response {
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

        val prompt = try {
            extractPrompt(JSONObject(body))
        } catch (error: JSONException) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "Bad JSON: ${error.message}")
            )
        }

        if (prompt.isNullOrBlank()) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "Missing prompt or user message content")
            )
        }

        val result = runBlocking { liteRtLmManager.generate(prompt) }
        return result.fold(
            onSuccess = { output ->
                jsonResponse(
                    Response.Status.OK,
                    JSONObject()
                        .put("response", output)
                        .put(
                            "choices",
                            JSONArray().put(
                                JSONObject().put(
                                    "message",
                                    JSONObject()
                                        .put("role", "assistant")
                                        .put("content", output)
                                )
                            )
                        )
                )
            },
            onFailure = { error ->
                jsonResponse(
                    Response.Status.INTERNAL_ERROR,
                    JSONObject().put("error", "Generation failed: ${error.message}")
                )
            }
        )
    }

    private fun readBody(session: IHTTPSession): String {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        return files["postData"].orEmpty()
    }

    private fun extractPrompt(json: JSONObject): String? {
        json.optString("prompt").takeIf { it.isNotBlank() }?.let { return it }
        val messages = json.optJSONArray("messages") ?: return null
        for (index in messages.length() - 1 downTo 0) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") == "user") {
                return message.optString("content").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun jsonResponse(status: Response.Status, body: JSONObject): Response {
        return newFixedLengthResponse(status, "application/json", body.toString()).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }
}
