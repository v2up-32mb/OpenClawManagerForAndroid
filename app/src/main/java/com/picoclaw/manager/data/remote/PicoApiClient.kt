package com.picoclaw.manager.data.remote

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * picoclaw REST API 客户端。
 *
 * 用于访问 picoclaw Dashboard 的 HTTP API：
 * - 模型管理: /api/models/*
 * - 状态监控: /api/gateway/status
 * - Skill 管理: /api/skills/*
 * - 配置管理: /api/config
 *
 * 参考: https://github.com/sipeed/picoclaw
 */
class PicoApiClient(private val baseUrl: String, private val token: String? = null) {

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("User-Agent", "PicoClawManager/1.0 (Android)")
                .addHeader("Accept", "application/json")
            token?.let {
                if (it.isNotBlank()) {
                    request.addHeader("Authorization", "Bearer $it")
                }
            }
            chain.proceed(request.build())
        }
        .build()

    /** 确保 baseUrl 不以 / 结尾。 */
    private val normalizedBase: String = baseUrl.trimEnd('/')

    // ==================== 通用 ====================

    /** 发起 GET 请求。 */
    private fun get(path: String): Result<String> = runCatching {
        val request = Request.Builder()
            .url("$normalizedBase$path")
            .get()
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string()
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: ${body?.take(200)}")
        }
        body ?: throw Exception("空响应")
    }

    /** 发起含 JSON body 的 POST 请求。 */
    private fun post(path: String, body: Any? = null): Result<String> = runCatching {
        val jsonBody = if (body != null) gson.toJson(body) else ""
        val request = Request.Builder()
            .url("$normalizedBase$path")
            .post(jsonBody.toRequestBody(jsonMediaType))
            .build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: ${responseBody?.take(200)}")
        }
        responseBody ?: throw Exception("空响应")
    }

    /** 发起含 JSON body 的 PUT 请求。 */
    private fun put(path: String, body: Any? = null): Result<String> = runCatching {
        val jsonBody = if (body != null) gson.toJson(body) else ""
        val request = Request.Builder()
            .url("$normalizedBase$path")
            .put(jsonBody.toRequestBody(jsonMediaType))
            .build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: ${responseBody?.take(200)}")
        }
        responseBody ?: throw Exception("空响应")
    }

    /** 发起 DELETE 请求。 */
    private fun delete(path: String): Result<String> = runCatching {
        val request = Request.Builder()
            .url("$normalizedBase$path")
            .delete()
            .build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: ${responseBody?.take(200)}")
        }
        responseBody ?: throw Exception("空响应")
    }

    /** 将 JSON 解析为 Map。 */
    private fun parseMap(json: String): Map<String, Any?>? {
        return try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(json, object : TypeToken<Map<String, Any?>>() {}.type) as Map<String, Any?>
        } catch (_: Exception) { null }
    }

    /** 将 JSON 解析为 Map 列表。 */
    private fun parseMapList(json: String): List<Map<String, Any?>>? {
        return try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(json, object : TypeToken<List<Map<String, Any?>>>() {}.type) as List<Map<String, Any?>>
        } catch (_: Exception) { null }
    }

    // ==================== 模型管理 ====================

    /**
     * 获取模型列表。
     * GET /api/models
     */
    suspend fun getModels(): Result<List<Map<String, Any?>>> {
        return get("/api/models").mapCatching { json ->
            parseMapList(json) ?: emptyList()
        }
    }

    /**
     * 添加模型。
     * POST /api/models
     */
    suspend fun addModel(config: Map<String, Any?>): Result<Unit> {
        return post("/api/models", config).map { }
    }

    /**
     * 更新模型。
     * PUT /api/models/{index}
     */
    suspend fun updateModel(index: Int, config: Map<String, Any?>): Result<Unit> {
        return put("/api/models/$index", config).map { }
    }

    /**
     * 删除模型。
     * DELETE /api/models/{index}
     */
    suspend fun deleteModel(index: Int): Result<Unit> {
        return delete("/api/models/$index").map { }
    }

    /**
     * 设置默认模型。
     * POST /api/models/default
     */
    suspend fun setDefaultModel(modelRef: String): Result<Unit> {
        return post("/api/models/default", mapOf("model_name" to modelRef)).map { }
    }

    /**
     * 测试模型连接。
     * POST /api/models/{index}/test
     */
    suspend fun testModel(index: Int): Result<String> {
        return post("/api/models/$index/test")
    }

    // ==================== 网关状态 ====================

    /**
     * 获取网关状态。
     * GET /api/gateway/status
     */
    suspend fun getGatewayStatus(): Result<Map<String, Any?>> {
        return get("/api/gateway/status").mapCatching { json ->
            parseMap(json) ?: emptyMap()
        }
    }

    /**
     * 获取网关日志。
     * GET /api/gateway/logs?log_offset={offset}&log_run_id={runId}
     */
    suspend fun getGatewayLogs(offset: Int = 0, runId: String? = null): Result<String> {
        val params = mutableListOf("log_offset=$offset")
        runId?.let { params.add("log_run_id=$it") }
        return get("/api/gateway/logs?${params.joinToString("&")}")
    }

    // ==================== 配置管理 ====================

    /**
     * 获取完整配置。
     * GET /api/config
     */
    suspend fun getConfig(): Result<Map<String, Any?>> {
        return get("/api/config").mapCatching { json ->
            parseMap(json) ?: emptyMap()
        }
    }

    // ==================== Skill 管理 ====================

    /**
     * 获取已安装的 Skill 列表。
     * GET /api/skills
     */
    suspend fun getSkills(): Result<List<Map<String, Any?>>> {
        return get("/api/skills").mapCatching { json ->
            parseMapList(json) ?: emptyList()
        }
    }

    /**
     * 获取 Skill 详情。
     * GET /api/skills/{name}
     */
    suspend fun getSkillDetail(name: String): Result<Map<String, Any?>> {
        return get("/api/skills/$name").mapCatching { json ->
            parseMap(json) ?: emptyMap()
        }
    }

    /**
     * 搜索 Registry 中的 Skill。
     * GET /api/skills/search?q={query}
     */
    suspend fun searchSkills(query: String): Result<List<Map<String, Any?>>> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        return get("/api/skills/search?q=$encoded").mapCatching { json ->
            parseMapList(json) ?: emptyList()
        }
    }

    /**
     * 安装 Skill。
     * POST /api/skills/install
     */
    suspend fun installSkill(slug: String, registry: String = "clawhub", version: String? = null, force: Boolean = false): Result<Unit> {
        val body = mutableMapOf<String, Any?>(
            "slug" to slug,
            "registry" to registry
        )
        version?.let { body["version"] = it }
        body["force"] = force
        return post("/api/skills/install", body).map { }
    }

    /**
     * 删除 Skill。
     * DELETE /api/skills/{name}
     */
    suspend fun uninstallSkill(name: String): Result<Unit> {
        return delete("/api/skills/$name").map { }
    }

    // ==================== 系统信息 ====================

    /**
     * 获取版本信息。
     * GET /api/system/version
     */
    suspend fun getVersion(): Result<Map<String, Any?>> {
        return get("/api/system/version").mapCatching { json ->
            parseMap(json) ?: emptyMap()
        }
    }
}
