package com.example.androidhostllm

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private data class AppPromptPlan(
    val plan: PromptPlan,
    val context: FileContextBuildResult?,
    val fileIds: List<String>,
    val messages: List<MessageRecord>,
    val skill: SkillRecord,
    val skillState: ChatSkillStateRecord,
)

private data class ParsedModelResponse(
    val rawModelText: String,
    val thinkingText: String?,
    val finalText: String,
)

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
    private val promptBudgetManager = PromptBudgetManager()
    private val skillRepository = SkillRepository(appContext)
    private val toolRegistry = ToolRegistry(chatRepository, fileRepository)
    private val generationJobs = GenerationJobStore()
    private val securityMode = SecurityMode.fromServerMode(serverMode)
    private val requestId = ThreadLocal<String>()

    override fun serve(session: IHTTPSession): Response {
        requestId.set(newRequestId())
        if (session.method == Method.OPTIONS) return corsPreflightResponse()

        val path = normalizePath(session.uri.orEmpty())
        val chatId = chatIdFromPath(path)
        val messageChatId = chatMessageChatIdFromPath(path)
        val chatFilesChatId = chatFilesChatIdFromPath(path)
        val chatSkillChatId = chatSkillChatIdFromPath(path)
        val chatToolLogsChatId = chatToolLogsChatIdFromPath(path)
        val chatGenerationCancelId = chatGenerationCancelIdFromPath(path)
        val chatGenerationRetryId = chatGenerationRetryIdFromPath(path)
        val chatGenerationListId = chatGenerationListIdFromPath(path)
        val generationCancelId = generationCancelIdFromPath(path)
        val generationId = generationIdFromPath(path)
        val adminSkillSlug = adminSkillSlugFromPath(path)
        val skillSlug = skillSlugFromPath(path)
        val chatFileAttachment = chatFileAttachmentFromPath(path)
        val fileId = fileIdFromPath(path)
        return when {
            session.method == Method.GET && path == "/" -> webAssetResponse("index.html")
            session.method == Method.GET && path == "/login" -> webAssetResponse("login.html")
            session.method == Method.GET && path == "/register" -> webAssetResponse("register.html")
            session.method == Method.GET && path == "/chat" -> webAssetResponse("chat.html")
            session.method == Method.GET && path == "/files" -> webAssetResponse("chat.html")
            session.method == Method.GET && path == "/admin" -> adminPageResponse(session)
            session.method == Method.GET && path == "/styles.css" -> webAssetResponse("styles.css")
            session.method == Method.GET && path == "/app.js" -> webAssetResponse("app.js")
            session.method == Method.GET && path == "/routes" -> routesResponse(session)
            session.method == Method.GET && path == "/v1" -> routesResponse(session)
            session.method == Method.GET && path == "/health" -> healthResponse()
            session.method == Method.GET && (
                path == "/v1/models" ||
                    path == "/coding/v1/models" ||
                    path == "/conversation/v1/models" ||
                    path == "/models"
                ) -> modelsResponse()
            session.method == Method.POST && path == "/auth/register" -> registerResponse(session)
            session.method == Method.POST && path == "/auth/login" -> loginResponse(session)
            session.method == Method.POST && path == "/auth/logout" -> logoutResponse(session)
            session.method == Method.POST && path == "/auth/logout-all" -> logoutAllResponse(session)
            session.method == Method.GET && path == "/auth/session" -> sessionResponse(session)
            session.method == Method.GET && path == "/api/admin/status" -> adminStatusResponse(session)
            session.method == Method.GET && path == "/api/admin/users" -> adminUsersResponse(session)
            session.method == Method.GET && path == "/api/admin/files" -> adminFilesResponse(session)
            session.method == Method.GET && path == "/api/admin/skills" -> adminSkillsResponse(session)
            session.method == Method.GET && path == "/api/admin/skills/export" -> adminExportSkillsResponse(session)
            session.method == Method.POST && path == "/api/admin/skills/import" -> adminImportSkillsResponse(session)
            session.method == Method.POST && path == "/api/admin/skills/test" -> adminSkillTestResponse(session)
            session.method == Method.GET && path == "/api/skills" -> listSkillsResponse(session)
            session.method == Method.GET && skillSlug != null -> getSkillResponse(session, skillSlug)
            session.method == Method.POST && path == "/api/admin/skills" -> adminCreateSkillResponse(session)
            session.method == Method.PUT && adminSkillSlug != null -> adminUpdateSkillResponse(session, adminSkillSlug)
            session.method == Method.DELETE && adminSkillSlug != null -> adminDeleteSkillResponse(session, adminSkillSlug)
            session.method == Method.GET && path == "/api/tools" -> listToolsResponse(session, admin = false)
            session.method == Method.GET && path == "/api/admin/tools" -> listToolsResponse(session, admin = true)
            session.method == Method.GET && path == "/api/admin/tools/logs" -> adminToolLogsResponse(session)
            session.method == Method.GET && path == "/api/chats" -> listChatsResponse(session)
            session.method == Method.POST && path == "/api/chats" -> createChatResponse(session)
            session.method == Method.GET && path == "/api/files" -> listFilesResponse(session)
            session.method == Method.POST && path == "/api/files/upload" -> uploadFileResponse(session)
            session.method == Method.GET && chatSkillChatId != null -> getChatSkillResponse(session, chatSkillChatId)
            session.method == Method.PUT && chatSkillChatId != null -> putChatSkillResponse(session, chatSkillChatId)
            session.method == Method.GET && chatToolLogsChatId != null -> chatToolLogsResponse(session, chatToolLogsChatId)
            session.method == Method.GET && chatGenerationListId != null -> listGenerationsResponse(session, chatGenerationListId)
            session.method == Method.POST && chatGenerationCancelId != null -> cancelChatGenerationResponse(session, chatGenerationCancelId)
            session.method == Method.POST && chatGenerationRetryId != null -> retryChatGenerationResponse(session, chatGenerationRetryId)
            session.method == Method.POST && generationCancelId != null -> cancelGenerationResponse(session, generationCancelId)
            session.method == Method.GET && generationId != null -> getGenerationResponse(session, generationId)
            session.method == Method.GET && chatFilesChatId != null -> listChatFilesResponse(session, chatFilesChatId)
            session.method == Method.POST && chatFilesChatId != null -> attachChatFileResponse(session, chatFilesChatId)
            session.method == Method.DELETE && chatFileAttachment != null -> detachChatFileResponse(
                session,
                chatFileAttachment.first,
                chatFileAttachment.second,
            )
            session.method == Method.GET && fileId != null -> getFileResponse(session, fileId)
            session.method == Method.DELETE && fileId != null -> deleteFileResponse(session, fileId)
            session.method == Method.GET && chatId != null -> getChatResponse(session, chatId)
            session.method == Method.POST && messageChatId != null -> createMessageResponse(session, messageChatId)
            session.method == Method.DELETE && chatId != null -> deleteChatResponse(session, chatId)
            session.method == Method.GET && path == "/debug/routes" -> debugRoutesResponse(session)
            session.method == Method.GET && path == "/debug/perf" -> debugResponse(session) { performanceResponse() }
            session.method == Method.GET && path == "/debug/perf/history" -> debugResponse(session) { performanceHistoryResponse() }
            session.method == Method.GET && path == "/debug/config" -> debugResponse(session) { configResponse() }
            session.method == Method.POST && path == "/debug/config" -> debugResponse(session) { updateConfigResponse(session) }
            session.method == Method.POST && path == "/debug/benchmark" -> debugResponse(session) { benchmarkResponse(session) }
            session.method == Method.POST && path == "/v1/conversation/reset" -> resetConversationResponse(session)
            session.method == Method.POST && path == "/v1/chat/completions" -> chatCompletionResponse(session)
            session.method == Method.POST && path == "/coding/v1/chat/completions" -> chatCompletionResponse(session, responseModeOverride = ResponseMode.CODING_CONCISE)
            session.method == Method.POST && path == "/conversation/v1/chat/completions" -> chatCompletionResponse(session, responseModeOverride = ResponseMode.BALANCED)
            session.method == Method.GET && path == "/v1/chat/completions" -> methodNotAllowedResponse()
            session.method == Method.GET && path == "/coding/v1/chat/completions" -> methodNotAllowedResponse()
            session.method == Method.GET && path == "/conversation/v1/chat/completions" -> methodNotAllowedResponse()
            else -> errorResponse(Response.Status.NOT_FOUND, "not_found", "Not found")
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

    private fun chatFilesChatIdFromPath(path: String): String? {
        val parts = path.split('/').filter { it.isNotBlank() }
        return if (parts.size == 4 && parts[0] == "api" && parts[1] == "chats" && parts[3] == "files") parts[2] else null
    }

    private fun chatSkillChatIdFromPath(path: String): String? {
        val parts = path.split('/').filter { it.isNotBlank() }
        return if (parts.size == 4 && parts[0] == "api" && parts[1] == "chats" && parts[3] == "skill") parts[2] else null
    }

    private fun chatToolLogsChatIdFromPath(path: String): String? {
        val parts = path.split('/').filter { it.isNotBlank() }
        return if (parts.size == 5 && parts[0] == "api" && parts[1] == "chats" && parts[3] == "tools" && parts[4] == "logs") parts[2] else null
    }

    private fun chatGenerationCancelIdFromPath(path: String): String? {
        val parts = path.split('/').filter { it.isNotBlank() }
        return if (parts.size == 5 && parts[0] == "api" && parts[1] == "chats" && parts[3] == "generation" && parts[4] == "cancel") parts[2] else null
    }

    private fun chatGenerationRetryIdFromPath(path: String): String? {
        val parts = path.split('/').filter { it.isNotBlank() }
        return if (parts.size == 5 && parts[0] == "api" && parts[1] == "chats" && parts[3] == "generation" && parts[4] == "retry") parts[2] else null
    }

    private fun chatGenerationListIdFromPath(path: String): String? {
        val parts = path.split('/').filter { it.isNotBlank() }
        return if (parts.size == 4 && parts[0] == "api" && parts[1] == "chats" && parts[3] == "generations") parts[2] else null
    }

    private fun generationCancelIdFromPath(path: String): String? {
        val parts = path.split('/').filter { it.isNotBlank() }
        return if (parts.size == 4 && parts[0] == "api" && parts[1] == "generations" && parts[3] == "cancel") parts[2] else null
    }

    private fun generationIdFromPath(path: String): String? {
        val parts = path.split('/').filter { it.isNotBlank() }
        return if (parts.size == 3 && parts[0] == "api" && parts[1] == "generations") parts[2] else null
    }

    private fun skillSlugFromPath(path: String): String? {
        val parts = path.split('/').filter { it.isNotBlank() }
        return if (parts.size == 3 && parts[0] == "api" && parts[1] == "skills") parts[2] else null
    }

    private fun adminSkillSlugFromPath(path: String): String? {
        val parts = path.split('/').filter { it.isNotBlank() }
        return if (parts.size == 4 && parts[0] == "api" && parts[1] == "admin" && parts[2] == "skills") parts[3] else null
    }

    private fun chatFileAttachmentFromPath(path: String): Pair<String, String>? {
        val parts = path.split('/').filter { it.isNotBlank() }
        return if (parts.size == 5 && parts[0] == "api" && parts[1] == "chats" && parts[3] == "files") {
            parts[2] to parts[4]
        } else {
            null
        }
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
            errorResponse(Response.Status.NOT_FOUND, "not_found", "Not found")
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
                    .put("webAdmin", "GET /admin")
                    .put("models", "GET /v1/models")
                    .put("codingModels", "GET /coding/v1/models")
                    .put("conversationModels", "GET /conversation/v1/models")
                    .put("chatCompletions", "POST /v1/chat/completions")
                    .put("codingChat", "POST /coding/v1/chat/completions")
                    .put("conversationChat", "POST /conversation/v1/chat/completions")
                    .put("register", "POST /auth/register")
                    .put("login", "POST /auth/login")
                    .put("logout", "POST /auth/logout")
                    .put("logoutAll", "POST /auth/logout-all")
                    .put("session", "GET /auth/session")
                    .put("chats", "GET/POST /api/chats")
                    .put("chatDetail", "GET/DELETE /api/chats/{chatId}")
                    .put("chatMessages", "POST /api/chats/{chatId}/messages")
                    .put("skills", "GET /api/skills")
                    .put("chatSkill", "GET/PUT /api/chats/{chatId}/skill")
                    .put("tools", "GET /api/tools")
                    .put("toolLogs", "GET /api/chats/{chatId}/tools/logs")
                    .put("chatFiles", "GET/POST /api/chats/{chatId}/files")
                    .put("chatFileDetail", "DELETE /api/chats/{chatId}/files/{fileId}")
                    .put("files", "GET /api/files")
                    .put("fileUpload", "POST /api/files/upload")
                    .put("fileDetail", "GET/DELETE /api/files/{fileId}")
                    .put("adminStatus", "GET /api/admin/status")
                    .put("adminUsers", "GET /api/admin/users")
                    .put("adminFiles", "GET /api/admin/files")
                    .put("adminSkills", "POST/PUT/DELETE /api/admin/skills")
                    .put("adminTools", "GET /api/admin/tools")
                    .put("adminToolLogs", "GET /api/admin/tools/logs")
                    .put("resetConversation", "POST /v1/conversation/reset")
                    .put("performance", "GET /debug/perf")
                    .put("performanceHistory", "GET /debug/perf/history")
                    .put("config", "GET/POST /debug/config")
                    .put("benchmark", "POST /debug/benchmark")
            )
            .put("note", "Use POST for /v1/chat/completions. Browser GET requests do not run inference.")
    }

    private fun healthResponse(): Response {
        val storageWritable = runCatching {
            val probe = java.io.File(appContext.filesDir, ".health-write-check")
            probe.writeText("ok")
            probe.delete()
        }.getOrDefault(false)
        val databaseAvailable = runCatching {
            AppDatabase(appContext).readableDatabase.rawQuery("SELECT 1", emptyArray()).use { it.moveToFirst() }
        }.getOrDefault(false)
        return jsonResponse(
            Response.Status.OK,
            JSONObject()
                .put("status", "ok")
                .put("appAlive", true)
                .put("databaseAvailable", databaseAvailable)
                .put("modelLoaded", liteRtLmManager.isLoaded())
                .put("storageWritable", storageWritable)
                .put("securityMode", securityMode.name)
                .put("serverMode", serverMode)
                .put("diagnostics", if (securityMode == SecurityMode.TRUSTED_LAN) "admin-required" else "local-dev-open")
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

    private fun debugRoutesResponse(session: IHTTPSession): Response {
        if (securityMode == SecurityMode.TRUSTED_LAN && authRepository.requireAdmin(sessionTokenFromRequest(session)) == null) {
            return errorResponse(Response.Status.UNAUTHORIZED, "admin_required", "Admin session required in TRUSTED_LAN mode")
        }
        return jsonResponse(
            Response.Status.OK,
            JSONObject()
                .put("status", "ok")
                .put("routesEnabled", true)
                .put("securityMode", securityMode.name)
                .put("cors", true)
                .put("privateNetworkAccess", true)
                .put("modelsEndpoint", true)
                .put("codingModelsEndpoint", "GET /coding/v1/models")
                .put("conversationModelsEndpoint", "GET /conversation/v1/models")
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
                .put("adminStatusEndpoint", "GET /api/admin/status")
                .put("adminUsersEndpoint", "GET /api/admin/users")
                .put("adminFilesEndpoint", "GET /api/admin/files")
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
            is AuthResult.Throttled -> errorResponse(
                Response.Status.TOO_MANY_REQUESTS,
                "login_throttled",
                "Too many failed login attempts. Try again later.",
                JSONObject().put("retryAfterSeconds", result.retryAfterSeconds),
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
            is AuthResult.Throttled -> errorResponse(
                Response.Status.TOO_MANY_REQUESTS,
                "login_throttled",
                "Too many failed login attempts. Try again later.",
                JSONObject().put("retryAfterSeconds", result.retryAfterSeconds),
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

    private fun logoutAllResponse(session: IHTTPSession): Response {
        val token = sessionTokenFromRequest(session)
        if (authRepository.requireUser(token) == null) {
            return unauthorizedResponse()
        }
        authRepository.logoutAllForToken(token)
        return jsonResponse(
            Response.Status.OK,
            JSONObject()
                .put("status", "ok")
                .put("message", "Logged out all sessions for current user")
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

    private fun adminPageResponse(session: IHTTPSession): Response {
        val token = sessionTokenFromRequest(session)
        val user = authRepository.requireUser(token)
        if (user == null) return redirectResponse("/login")
        if (user.role != UserRole.ADMIN) {
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                "text/html",
                """
                <!doctype html>
                <html>
                  <body>
                    <h1>Access denied</h1>
                    <p>Admin access required.</p>
                    <a href="/chat">Back to chat</a>
                  </body>
                </html>
                """.trimIndent()
            ).apply {
                addCorsHeaders()
            }
        }
        return webAssetResponse("admin.html")
    }

    private fun adminStatusResponse(session: IHTTPSession): Response {
        return withAdmin(session) {
            val host = publicHost(session)
            val base = "http://$host"
            val files = fileRepository.listAdminFileOverviews()
            val users = authRepository.listAdminUserOverviews()
            val perf = liteRtLmManager.performanceSnapshot()
            jsonResponse(
                Response.Status.OK,
                JSONObject()
                    .put("modelLoaded", liteRtLmManager.isLoaded())
                    .put("backendStatus", perf.backendStatus)
                    .put("serverMode", serverMode)
                    .put("lanIp", NetworkUtils.lanIpv4Candidates().firstOrNull() ?: "")
                    .put("codingBaseUrl", "$base/coding/v1")
                    .put("conversationBaseUrl", "$base/conversation/v1")
                    .put("normalWebUrl", "$base/chat")
                    .put("compatibilityBaseUrl", "$base/v1")
                    .put("totalUsers", users.size)
                    .put("totalFiles", files.size)
                    .put("totalStorageBytes", files.sumOf { it.sizeBytes })
                    .put("totalChats", chatRepository.totalChatCount())
                    .put("speculativeDecodingRequested", perf.speculativeDecodingRequested)
                    .put("speculativeDecodingEnabled", perf.speculativeDecodingEnabled)
                    .put("speculativeDecodingAvailable", perf.speculativeDecodingAvailable)
                    .put("speculativeDecodingError", perf.speculativeDecodingError ?: JSONObject.NULL)
                    .put("activeGeneration", perf.activeGeneration)
                    .put(
                        "debug",
                        JSONObject()
                            .put("perf", "/debug/perf")
                            .put("perfHistory", "/debug/perf/history")
                            .put("config", "/debug/config")
                            .put("routes", "/debug/routes")
                            .put("health", "/health")
                    )
            )
        }
    }

    private fun adminUsersResponse(session: IHTTPSession): Response {
        return withAdmin(session) {
            val users = JSONArray()
            authRepository.listAdminUserOverviews().forEach { users.put(adminUserJson(it)) }
            jsonResponse(Response.Status.OK, JSONObject().put("users", users))
        }
    }

    private fun adminFilesResponse(session: IHTTPSession): Response {
        return withAdmin(session) {
            val files = JSONArray()
            fileRepository.listAdminFileOverviews().forEach { files.put(adminFileJson(it)) }
            jsonResponse(Response.Status.OK, JSONObject().put("files", files))
        }
    }

    private fun adminSkillsResponse(session: IHTTPSession): Response = withAdmin(session) {
        val skills = JSONArray()
        skillRepository.listSkills(enabledOnly = false).forEach { skills.put(skillJson(it, includePrompt = true)) }
        jsonResponse(Response.Status.OK, JSONObject().put("skills", skills))
    }

    private fun adminExportSkillsResponse(session: IHTTPSession): Response = withAdmin(session) {
        jsonResponse(Response.Status.OK, JSONObject().put("skills", skillRepository.exportCustomSkills()))
    }

    private fun adminImportSkillsResponse(session: IHTTPSession): Response = withAdmin(session) {
        val json = readJsonRequest(session) ?: return@withAdmin errorResponse(Response.Status.BAD_REQUEST, "bad_json", "Malformed JSON")
        val array = json.optJSONArray("skills") ?: return@withAdmin errorResponse(Response.Status.BAD_REQUEST, "invalid_skills", "skills must be an array")
        val (imported, error) = skillRepository.importCustomSkills(array)
        if (error != null) return@withAdmin errorResponse(Response.Status.BAD_REQUEST, "invalid_skill_import", error)
        jsonResponse(Response.Status.OK, JSONObject().put("status", "ok").put("imported", imported))
    }

    private fun adminSkillTestResponse(session: IHTTPSession): Response = withAdmin(session) {
        val json = readJsonRequest(session) ?: return@withAdmin errorResponse(Response.Status.BAD_REQUEST, "bad_json", "Malformed JSON")
        val slug = json.optString("skillSlug").trim().takeIf { it.isNotBlank() }
            ?: return@withAdmin errorResponse(Response.Status.BAD_REQUEST, "missing_skill", "skillSlug is required")
        val prompt = json.optString("prompt").trim().takeIf { it.isNotBlank() }
            ?: return@withAdmin errorResponse(Response.Status.BAD_REQUEST, "missing_prompt", "prompt is required")
        val skill = skillRepository.getSkillBySlug(slug) ?: return@withAdmin notFoundResponse()
        if (!liteRtLmManager.isLoaded()) {
            return@withAdmin errorResponse(Response.Status.SERVICE_UNAVAILABLE, "model_not_loaded", "Model is not loaded")
        }
        val responseMode = responseModeForSkill(skill, ChatProfile.CUSTOM)
        val testPrompt = buildString {
            appendLine(skill.systemPrompt)
            appendLine()
            appendLine("Admin skill test prompt:")
            appendLine(prompt)
            append("assistant:")
        }
        val output = runBlocking {
            liteRtLmManager.generate(
                prompt = liteRtLmManager.applyResponseModeHint(testPrompt, responseMode),
                conversationModeOverride = ConversationMode.FRESH_PER_REQUEST,
                responseModeOverride = responseMode,
            )
        }
        output.fold(
            onSuccess = {
                jsonResponse(
                    Response.Status.OK,
                    JSONObject()
                        .put("status", "ok")
                        .put("skill", skillJson(skill, includePrompt = false))
                        .put("response", parseAndValidateResponse(skill, it, prompt).finalText)
                )
            },
            onFailure = {
                jsonResponse(Response.Status.INTERNAL_ERROR, JSONObject().put("error", it.message ?: "Skill test failed"))
            }
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
                .put("files", chatFilesJson(user.id, chatId))
        )
    }

    private fun listChatFilesResponse(session: IHTTPSession, chatId: String): Response {
        val user = requireAppUser(session) ?: return unauthorizedResponse()
        val files = chatRepository.listAttachedFiles(user.id, chatId) ?: return notFoundResponse()
        val array = JSONArray()
        files.forEach { array.put(fileJson(it)) }
        return jsonResponse(Response.Status.OK, JSONObject().put("files", array))
    }

    private fun attachChatFileResponse(session: IHTTPSession, chatId: String): Response {
        val user = requireAppUser(session) ?: return unauthorizedResponse()
        val requestJson = readJsonRequest(session) ?: return jsonResponse(
            Response.Status.BAD_REQUEST,
            JSONObject().put("error", "Malformed JSON or missing JSON body")
        )
        val fileId = requestJson.optString("fileId").trim().takeIf { it.isNotBlank() } ?: return jsonResponse(
            Response.Status.BAD_REQUEST,
            JSONObject().put("error", "fileId is required")
        )
        if (!chatRepository.attachFile(user.id, chatId, fileId)) return notFoundResponse()
        return jsonResponse(
            Response.Status.OK,
            JSONObject()
                .put("status", "ok")
                .put("fileId", fileId)
        )
    }

    private fun detachChatFileResponse(session: IHTTPSession, chatId: String, fileId: String): Response {
        val user = requireAppUser(session) ?: return unauthorizedResponse()
        if (!chatRepository.detachFile(user.id, chatId, fileId)) return notFoundResponse()
        return jsonResponse(Response.Status.OK, JSONObject().put("status", "ok"))
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


    private fun listSkillsResponse(session: IHTTPSession): Response {
        requireAppUser(session) ?: return unauthorizedResponse()
        val skills = JSONArray()
        skillRepository.listEnabledSkills().forEach { skills.put(skillJson(it, includePrompt = false)) }
        return jsonResponse(Response.Status.OK, JSONObject().put("skills", skills))
    }

    private fun getSkillResponse(session: IHTTPSession, slug: String): Response {
        requireAppUser(session) ?: return unauthorizedResponse()
        val skill = skillRepository.getSkillBySlug(slug) ?: return notFoundResponse()
        return jsonResponse(Response.Status.OK, JSONObject().put("skill", skillJson(skill, includePrompt = false)))
    }

    private fun adminCreateSkillResponse(session: IHTTPSession): Response = withAdmin(session) {
        val json = readJsonRequest(session) ?: return@withAdmin jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "Malformed JSON"))
        val skill = skillRepository.upsertCustomSkill(json) ?: return@withAdmin jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "Invalid skill"))
        jsonResponse(Response.Status.OK, JSONObject().put("skill", skillJson(skill, includePrompt = true)))
    }

    private fun adminUpdateSkillResponse(session: IHTTPSession, slug: String): Response = withAdmin(session) {
        val json = readJsonRequest(session) ?: return@withAdmin jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "Malformed JSON"))
        val skill = skillRepository.upsertCustomSkill(json, existingSlug = slug) ?: return@withAdmin jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "Invalid skill"))
        jsonResponse(Response.Status.OK, JSONObject().put("skill", skillJson(skill, includePrompt = true)))
    }

    private fun adminDeleteSkillResponse(session: IHTTPSession, slug: String): Response = withAdmin(session) {
        if (!skillRepository.disableOrDeleteSkill(slug)) return@withAdmin notFoundResponse()
        jsonResponse(Response.Status.OK, JSONObject().put("status", "ok"))
    }

    private fun adminToolLogsResponse(session: IHTTPSession): Response = withAdmin(session) {
        val logs = JSONArray()
        skillRepository.listRecentToolLogs().forEach { log ->
            logs.put(toolLogJson(log))
        }
        jsonResponse(Response.Status.OK, JSONObject().put("logs", logs))
    }

    private fun getChatSkillResponse(session: IHTTPSession, chatId: String): Response {
        val user = requireAppUser(session) ?: return unauthorizedResponse()
        chatRepository.getChat(user.id, chatId) ?: return notFoundResponse()
        val state = skillRepository.getChatSkillState(chatId)
        val skill = skillRepository.getSkillBySlug(state.skillSlug) ?: return notFoundResponse()
        return jsonResponse(Response.Status.OK, JSONObject().put("skill", skillJson(skill, false)).put("state", chatSkillStateJson(state)))
    }

    private fun putChatSkillResponse(session: IHTTPSession, chatId: String): Response {
        val user = requireAppUser(session) ?: return unauthorizedResponse()
        chatRepository.getChat(user.id, chatId) ?: return notFoundResponse()
        val json = readJsonRequest(session) ?: return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "Malformed JSON"))
        val current = skillRepository.getChatSkillState(chatId)
        val slug = json.optString("skillSlug", current.skillSlug).trim().ifBlank { current.skillSlug }
        val state = skillRepository.setChatSkillState(
            chatId,
            slug,
            if (json.has("thinkingEnabled")) json.optBoolean("thinkingEnabled") else null,
            if (json.has("showThinking")) json.optBoolean("showThinking") else null,
        ) ?: return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "Skill is unavailable"))
        val skill = skillRepository.getSkillBySlug(state.skillSlug) ?: return notFoundResponse()
        return jsonResponse(Response.Status.OK, JSONObject().put("skill", skillJson(skill, false)).put("state", chatSkillStateJson(state)))
    }

    private fun listToolsResponse(session: IHTTPSession, admin: Boolean): Response {
        if (admin) {
            val user = authRepository.requireUser(sessionTokenFromRequest(session)) ?: return unauthorizedResponse()
            if (user.role != UserRole.ADMIN) return jsonResponse(Response.Status.FORBIDDEN, JSONObject().put("error", "Forbidden"))
        } else {
            requireAppUser(session) ?: return unauthorizedResponse()
        }
        val array = JSONArray()
        (if (admin) toolRegistry.listAdminMetadata() else toolRegistry.listSafeMetadata()).forEach { array.put(toolJson(it, admin)) }
        return jsonResponse(Response.Status.OK, JSONObject().put("tools", array))
    }

    private fun chatToolLogsResponse(session: IHTTPSession, chatId: String): Response {
        val user = requireAppUser(session) ?: return unauthorizedResponse()
        chatRepository.getChat(user.id, chatId) ?: return notFoundResponse()
        val logs = JSONArray()
        skillRepository.listToolLogs(chatId).forEach { log ->
            logs.put(toolLogJson(log))
        }
        return jsonResponse(Response.Status.OK, JSONObject().put("logs", logs))
    }

    private fun listGenerationsResponse(session: IHTTPSession, chatId: String): Response {
        val user = requireAppUser(session) ?: return unauthorizedResponse()
        chatRepository.getChat(user.id, chatId) ?: return notFoundResponse()
        val array = JSONArray()
        generationJobs.recentForChat(chatId).forEach { array.put(generationJobJson(it)) }
        return jsonResponse(Response.Status.OK, JSONObject().put("generations", array))
    }

    private fun getGenerationResponse(session: IHTTPSession, generationId: String): Response {
        val user = requireAppUser(session) ?: return unauthorizedResponse()
        val job = generationJobs.get(generationId) ?: return notFoundResponse()
        if (job.userId != user.id) return notFoundResponse()
        return jsonResponse(Response.Status.OK, JSONObject().put("generation", generationJobJson(job)))
    }

    private fun cancelGenerationResponse(session: IHTTPSession, generationId: String): Response {
        val user = requireAppUser(session) ?: return unauthorizedResponse()
        val job = generationJobs.get(generationId) ?: return notFoundResponse()
        if (job.userId != user.id) return notFoundResponse()
        liteRtLmManager.cancelCurrentGeneration()
        val cancelled = generationJobs.cancel(generationId) ?: job
        return jsonResponse(Response.Status.OK, JSONObject().put("generation", generationJobJson(cancelled)))
    }

    private fun cancelChatGenerationResponse(session: IHTTPSession, chatId: String): Response {
        val user = requireAppUser(session) ?: return unauthorizedResponse()
        chatRepository.getChat(user.id, chatId) ?: return notFoundResponse()
        val job = generationJobs.activeForChat(chatId) ?: return errorResponse(Response.Status.CONFLICT, "no_active_generation", "No active generation for this chat")
        liteRtLmManager.cancelCurrentGeneration()
        val cancelled = generationJobs.cancel(job.id) ?: job
        return jsonResponse(Response.Status.OK, JSONObject().put("generation", generationJobJson(cancelled)))
    }

    private fun retryChatGenerationResponse(session: IHTTPSession, chatId: String): Response {
        val user = requireAppUser(session) ?: return unauthorizedResponse()
        val chat = chatRepository.getChat(user.id, chatId) ?: return notFoundResponse()
        if (generationJobs.activeAny() != null || liteRtLmManager.performanceSnapshot().activeGeneration) {
            return errorResponse(Response.Status.CONFLICT, "generation_active", "Another generation is already active")
        }
        val messages = chatRepository.listMessages(user.id, chat.id).orEmpty()
        val lastUser = messages.lastOrNull { it.role == "user" }
            ?: return errorResponse(Response.Status.BAD_REQUEST, "no_user_message", "No user message is available to retry")
        val activeState = skillRepository.getChatSkillState(chat.id)
        val skill = skillRepository.getSkillBySlug(activeState.skillSlug)
            ?: return errorResponse(Response.Status.BAD_REQUEST, "skill_unavailable", "Skill is unavailable")
        val fileIds = chatRepository.listAttachedFileIds(user.id, chat.id).orEmpty()
        val responseMode = responseModeForSkill(skill, chat.profile)
        val promptPlan = buildAppPromptPlan(user.id, chat.id, fileIds, messages.filter { it.createdAtMs <= lastUser.createdAtMs }, lastUser, responseMode, retry = true, skill = skill, skillState = activeState)
            ?: return notFoundResponse()
        if (!liteRtLmManager.isLoaded()) return errorResponse(Response.Status.SERVICE_UNAVAILABLE, "model_not_loaded", "Model is not loaded")
        val job = generationJobs.create(chat.id, user.id, lastUser.id)
        generationJobs.markRunning(job.id, streaming = false)
        val result = generateAppReply(user.id, chat, lastUser, promptPlan, responseMode, ConversationMode.FRESH_PER_REQUEST)
        return result.fold(
            onSuccess = { (usedPlan, parsed) ->
                updateContextState(chat.id, usedPlan.context)
                val assistant = chatRepository.addMessage(chat.id, "assistant", parsed.finalText, thinking = parsed.thinkingText, rawContent = parsed.rawModelText)
                val done = generationJobs.complete(job.id, assistant.id, parsed.finalText) ?: job
                jsonResponse(Response.Status.OK, JSONObject().put("message", messageJson(assistant)).put("generation", generationJobJson(done)))
            },
            onFailure = {
                val failed = generationJobs.fail(job.id, it) ?: job
                jsonResponse(Response.Status.INTERNAL_ERROR, JSONObject().put("error", promptBudgetManager.friendlyError(it).second).put("generation", generationJobJson(failed)))
            }
        )
    }

    private fun createMessageResponse(session: IHTTPSession, chatId: String): Response {
        val user = requireAppUser(session) ?: return unauthorizedResponse()
        val chat = chatRepository.getChat(user.id, chatId) ?: return notFoundResponse()
        if (generationJobs.activeAny() != null || liteRtLmManager.performanceSnapshot().activeGeneration) {
            return errorResponse(Response.Status.CONFLICT, "generation_active", "Another generation is already active")
        }
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
        val fileIds = if (requestJson.has("fileIds") && !requestJson.isNull("fileIds")) {
            parseFileIds(requestJson)
        } else {
            chatRepository.listAttachedFileIds(user.id, chat.id) ?: return notFoundResponse()
        }
        val requestedSkillSlug = requestJson.optString("skillSlug").trim().takeIf { it.isNotBlank() }
        val baseState = skillRepository.getChatSkillState(chat.id)
        val activeState = if (requestedSkillSlug != null || requestJson.has("thinkingEnabled") || requestJson.has("showThinking")) {
            skillRepository.setChatSkillState(
                chat.id,
                requestedSkillSlug ?: baseState.skillSlug,
                if (requestJson.has("thinkingEnabled")) requestJson.optBoolean("thinkingEnabled") else null,
                if (requestJson.has("showThinking")) requestJson.optBoolean("showThinking") else null,
            ) ?: return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "Skill is unavailable"))
        } else {
            baseState
        }
        val skill = skillRepository.getSkillBySlug(activeState.skillSlug) ?: return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "Skill is unavailable"))
        val userMessage = chatRepository.addMessage(chat.id, "user", content)
        val messages = chatRepository.listMessages(user.id, chat.id).orEmpty()
        val conversationMode = ConversationMode.FRESH_PER_REQUEST
        val responseMode = responseModeForSkill(skill, chat.profile)
        val promptPlan = buildAppPromptPlan(user.id, chat.id, fileIds, messages, userMessage, responseMode, retry = false, skill = skill, skillState = activeState)
            ?: return notFoundResponse()
        val prompt = liteRtLmManager.applyResponseModeHint(promptPlan.plan.finalPrompt, responseMode)
        val generationJob = generationJobs.create(chat.id, user.id, userMessage.id)
        return if (requestJson.optBoolean("stream", false)) {
            streamingAppMessageResponse(chat, userMessage, promptPlan, prompt, conversationMode, responseMode, generationJob.id)
        } else {
            if (!liteRtLmManager.isLoaded()) {
                generationJobs.fail(generationJob.id, IllegalStateException("Model is not loaded"))
                return errorResponse(Response.Status.SERVICE_UNAVAILABLE, "model_not_loaded", "Model is not loaded")
            }
            generationJobs.markRunning(generationJob.id, streaming = false)
            val result = generateAppReply(user.id, chat, userMessage, promptPlan, responseMode, conversationMode)
            result.fold(
                onSuccess = { (usedPlan, parsed) ->
                    updateContextState(chat.id, usedPlan.context)
                    val assistantMessage = chatRepository.addMessage(chat.id, "assistant", parsed.finalText, thinking = parsed.thinkingText, rawContent = parsed.rawModelText)
                    val completedJob = generationJobs.complete(generationJob.id, assistantMessage.id, parsed.finalText) ?: generationJob
                    jsonResponse(
                        Response.Status.OK,
                        JSONObject()
                            .put("message", messageJson(assistantMessage))
                            .put("userMessage", messageJson(userMessage))
                            .put("context", contextJson(usedPlan.plan.contextMetadata))
                            .put("skill", skillJson(usedPlan.skill, false))
                            .put("generation", generationJobJson(completedJob))
                            .put("thinking", JSONObject().put("present", parsed.thinkingText != null).put("visible", usedPlan.skillState.showThinking))
                    )
                },
                onFailure = { error ->
                    val failedJob = generationJobs.fail(generationJob.id, error) ?: generationJob
                    val friendly = promptBudgetManager.friendlyError(error)
                    jsonResponse(
                        Response.Status.INTERNAL_ERROR,
                        JSONObject()
                            .put("error", friendly.second)
                            .put("code", friendly.first)
                            .put("generation", generationJobJson(failedJob))
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

    private fun appStreamingErrorJson(error: Throwable): JSONObject {
        val friendly = promptBudgetManager.friendlyError(error)
        return JSONObject()
            .put(
                "error",
                JSONObject()
                    .put("code", friendly.first)
                    .put("message", friendly.second)
            )
    }


    private fun generateAppReply(
        userId: String,
        chat: ChatRecord,
        userMessage: MessageRecord,
        initialPlan: AppPromptPlan,
        responseMode: ResponseMode,
        conversationMode: ConversationMode,
        retry: Boolean = true,
        onToolEvent: ((JSONObject) -> Unit)? = null,
    ): Result<Pair<AppPromptPlan, ParsedModelResponse>> {
        var usedPlan = initialPlan
        var result = runBlocking {
            liteRtLmManager.generate(
                prompt = liteRtLmManager.applyResponseModeHint(usedPlan.plan.finalPrompt, responseMode),
                conversationModeOverride = conversationMode,
                responseModeOverride = responseMode,
            )
        }
        val firstError = result.exceptionOrNull()
        if (retry && firstError != null && promptBudgetManager.isTokenOverflow(firstError)) {
            val retryPlan = buildAppPromptPlan(userId, chat.id, usedPlan.fileIds, usedPlan.messages, userMessage, responseMode, retry = true, skill = usedPlan.skill, skillState = usedPlan.skillState)
                ?: return Result.failure(firstError)
            usedPlan = retryPlan
            result = runBlocking {
                liteRtLmManager.generate(
                    prompt = liteRtLmManager.applyResponseModeHint(retryPlan.plan.finalPrompt, responseMode),
                    conversationModeOverride = conversationMode,
                    responseModeOverride = responseMode,
                )
            }
        }
        val firstText = result.getOrElse { return Result.failure(it) }
        val toolCall = toolRegistry.parseToolCall(firstText)
        if (toolCall != null && usedPlan.skill.toolUseMode != ToolUseMode.NONE) {
            onToolEvent?.invoke(JSONObject().put("toolCall", JSONObject().put("name", toolCall.name).put("status", "started")))
            val toolResult = toolRegistry.execute(userId, chat.id, usedPlan.skill, toolCall)
            skillRepository.insertToolLog(chat.id, userMessage.id, toolCall.name, toolCall.raw, toolResult.result, toolResult.status, toolResult.error)
            onToolEvent?.invoke(JSONObject().put("toolCall", JSONObject().put("name", toolCall.name).put("status", toolResult.status.name.lowercase())))
            val toolBlock = if (toolResult.status == ToolCallStatus.SUCCESS) {
                "Tool result for ${toolCall.name}: ${toolResult.result}"
            } else {
                "The requested tool '${toolCall.name}' is unavailable or rejected. Continue safely without using it."
            }
            val followPlan = buildAppPromptPlan(userId, chat.id, usedPlan.fileIds, usedPlan.messages, userMessage, responseMode, retry = false, skill = usedPlan.skill, skillState = usedPlan.skillState, toolResultBlock = toolBlock)
                ?: usedPlan
            usedPlan = followPlan
            val follow = runBlocking {
                liteRtLmManager.generate(
                    prompt = liteRtLmManager.applyResponseModeHint(followPlan.plan.finalPrompt, responseMode),
                    conversationModeOverride = conversationMode,
                    responseModeOverride = responseMode,
                )
            }.getOrElse { return Result.failure(it) }
            return Result.success(usedPlan to parseAndValidateResponse(usedPlan.skill, follow, userMessage.content))
        }
        return Result.success(usedPlan to parseAndValidateResponse(usedPlan.skill, firstText, userMessage.content))
    }

    private fun parseAndValidateResponse(skill: SkillRecord, raw: String, userContent: String): ParsedModelResponse {
        var parsed = parseThinking(raw)
        if (skill.strictOutput) {
            val minified = minifiedJsonObject(parsed.finalText) ?: minifiedJsonObject(parsed.rawModelText)
            parsed = if (minified != null) {
                parsed.copy(finalText = minified)
            } else if (skill.slug == "gdpr-pii-audit") {
                parsed.copy(finalText = gdprFallbackJson(userContent))
            } else {
                parsed.copy(finalText = "The model did not return valid JSON for this skill. Please try again.")
            }
        }
        return parsed
    }

    private fun gdprFallbackJson(text: String): String {
        val hasNameLikePair = Regex("\\b[A-ZÄÖÜ][a-zäöüß]+\\s+[A-ZÄÖÜ][a-zäöüß]+\\b").containsMatchIn(text)
        val hasIdentifier = Regex("(?i)\\b(student|pupil|teacher|staff|parent|class|klasse|id|email|absent|illness|krank)\\b").containsMatchIn(text)
        val aggregateOnly = Regex("(?i)\\b(aggregated|anonymous|anonymized|percent|percentage|increased|decreased|average|total)\\b").containsMatchIn(text) && !hasNameLikePair
        val confirm = (hasNameLikePair || hasIdentifier && !aggregateOnly)
        val reason = if (confirm) {
            "The snippet appears to contain identifiable student, staff, or parent information or specific identifiers."
        } else {
            "The snippet appears aggregated or anonymized and does not identify a specific student, staff member, or parent."
        }
        return JSONObject().put("confirm_deletion", confirm).put("reason", reason).toString()
    }

    private fun minifiedJsonObject(text: String): String? {
        val cleaned = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val json = runCatching { JSONObject(cleaned) }.getOrNull() ?: return null
        return json.toString()
    }

    private fun parseThinking(raw: String): ParsedModelResponse {
        val patterns = listOf(
            Regex("(?s)<\\|think\\|>(.*?)</\\|think\\|>"),
            Regex("(?s)<think>(.*?)</think>"),
        )
        for (pattern in patterns) {
            val match = pattern.find(raw)
            if (match != null) {
                val thinking = match.groupValues[1].trim().takeIf { it.isNotBlank() }
                val final = raw.replaceRange(match.range, "").trim()
                return ParsedModelResponse(raw, thinking, final)
            }
        }
        val openGemma = raw.indexOf("<|think|>")
        val openXml = raw.indexOf("<think>")
        val open = listOf(openGemma, openXml).filter { it >= 0 }.minOrNull()
        if (open != null) return ParsedModelResponse(raw, raw.substring(open).trim().takeIf { it.isNotBlank() }, raw.substring(0, open).trim())
        return ParsedModelResponse(raw, null, raw.trim())
    }

    private fun buildSkillPromptPrefix(skill: SkillRecord, state: ChatSkillStateRecord, toolResultBlock: String?): String {
        return buildString {
            appendLine(skill.systemPrompt)
            appendLine()
            if (state.thinkingEnabled) {
                appendLine("If you need to reason internally, place reasoning inside:")
                appendLine("<|think|>")
                appendLine("...")
                appendLine("</|think|>")
                appendLine("Then provide the final answer after the thinking block.")
            } else {
                appendLine("Do not include <|think|> blocks. Provide only the final answer.")
            }
            if (skill.strictOutput) {
                appendLine("Strict output mode: output only the requested schema. Do not use Markdown fences.")
            }
            val toolInstructions = toolRegistry.toolInstructions(skill)
            if (toolInstructions.isNotBlank()) {
                appendLine()
                appendLine(toolInstructions)
            }
            if (!toolResultBlock.isNullOrBlank()) {
                appendLine()
                appendLine("[Tool Result]")
                appendLine(toolResultBlock)
                appendLine("[/Tool Result]")
                appendLine("Use the tool result above to produce the final answer. Do not output another tool call.")
            }
        }.trim()
    }

    private fun responseModeForSkill(skill: SkillRecord, profile: ChatProfile): ResponseMode {
        return when (skill.responseMode) {
            "CODING_CONCISE" -> ResponseMode.CODING_CONCISE
            "BALANCED" -> ResponseMode.BALANCED
            else -> responseModeForChatProfile(profile)
        }
    }

    private fun streamingAppMessageResponse(
        chat: ChatRecord,
        userMessage: MessageRecord,
        promptPlan: AppPromptPlan,
        prompt: String,
        conversationMode: ConversationMode,
        responseMode: ResponseMode,
        generationId: String,
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
                        generationJobs.fail(generationId, IllegalStateException("Model is not loaded"))
                        writeEvent(JSONObject().put("error", "Model is not loaded").toString())
                        writeEvent("[DONE]")
                        return@use
                    }
                    generationJobs.markRunning(generationId, streaming = true)
                    writeEvent(JSONObject().put("generation", generationJobJson(generationJobs.get(generationId)!!)).toString())
                    writeEvent(JSONObject().put("context", contextJson(promptPlan.plan.contextMetadata)).toString())
                    writeEvent(JSONObject().put("skill", skillJson(promptPlan.skill, false)).toString())
                    val result = generateAppReply(
                        userId = chat.userId,
                        chat = chat,
                        userMessage = userMessage,
                        initialPlan = promptPlan,
                        responseMode = responseMode,
                        conversationMode = conversationMode,
                        onToolEvent = { writeEvent(it.toString()) },
                    )
                    result.fold(
                        onSuccess = { (usedPlan, parsed) ->
                            updateContextState(chat.id, usedPlan.context)
                            if (parsed.thinkingText != null && usedPlan.skillState.showThinking) {
                                writeEvent(JSONObject().put("thinking", JSONObject().put("content", parsed.thinkingText).put("visible", true)).toString())
                            }
                            if (parsed.finalText.isNotBlank()) writeEvent(JSONObject().put("content", parsed.finalText).toString())
                            val assistantMessage = chatRepository.addMessage(chat.id, "assistant", parsed.finalText, thinking = parsed.thinkingText, rawContent = parsed.rawModelText)
                            generationJobs.complete(generationId, assistantMessage.id, parsed.finalText)
                            writeEvent(JSONObject().put("message", messageJson(assistantMessage)).toString())
                            writeEvent(JSONObject().put("generation", generationJobJson(generationJobs.get(generationId)!!)).toString())
                            writeEvent("[DONE]")
                        },
                        onFailure = { error ->
                            generationJobs.fail(generationId, error)
                            writeEvent(appStreamingErrorJson(error).toString())
                            writeEvent(JSONObject().put("generation", generationJobJson(generationJobs.get(generationId)!!)).toString())
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

    private fun buildAppPromptPlan(
        userId: String,
        chatId: String,
        fileIds: List<String>,
        messages: List<MessageRecord>,
        userMessage: MessageRecord,
        responseMode: ResponseMode,
        retry: Boolean,
        skill: SkillRecord,
        skillState: ChatSkillStateRecord,
        toolResultBlock: String? = null,
    ): AppPromptPlan? {
        val continuationMode = promptBudgetManager.isContinuationRequest(userMessage.content)
        val state = if (continuationMode) {
            fileIds.associateWith { fileId -> chatRepository.getContextState(chatId, fileId)?.lastIncludedChunkIndex ?: -1 }
        } else {
            emptyMap()
        }
        val budget = promptBudgetManager.calculateFileBudget(
            messages = messages,
            currentUserMessageId = userMessage.id,
            selectedFileIds = fileIds,
            responseMode = responseMode,
            continuationMode = continuationMode,
            retry = retry,
        )
        val context = if (fileIds.isEmpty()) {
            null
        } else {
            fileRepository.buildContext(
                userId = userId,
                fileIds = fileIds,
                budgetChars = budget,
                question = userMessage.content,
                continuationMode = continuationMode,
                continuationState = state,
            ) ?: return null
        }
        val basePlan = promptBudgetManager.buildPlan(
            messages = messages,
            currentUserMessageId = userMessage.id,
            context = context,
            continuationMode = continuationMode,
            retry = retry,
        )
        val promptPrefix = buildSkillPromptPrefix(skill, skillState, toolResultBlock)
        val plan = basePlan.copy(finalPrompt = promptBudgetManager.limitFinalPrompt(promptPrefix + "\n\n" + basePlan.finalPrompt))
        return AppPromptPlan(
            plan = plan,
            context = context,
            fileIds = fileIds,
            messages = messages,
            skill = skill,
            skillState = skillState,
        )
    }

    private fun updateContextState(chatId: String, context: FileContextBuildResult?) {
        context?.lastIncludedChunkIndexes?.forEach { (fileId, chunkIndex) ->
            chatRepository.updateContextState(chatId, fileId, chunkIndex)
        }
    }

    private fun requireAppUser(session: IHTTPSession): AuthUser? {
        return authRepository.requireUser(sessionTokenFromRequest(session))
    }

    private fun withAdmin(session: IHTTPSession, block: () -> Response): Response {
        val user = authRepository.requireUser(sessionTokenFromRequest(session))
            ?: return unauthorizedResponse()
        if (user.role != UserRole.ADMIN) {
            return errorResponse(Response.Status.FORBIDDEN, "forbidden", "Forbidden")
        }
        return block()
    }

    private fun unauthorizedResponse(): Response {
        return errorResponse(Response.Status.UNAUTHORIZED, "unauthorized", "Unauthorized")
    }

    private fun notFoundResponse(): Response {
        return errorResponse(Response.Status.NOT_FOUND, "not_found", "Not found")
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
            .put("thinking", message.thinking ?: JSONObject.NULL)
            .put("createdAtMs", message.createdAtMs)
    }

    private fun skillJson(skill: SkillRecord, includePrompt: Boolean): JSONObject {
        val json = JSONObject()
            .put("slug", skill.slug)
            .put("displayName", skill.displayName)
            .put("description", skill.description)
            .put("responseMode", skill.responseMode ?: JSONObject.NULL)
            .put("thinkingDefault", skill.thinkingDefault)
            .put("showThinkingDefault", skill.showThinkingDefault)
            .put("toolUseMode", skill.toolUseMode.name)
            .put("allowedTools", JSONArray(skill.allowedTools))
            .put("strictOutput", skill.strictOutput)
            .put("builtIn", skill.builtIn)
            .put("enabled", skill.enabled)
        if (includePrompt) json.put("systemPrompt", skill.systemPrompt).put("outputSchema", skill.outputSchemaJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL)
        return json
    }

    private fun chatSkillStateJson(state: ChatSkillStateRecord): JSONObject {
        return JSONObject()
            .put("chatId", state.chatId)
            .put("skillSlug", state.skillSlug)
            .put("thinkingEnabled", state.thinkingEnabled)
            .put("showThinking", state.showThinking)
            .put("updatedAtMs", state.updatedAtMs)
    }

    private fun toolJson(tool: ToolDefinition, admin: Boolean): JSONObject {
        val json = JSONObject()
            .put("name", tool.name)
            .put("displayName", tool.displayName)
            .put("description", tool.description)
            .put("enabled", tool.enabled)
        if (admin) json.put("dangerLevel", tool.dangerLevel).put("allowedForSkills", JSONArray(tool.allowedForSkills)).put("inputSchema", tool.inputSchema).put("outputSchema", tool.outputSchema)
        return json
    }

    private fun toolLogJson(log: ToolCallLogRecord): JSONObject {
        return JSONObject()
            .put("id", log.id)
            .put("chatId", log.chatId)
            .put("messageId", log.messageId ?: JSONObject.NULL)
            .put("toolName", log.toolName)
            .put("status", log.status.name)
            .put("errorMessage", log.errorMessage ?: JSONObject.NULL)
            .put("requestPreview", sanitizeJsonPreview(log.requestJson))
            .put("resultPreview", log.resultJson?.let { sanitizeJsonPreview(it) } ?: JSONObject.NULL)
            .put("createdAtMs", log.createdAtMs)
    }

    private fun generationJobJson(job: GenerationJobRecord): JSONObject {
        return JSONObject()
            .put("id", job.id)
            .put("chatId", job.chatId)
            .put("userId", job.userId)
            .put("userMessageId", job.userMessageId)
            .put("assistantMessageId", job.assistantMessageId ?: JSONObject.NULL)
            .put("status", job.status.name.lowercase())
            .put("createdAtMs", job.createdAtMs)
            .put("startedAtMs", job.startedAtMs ?: JSONObject.NULL)
            .put("completedAtMs", job.completedAtMs ?: JSONObject.NULL)
            .put("error", job.error ?: JSONObject.NULL)
            .put("partialOutput", job.partialOutput)
    }

    private fun sanitizeJsonPreview(value: String): String {
        return value
            .replace(Regex("(?i)(password|token|apiKey|huggingFaceToken|hfToken|storage_path|storagePath)\"\\s*:\\s*\"[^\"]*\""), "$1\":\"[redacted]\"")
            .take(600)
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

    private fun chatFilesJson(userId: String, chatId: String): JSONArray {
        val files = JSONArray()
        chatRepository.listAttachedFiles(userId, chatId).orEmpty().forEach { files.put(fileJson(it)) }
        return files
    }

    private fun adminUserJson(user: AdminUserOverview): JSONObject {
        return JSONObject()
            .put("id", user.id)
            .put("username", user.username)
            .put("role", user.role.name)
            .put("createdAtMs", user.createdAtMs)
            .put("chatCount", user.chatCount)
            .put("fileCount", user.fileCount)
    }

    private fun adminFileJson(file: AdminFileOverview): JSONObject {
        return JSONObject()
            .put("id", file.id)
            .put("username", file.username)
            .put("filename", file.filename)
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
        if (context == null) return contextJson(null as PromptContextMetadata?)
        return JSONObject()
            .put("fileIds", JSONArray(context.fileIds))
            .put("includedChunks", context.includedChunks)
            .put("includedChars", context.includedChars)
            .put("omittedChunks", context.omittedChunks)
            .put("truncated", context.truncated)
            .put("continuationMode", context.continuationMode)
            .put("message", context.message ?: JSONObject.NULL)
    }

    private fun contextJson(context: PromptContextMetadata?): JSONObject {
        if (context == null) {
            return JSONObject()
                .put("fileIds", JSONArray())
                .put("includedChunks", 0)
                .put("includedChars", 0)
                .put("omittedChunks", 0)
                .put("truncated", false)
                .put("continuationMode", false)
                .put("message", JSONObject.NULL)
        }
        return JSONObject()
            .put("fileIds", JSONArray(context.fileIds))
            .put("includedChunks", context.includedChunks)
            .put("includedChars", context.includedChars)
            .put("omittedChunks", context.omittedChunks)
            .put("truncated", context.truncated)
            .put("continuationMode", context.continuationMode)
            .put("message", context.friendlyMessage ?: JSONObject.NULL)
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

    private fun publicHost(session: IHTTPSession): String {
        return session.headers["host"] ?: session.headers["Host"] ?: "$bindHost:${listeningPort}"
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
        val id = currentRequestId()
        if (body.has("error") && !body.has("requestId")) {
            val message = body.optString("error", "Request failed")
            body.put("requestId", id)
            body.put(
                "errorDetails",
                JSONObject()
                    .put("code", defaultErrorCode(status))
                    .put("message", message)
                    .put("requestId", id)
            )
        }
        return newFixedLengthResponse(status, "application/json", body.toString()).apply {
            addCorsHeaders()
        }
    }

    private fun errorResponse(
        status: Response.Status,
        code: String,
        message: String,
        details: JSONObject? = null,
    ): Response {
        val id = currentRequestId()
        val error = JSONObject()
            .put("code", code)
            .put("message", message)
            .put("requestId", id)
        if (details != null) error.put("details", details)
        return jsonResponse(
            status,
            JSONObject()
                .put("error", message)
                .put("requestId", id)
                .put("errorDetails", error)
        )
    }

    private fun debugResponse(session: IHTTPSession, block: () -> Response): Response {
        if (securityMode == SecurityMode.TRUSTED_LAN && authRepository.requireAdmin(sessionTokenFromRequest(session)) == null) {
            return errorResponse(Response.Status.UNAUTHORIZED, "admin_required", "Admin session required in TRUSTED_LAN mode")
        }
        return block()
    }

    private fun redirectResponse(location: String): Response {
        return newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply {
            addHeader("Location", location)
            addCorsHeaders()
        }
    }

    private fun Response.addCorsHeaders() {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-API-Key")
        addHeader("Access-Control-Max-Age", "86400")
        addHeader("Access-Control-Allow-Private-Network", "true")
        addHeader("X-Request-Id", currentRequestId())
    }

    private fun currentRequestId(): String = requestId.get() ?: "req-unknown"

    private fun newRequestId(): String = "req-${UUID.randomUUID()}"

    private fun defaultErrorCode(status: Response.Status): String {
        return when (status) {
            Response.Status.BAD_REQUEST -> "bad_request"
            Response.Status.UNAUTHORIZED -> "unauthorized"
            Response.Status.FORBIDDEN -> "forbidden"
            Response.Status.NOT_FOUND -> "not_found"
            Response.Status.METHOD_NOT_ALLOWED -> "method_not_allowed"
            Response.Status.CONFLICT -> "conflict"
            Response.Status.PAYLOAD_TOO_LARGE -> "payload_too_large"
            Response.Status.SERVICE_UNAVAILABLE -> "service_unavailable"
            Response.Status.INTERNAL_ERROR -> "internal_error"
            else -> "request_failed"
        }
    }

    private companion object {
        const val STREAM_PIPE_BUFFER_BYTES = 64 * 1024
        const val MAX_PROMPT_MESSAGES = 24
        const val CHUNK_PREVIEW_CHARS = 500
    }
}
