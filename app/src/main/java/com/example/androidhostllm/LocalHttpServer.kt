package com.example.androidhostllm

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class LocalHttpServer(
    private val appContext: Context,
    private val liteRtLmManager: LiteRtLmManager,
    private val appPreferences: AppPreferences,
    private val authRepository: AuthRepository,
    private val chatRepository: ChatRepository,
    private val fileRepository: FileRepository,
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
        val chatId = chatIdFromPath(path)
        val messageChatId = chatMessageChatIdFromPath(path)
        val fileId = fileIdFromPath(path)
        return when {
            session.method == Method.GET && path == "/" -> webAssetResponse("index.html")
            session.method == Method.GET && path == "/login" -> webAssetResponse("login.html")
            session.method == Method.GET && path == "/register" -> webAssetResponse("register.html")
            session.method == Method.GET && path == "/chat" -> webAssetResponse("chat.html")
            session.method == Method.GET && path == "/files" -> webAssetResponse("chat.html")
            session.method == Method.GET && path == "/styles.css" -> webAssetResponse("styles.css")
            session.method == Method.GET && path == "/app.js" -> webAssetResponse("app.js")
            session.method == Method.GET && path == "/routes" -> routesResponse(session)
            session.method == Method.GET && path == "/v1" -> routesResponse(session)
            session.method == Method.GET && path == "/health" -> healthResponse()
            session.method == Method.GET && (path == "/v1/models" || path == "/models") -> modelsResponse()
            session.method == Method.POST && path == "/auth/register" -> registerResponse(session)
            session.method == Method.POST && path == "/auth/login" -> loginResponse(session)
            session.method == Method.POST && path == "/auth/logout" -> logoutResponse(session)
            session.method == Method.GET && path == "/auth/session" -> sessionResponse(session)
            session.method == Method.GET && path == "/api/chats" -> listChatsResponse(session)
            session.method == Method.POST && path == "/api/chats" -> createChatResponse(session)
            session.method == Method.GET && path == "/api/files" -> listFilesResponse(session)
            session.method == Method.POST && path == "/api/files/upload" -> uploadFileResponse(session)
            session.method == Method.GET && fileId != null -> getFileResponse(session, fileId)
            session.method == Method.DELETE && fileId != null -> deleteFileResponse(session, fileId)
            session.method == Method.GET && chatId != null -> getChatResponse(session, chatId)
            session.method == Method.POST && messageChatId != null -> createMessageResponse(session, messageChatId)
            session.method == Method.DELETE && chatId != null -> deleteChatResponse(session, chatId)
            session.method == Method.GET && path == "/debug/routes" -> debugRoutesResponse()
            session.method == Method.GET && path == "/debug/perf" -> performanceResponse()
            session.method == Method.GET && path == "/debug/perf/history" -> performanceHistoryResponse()
            session.method == Method.GET && path == "/debug/config" -> configResponse()
            session.method == Method.POST && path == "/debug/config" -> updateConfigResponse(session)
            session.method == Method.POST && path == "/debug/benchmark" -> benchmarkResponse(session)
            session.method == Method.POST && path == "/v1/conversation/reset" -> resetConversationResponse(session)
            session.method == Method.POST && path == "/v1/chat/completions" -> chatCompletionResponse(session)
            session.method == Method.POST && path == "/coding/v1/chat/completions" -> chatCompletionResponse(session, responseModeOverride = ResponseMode.CODING_CONCISE)
            session.method == Method.POST && path == "/conversation/v1/chat/completions" -> chatCompletionResponse(session, responseModeOverride = ResponseMode.BALANCED)
            session.method == Method.GET && path == "/v1/chat/completions" -> methodNotAllowedResponse()
            session.method == Method.GET && path == "/coding/v1/chat/completions" -> methodNotAllowedResponse()
            session.method == Method.GET && path == "/conversation/v1/chat/completions" -> methodNotAllowedResponse()
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

    private fun chatIdFromPath(path: String): String? {
        val parts = path.split('/').filter { it.isNotBlank() }
        return if (parts.size == 3 && parts[0] == "api" && parts[1] == "chats") parts[2] else null
    }

    private fun chatMessageChatIdFromPath(path: String): String? {
        val parts = path.split('/').filter { it.isNotBlank() }
        return if (parts.size == 4 && parts[0] == "api" && parts[1] == "chats" && parts[3] == "messages") parts[2] else null
    }

    private fun fileIdFromPath(path: String): String? {
        val parts = path.split('/').filter { it.isNotBlank() }
        return if (parts.size == 3 && parts[0] == "api" && parts[1] == "files") parts[2] else null
    }

    private fun routesResponse(session: IHTTPSession): Response {
        return jsonResponse(Response.Status.OK, routeHelpJson(session))
    }

    private fun webAssetResponse(filename: String): Response {
        val safeName = filename.substringAfterLast('/')
        return try {
            val bytes = appContext.assets.open("web/$safeName").use { it.readBytes() }
            newFixedLengthResponse(
                Response.Status.OK,
                mimeTypeForWebAsset(safeName),
                ByteArrayInputStream(bytes),
                bytes.size.toLong(),
            ).apply {
                addCorsHeaders()
                addHeader("Cache-Control", if (safeName.endsWith(".html")) "no-store" else "public, max-age=300")
            }
        } catch (_: Exception) {
            jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "Not found"))
        }
    }

    private fun mimeTypeForWebAsset(filename: String): String {
        return when {
            filename.endsWith(".html") -> "text/html; charset=utf-8"
            filename.endsWith(".css") -> "text/css; charset=utf-8"
            filename.endsWith(".js") -> "application/javascript; charset=utf-8"
            else -> "application/octet-stream"
        }
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
                    .put("webLogin", "GET /login")
                    .put("webRegister", "GET /register")
                    .put("webChat", "GET /chat")
                    .put("webFiles", "GET /files")
                    .put("models", "GET /v1/models")
                    .put("chatCompletions", "POST /v1/chat/completions")
                    .put("codingChat", "POST /coding/v1/chat/completions")
                    .put("conversationChat", "POST /conversation/v1/chat/completions")
                    .put("register", "POST /auth/register")
                    .put("login", "POST /auth/login")
                    .put("logout", "POST /auth/logout")
                    .put("session", "GET /auth/session")
                    .put("chats", "GET/POST /api/chats")
                    .put("chatDetail", "GET/DELETE /api/chats/{chatId}")
                    .put("chatMessages", "POST /api/chats/{chatId}/messages")
                    .put("files", "GET /api/files")
                    .put("fileUpload", "POST /api/files/upload")
                    .put("fileDetail", "GET/DELETE /api/files/{fileId}")
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
                .put("authRegisterEndpoint", "POST /auth/register")
                .put("authLoginEndpoint", "POST /auth/login")
                .put("authLogoutEndpoint", "POST /auth/logout")
                .put("authSessionEndpoint", "GET /auth/session")
                .put("chatListEndpoint", "GET /api/chats")
                .put("chatCreateEndpoint", "POST /api/chats")
                .put("chatDetailEndpoint", "GET /api/chats/{chatId}")
                .put("chatMessageEndpoint", "POST /api/chats/{chatId}/messages")
                .put("chatDeleteEndpoint", "DELETE /api/chats/{chatId}")
                .put("fileListEndpoint", "GET /api/files")
                .put("fileUploadEndpoint", "POST /api/files/upload")
                .put("fileDetailEndpoint", "GET /api/files/{fileId}")
                .put("fileDeleteEndpoint", "DELETE /api/files/{fileId}")
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

    private fun registerResponse(session: IHTTPSession): Response {
        val requestJson = readJsonRequest(session) ?: return jsonResponse(
            Response.Status.BAD_REQUEST,
            JSONObject().put("error", "Malformed JSON or missing JSON body")
        )
        return when (val result = authRepository.register(requestJson.optString("username"), requestJson.optString("password"))) {
            is AuthResult.Success -> authSuccessResponse(result.session)
            AuthResult.InvalidFields -> jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "username and password are required")
            )
            AuthResult.DuplicateUsername -> jsonResponse(
                Response.Status.CONFLICT,
                JSONObject().put("error", "username already exists")
            )
            AuthResult.InvalidCredentials -> jsonResponse(
                Response.Status.UNAUTHORIZED,
                JSONObject().put("error", "Invalid credentials")
            )
        }
    }

    private fun loginResponse(session: IHTTPSession): Response {
        val requestJson = readJsonRequest(session) ?: return jsonResponse(
            Response.Status.BAD_REQUEST,
            JSONObject().put("error", "Malformed JSON or missing JSON body")
        )
        return when (val result = authRepository.login(requestJson.optString("username"), requestJson.optString("password"))) {
            is AuthResult.Success -> authSuccessResponse(result.session)
            AuthResult.InvalidFields -> jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "username and password are required")
            )
            AuthResult.DuplicateUsername,
            AuthResult.InvalidCredentials -> jsonResponse(
                Response.Status.UNAUTHORIZED,
                JSONObject().put("error", "Invalid credentials")
            )
        }
    }

    private fun logoutResponse(session: IHTTPSession): Response {
        val token = sessionTokenFromRequest(session)
        if (authRepository.requireUser(token) == null) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Unauthorized"))
        }
        if (token != null) authRepository.logout(token)
        return jsonResponse(
            Response.Status.OK,
            JSONObject()
                .put("status", "ok")
                .put("message", "Logged out")
        )
    }

    private fun sessionResponse(session: IHTTPSession): Response {
        val user = authRepository.currentUser(sessionTokenFromRequest(session))
        if (user == null) {
            return jsonResponse(
                Response.Status.OK,
                JSONObject()
                    .put("status", "unauthenticated")
                    .put("authenticated", false)
            )
        }
        return jsonResponse(
            Response.Status.OK,
            JSONObject()
                .put("status", "ok")
                .put("authenticated", true)
                .put("user", userJson(user))
        )
    }

    private fun listChatsResponse(session: IHTTPSession): Response {
        val user = requireAppUser(session) ?: return unauthorizedResponse()
        val chats = JSONArray()
        chatRepository.listChats(user.id).forEach { chats.put(chatJson(it)) }
        return jsonResponse(Response.Status.OK, JSONObject().put("chats", chats))
    }

    private fun createChatResponse(session: IHTTPSession): Response {
        val user = requireAppUser(session) ?: return unauthorizedResponse()
        val body = readBody(session)
        val requestJson = if (body.isBlank()) {
            JSONObject()
        } else {
            try {
                JSONObject(body)
            } catch (_: JSONException) {
                return jsonResponse(
                    Response.Status.BAD_REQUEST,
                    JSONObject().put("error", "Malformed JSON or missing JSON body")
                )
            }
        }
        val profile = if (requestJson.has("profile") && !requestJson.isNull("profile")) {
            parseChatProfile(requestJson.optString("profile")) ?: return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "Invalid profile")
            )
        } else {
            ChatProfile.CONVERSATION
        }
        val chat = chatRepository.createChat(
            userId = user.id,
            title = if (requestJson.has("title") && !requestJson.isNull("title")) requestJson.optString("title") else null,
            profile = profile,
        )
        return jsonResponse(Response.Status.OK, JSONObject().put("chat", chatJson(chat)))
    }

    private fun getChatResponse(session: IHTTPSession, chatId: String): Response {
        val user = requireAppUser(session) ?: return unauthorizedResponse()
        val chat = chatRepository.getChat(user.id, chatId) ?: return notFoundResponse()
        val messages = JSONArray()
        chatRepository.listMessages(user.id, chatId)?.forEach { messages.put(messageJson(it)) }
        return jsonResponse(
            Response.Status.OK,
            JSONObject()
                .put("chat", chatJson(chat))
                .put("messages", messages)
        )
    }

    private fun listFilesResponse(session: IHTTPSession): Response {
        val user = requireAppUser(session) ?: return unauthorizedResponse()
        val files = JSONArray()
        fileRepository.listFiles(user.id).forEach { files.put(fileJson(it)) }
        return jsonResponse(Response.Status.OK, JSONObject().put("files", files))
    }

    private fun uploadFileResponse(session: IHTTPSession): Response {
        val user = requireAppUser(session) ?: return unauthorizedResponse()
        val requestJson = readJsonRequest(session) ?: return jsonResponse(
            Response.Status.BAD_REQUEST,
            JSONObject().put("error", "Malformed JSON or missing JSON body. JSON upload expects filename and content.")
        )
        return when (
            val result = fileRepository.uploadMarkdown(
                userId = user.id,
                filename = requestJson.optString("filename"),
                content = if (requestJson.has("content") && !requestJson.isNull("content")) requestJson.optString("content") else null,
                mimeType = if (requestJson.has("mimeType") && !requestJson.isNull("mimeType")) requestJson.optString("mimeType") else null,
            )
        ) {
            is FileUploadResult.Success -> jsonResponse(
                Response.Status.OK,
                JSONObject()
                    .put("status", "ok")
                    .put("file", fileJson(result.file))
            )
            FileUploadResult.InvalidFilename -> jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "Invalid filename")
            )
            FileUploadResult.InvalidType -> jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "Only .md Markdown files are accepted")
            )
            FileUploadResult.Oversized -> jsonResponse(
                Response.Status.PAYLOAD_TOO_LARGE,
                JSONObject().put("error", "Markdown file exceeds 2 MB limit")
            )
            FileUploadResult.InvalidEncoding -> jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "Markdown content must be UTF-8 text")
            )
        }
    }

    private fun getFileResponse(session: IHTTPSession, fileId: String): Response {
        val user = requireAppUser(session) ?: return unauthorizedResponse()
        val file = fileRepository.getFile(user.id, fileId) ?: return notFoundResponse()
        val chunks = JSONArray()
        fileRepository.listChunks(user.id, fileId).orEmpty().forEach { chunk ->
            chunks.put(chunkPreviewJson(chunk))
        }
        return jsonResponse(
            Response.Status.OK,
            JSONObject()
                .put("file", fileJson(file))
                .put("chunks", chunks)
        )
    }

    private fun deleteFileResponse(session: IHTTPSession, fileId: String): Response {
        val user = requireAppUser(session) ?: return unauthorizedResponse()
        if (!fileRepository.deleteFile(user.id, fileId)) return notFoundResponse()
        return jsonResponse(Response.Status.OK, JSONObject().put("status", "ok"))
    }

    private fun createMessageResponse(session: IHTTPSession, chatId: String): Response {
        val user = requireAppUser(session) ?: return unauthorizedResponse()
        val chat = chatRepository.getChat(user.id, chatId) ?: return notFoundResponse()
        val requestJson = readJsonRequest(session) ?: return jsonResponse(
            Response.Status.BAD_REQUEST,
            JSONObject().put("error", "Malformed JSON or missing JSON body")
        )
        val content = requestJson.optString("content").takeIf { it.isNotBlank() } ?: return jsonResponse(
            Response.Status.BAD_REQUEST,
            JSONObject().put("error", "content is required")
        )
        if (requestJson.has("fileIds") && !requestJson.isNull("fileIds") && requestJson.optJSONArray("fileIds") == null) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "fileIds must be an array"))
        }
        val fileIds = parseFileIds(requestJson)
        val context = if (fileIds.isEmpty()) null else {
            fileRepository.buildContext(user.id, fileIds) ?: return notFoundResponse()
        }

        val userMessage = chatRepository.addMessage(chat.id, "user", content)
        val messages = chatRepository.listMessages(user.id, chat.id).orEmpty()
        val prompt = liteRtLmManager.applyResponseModeHint(
            renderChatPrompt(messages, context, userMessage.id),
            responseModeForChatProfile(chat.profile)
        )
        val conversationMode = ConversationMode.FRESH_PER_REQUEST
        val responseMode = responseModeForChatProfile(chat.profile)
        return if (requestJson.optBoolean("stream", false)) {
            streamingAppMessageResponse(chat, userMessage, prompt, conversationMode, responseMode, context)
        } else {
            if (!liteRtLmManager.isLoaded()) {
                return jsonResponse(Response.Status.SERVICE_UNAVAILABLE, JSONObject().put("error", "Model is not loaded"))
            }
            val result = runBlocking {
                liteRtLmManager.generate(
                    prompt = prompt,
                    conversationModeOverride = conversationMode,
                    responseModeOverride = responseMode,
                )
            }
            result.fold(
                onSuccess = { output ->
                    val assistantMessage = chatRepository.addMessage(chat.id, "assistant", output)
                    jsonResponse(
                        Response.Status.OK,
                        JSONObject()
                            .put("message", messageJson(assistantMessage))
                            .put("userMessage", messageJson(userMessage))
                            .put("context", contextJson(context))
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
    }

    private fun deleteChatResponse(session: IHTTPSession, chatId: String): Response {
        val user = requireAppUser(session) ?: return unauthorizedResponse()
        if (!chatRepository.archiveChat(user.id, chatId)) return notFoundResponse()
        return jsonResponse(Response.Status.OK, JSONObject().put("status", "ok"))
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

    private fun chatCompletionResponse(
        session: IHTTPSession,
        responseModeOverride: ResponseMode? = null,
    ): Response {
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
        val promptedWithStyle = liteRtLmManager.applyResponseModeHint(prompt, responseModeOverride ?: liteRtLmManager.responseMode())

        val stream = requestJson.optBoolean("stream", false)
        val nowSeconds = System.currentTimeMillis() / 1000
        val completionId = "chatcmpl-local-$nowSeconds"

        if (stream) {
            return streamingChatCompletionResponse(completionId, nowSeconds, promptedWithStyle, responseModeOverride)
        }

        val result = runBlocking { liteRtLmManager.generate(promptedWithStyle, responseModeOverride = responseModeOverride) }
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

    private fun streamingChatCompletionResponse(
        completionId: String,
        created: Long,
        prompt: String,
        responseModeOverride: ResponseMode? = null,
    ): Response {
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
                            responseModeOverride = responseModeOverride,
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

    private fun parseChatProfile(value: String): ChatProfile? = runCatching {
        ChatProfile.valueOf(value.trim().uppercase(java.util.Locale.US))
    }.getOrNull()

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

    private fun streamingAppMessageResponse(
        chat: ChatRecord,
        userMessage: MessageRecord,
        prompt: String,
        conversationMode: ConversationMode,
        responseMode: ResponseMode,
        context: FileContextBuildResult?,
    ): Response {
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
                    if (!liteRtLmManager.isLoaded()) {
                        writeEvent(JSONObject().put("error", "Model is not loaded").toString())
                        writeEvent("[DONE]")
                        return@use
                    }
                    if (context != null) {
                        writeEvent(JSONObject().put("context", contextJson(context)).toString())
                    }
                    val result = runBlocking {
                        liteRtLmManager.generateStreaming(
                            prompt = prompt,
                            onChunk = { chunk ->
                                writeEvent(JSONObject().put("content", chunk).toString())
                            },
                            conversationModeOverride = conversationMode,
                            responseModeOverride = responseMode,
                        )
                    }
                    result.fold(
                        onSuccess = { outputText ->
                            val assistantMessage = chatRepository.addMessage(chat.id, "assistant", outputText)
                            writeEvent(JSONObject().put("message", messageJson(assistantMessage)).toString())
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
            name = "App-Chat-SSE-${userMessage.id}"
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

    private fun requireAppUser(session: IHTTPSession): AuthUser? {
        return authRepository.requireUser(sessionTokenFromRequest(session))
    }

    private fun unauthorizedResponse(): Response {
        return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Unauthorized"))
    }

    private fun notFoundResponse(): Response {
        return jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "Not found"))
    }

    private fun chatJson(chat: ChatRecord): JSONObject {
        return JSONObject()
            .put("id", chat.id)
            .put("title", chat.title)
            .put("profile", chat.profile.name)
            .put("createdAtMs", chat.createdAtMs)
            .put("updatedAtMs", chat.updatedAtMs)
    }

    private fun messageJson(message: MessageRecord): JSONObject {
        return JSONObject()
            .put("id", message.id)
            .put("role", message.role)
            .put("content", message.content)
            .put("createdAtMs", message.createdAtMs)
    }

    private fun fileJson(file: UploadedFileRecord): JSONObject {
        return JSONObject()
            .put("id", file.id)
            .put("filename", file.safeFilename)
            .put("originalFilename", file.originalFilename)
            .put("mimeType", file.mimeType)
            .put("sizeBytes", file.sizeBytes)
            .put("chunkCount", file.chunkCount)
            .put("createdAtMs", file.createdAtMs)
    }

    private fun chunkPreviewJson(chunk: FileChunkRecord): JSONObject {
        return JSONObject()
            .put("chunkIndex", chunk.chunkIndex)
            .put("headingPath", chunk.headingPath ?: JSONObject.NULL)
            .put("charCount", chunk.charCount)
            .put("preview", chunk.content.take(CHUNK_PREVIEW_CHARS))
    }

    private fun contextJson(context: FileContextBuildResult?): JSONObject {
        if (context == null) {
            return JSONObject()
                .put("fileIds", JSONArray())
                .put("includedChunks", 0)
                .put("includedChars", 0)
                .put("truncated", false)
        }
        return JSONObject()
            .put("fileIds", JSONArray(context.fileIds))
            .put("includedChunks", context.includedChunks)
            .put("includedChars", context.includedChars)
            .put("truncated", context.truncated)
    }

    private fun responseModeForChatProfile(profile: ChatProfile): ResponseMode {
        return when (profile) {
            ChatProfile.CODING -> ResponseMode.CODING_CONCISE
            ChatProfile.CONVERSATION -> ResponseMode.BALANCED
            ChatProfile.CUSTOM -> liteRtLmManager.responseMode()
        }
    }

    private fun renderChatPrompt(
        messages: List<MessageRecord>,
        context: FileContextBuildResult? = null,
        contextMessageId: String? = null,
    ): String {
        return buildString {
            appendLine("Continue this chat. Use the prior turns only as context.")
            messages.takeLast(MAX_PROMPT_MESSAGES).forEach { message ->
                append(message.role)
                append(": ")
                if (context != null && message.id == contextMessageId && message.role == "user") {
                    appendLine(context.promptBlock)
                    appendLine()
                    appendLine("User question:")
                    appendLine(message.content)
                } else {
                    appendLine(message.content)
                }
            }
            append("assistant:")
        }
    }

    private fun parseFileIds(json: JSONObject): List<String> {
        val array = json.optJSONArray("fileIds") ?: return emptyList()
        val ids = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotBlank()) ids += value
        }
        return ids.distinct()
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

    private fun authSuccessResponse(authSession: AuthSession): Response {
        return jsonResponse(
            Response.Status.OK,
            JSONObject()
                .put("status", "ok")
                .put("user", userJson(authSession.user))
                .put("token", authSession.token)
        ).apply {
            addHeader("Set-Cookie", "session=${authSession.token}; Path=/; HttpOnly; SameSite=Lax")
        }
    }

    private fun userJson(user: AuthUser): JSONObject {
        return JSONObject()
            .put("id", user.id)
            .put("username", user.username)
            .put("role", user.role.name)
    }

    private fun sessionTokenFromRequest(session: IHTTPSession): String? {
        val headers = session.headers
        val authorization = headers["authorization"] ?: headers["Authorization"]
        val bearer = authorization?.removePrefix("Bearer ")?.takeIf { it != authorization }?.trim()
        if (!bearer.isNullOrBlank()) return bearer

        val cookieHeader = headers["cookie"] ?: headers["Cookie"]
        return cookieHeader
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("session=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
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
        addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-API-Key")
        addHeader("Access-Control-Max-Age", "86400")
        addHeader("Access-Control-Allow-Private-Network", "true")
    }

    private companion object {
        const val STREAM_PIPE_BUFFER_BYTES = 64 * 1024
        const val MAX_PROMPT_MESSAGES = 24
        const val CHUNK_PREVIEW_CHARS = 500
    }
}
