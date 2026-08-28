package com.picoclaw.manager.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.picoclaw.manager.data.pico.ChatMessage
import com.picoclaw.manager.data.pico.PicoConnectionState
import com.picoclaw.manager.data.pico.PicoProfile
import com.picoclaw.manager.data.repository.PicoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val PREFS_PICO = "pico_connection"
private const val KEY_PROFILES_JSON = "pico_profiles_json"
private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
private const val KEY_PRIVACY_ACCEPTED = "privacy_accepted"
private const val DEFAULT_WS_URL = "ws://你的picoclaw地址:9090"
private const val DEFAULT_API_URL = "http://你的picoclaw地址:9090"

class MainViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val gson = Gson()

    // ==================== 多实例管理 ====================

    private val repositories = ConcurrentHashMap<String, PicoRepository>()

    /** M1 修复：共享 OkHttpClient，避免每个 Repository 实例各建一个连接池。 */
    private val sharedOkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private fun repoFor(profileId: String): PicoRepository {
        return repositories.getOrPut(profileId) { PicoRepository(sharedOkHttpClient) }
    }

    /**
     * H4 修复：从 map 中移除并关闭 Repository，释放协程和资源。
     */
    private fun closeRepo(profileId: String) {
        repositories.remove(profileId)?.close()
    }

    private val _activeRepo = MutableStateFlow<PicoRepository>(PicoRepository(sharedOkHttpClient))

    // ==================== 通过 flatMapLatest 映射的活动仓库状态 ====================

    val connectionState: StateFlow<PicoConnectionState> =
        _activeRepo.flatMapLatest { it.connectionState }
            .stateIn(viewModelScope, SharingStarted.Eagerly, PicoConnectionState.Disconnected)

    val gatewayStatus: StateFlow<Map<String, Any?>> =
        _activeRepo.flatMapLatest { it.gatewayStatus }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val systemVersion: StateFlow<Map<String, Any?>> =
        _activeRepo.flatMapLatest { it.systemVersion }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val models: StateFlow<List<Map<String, Any?>>> =
        _activeRepo.flatMapLatest { it.models }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val defaultModel: StateFlow<String?> =
        _activeRepo.flatMapLatest { it.defaultModel }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val configSetError: StateFlow<String?> =
        _activeRepo.flatMapLatest { it.configSetError }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val skills: StateFlow<List<Map<String, Any?>>> =
        _activeRepo.flatMapLatest { it.skills }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val errorMessage: StateFlow<String?> =
        _activeRepo.flatMapLatest { it.errorMessage }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val chatMessages: StateFlow<List<ChatMessage>> =
        _activeRepo.flatMapLatest { it.chatMessages }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val chatSendError: StateFlow<String?> =
        _activeRepo.flatMapLatest { it.chatSendError }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val agentRunInProgress: StateFlow<Boolean> =
        _activeRepo.flatMapLatest { it.agentRunInProgress }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** AI 正在输入指示（typing.start/typing.stop 事件）。 */
    val aiTyping: StateFlow<Boolean> =
        _activeRepo.flatMapLatest { it.aiTyping }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ==================== Toast 提示 ====================

    private val _saveSuccessToast = MutableStateFlow<String?>(null)
    val saveSuccessToast: StateFlow<String?> = _saveSuccessToast.asStateFlow()

    // ==================== 搜索状态 ====================

    private val _searchResults = MutableStateFlow<List<Map<String, Any?>>>(emptyList())
    val searchResults: StateFlow<List<Map<String, Any?>>> = _searchResults.asStateFlow()

    private val _searchLoading = MutableStateFlow(false)
    val searchLoading: StateFlow<Boolean> = _searchLoading.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    // ==================== Profile 管理 ====================

    private val prefs = getApplication<Application>().getSharedPreferences(PREFS_PICO, Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow(loadProfilesFromPrefs())
    val profiles: StateFlow<List<PicoProfile>> = _profiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow(loadActiveProfileIdFromPrefs(_profiles.value))
    val activeProfileId: StateFlow<String> = _activeProfileId.asStateFlow()

    private val _wsUrl = MutableStateFlow(currentProfileOrNull()?.url ?: DEFAULT_WS_URL)
    val wsUrl: StateFlow<String> = _wsUrl.asStateFlow()

    private val _apiUrl = MutableStateFlow(currentProfileOrNull()?.apiUrl ?: DEFAULT_API_URL)
    val apiUrl: StateFlow<String> = _apiUrl.asStateFlow()

    private val _authToken = MutableStateFlow(currentProfileOrNull()?.token ?: "")
    val authToken: StateFlow<String> = _authToken.asStateFlow()

    private val _privacyAccepted = MutableStateFlow(prefs.getBoolean(KEY_PRIVACY_ACCEPTED, false))
    val privacyAccepted: StateFlow<Boolean> = _privacyAccepted.asStateFlow()

    init {
        _activeRepo.value = repoFor(_activeProfileId.value)
    }

    private fun currentProfileOrNull(): PicoProfile? {
        return _profiles.value.firstOrNull { it.id == _activeProfileId.value }
            ?: _profiles.value.firstOrNull()
    }

    private fun saveProfilesPrefs() {
        val list = _profiles.value
        val json = gson.toJson(list)
        prefs.edit()
            .putString(KEY_PROFILES_JSON, json)
            .putString(KEY_ACTIVE_PROFILE_ID, _activeProfileId.value)
            .apply()
    }

    private fun loadProfilesFromPrefs(): List<PicoProfile> {
        val json = prefs.getString(KEY_PROFILES_JSON, null)
        if (!json.isNullOrBlank()) {
            return runCatching {
                val type = object : TypeToken<List<PicoProfile>>() {}.type
                gson.fromJson<List<PicoProfile>>(json, type)
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: defaultProfiles()
        }
        return defaultProfiles()
    }

    private fun defaultProfiles(): List<PicoProfile> {
        return listOf(
            PicoProfile(
                id = "pico-1",
                name = "picoclaw 1",
                url = DEFAULT_WS_URL,
                apiUrl = DEFAULT_API_URL,
                token = ""
            )
        )
    }

    private fun loadActiveProfileIdFromPrefs(profiles: List<PicoProfile>): String {
        val saved = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
        return if (saved != null && profiles.any { it.id == saved }) saved else profiles.first().id
    }

    private fun updateActiveProfile(transform: (PicoProfile) -> PicoProfile) {
        val activeId = _activeProfileId.value
        val updated = _profiles.value.map { p -> if (p.id == activeId) transform(p) else p }
        _profiles.value = updated
        currentProfileOrNull()?.let { p ->
            _wsUrl.value = p.url
            _apiUrl.value = p.apiUrl
            _authToken.value = p.token
        }
        saveProfilesPrefs()
    }

    fun setWsUrl(url: String) {
        _wsUrl.value = url
        // 同时推导 apiUrl
        val apiUrl = deriveApiUrl(url)
        _apiUrl.value = apiUrl
        updateActiveProfile { it.copy(url = url, apiUrl = apiUrl) }
    }

    fun setAuthToken(token: String) {
        _authToken.value = token
        updateActiveProfile { it.copy(token = token) }
    }

    fun setActiveProfile(profileId: String) {
        if (_profiles.value.none { it.id == profileId }) return
        _activeProfileId.value = profileId
        val p = currentProfileOrNull()!!
        _wsUrl.value = p.url
        _apiUrl.value = p.apiUrl
        _authToken.value = p.token
        _activeRepo.value = repoFor(profileId)
        saveProfilesPrefs()
    }

    fun addProfile(name: String, wsUrl: String, token: String) {
        val trimmedName = name.trim().ifBlank { "picoclaw ${_profiles.value.size + 1}" }
        val apiUrl = deriveApiUrl(wsUrl.trim())
        val id = "pico-${System.currentTimeMillis()}"
        val p = PicoProfile(
            id = id,
            name = trimmedName,
            url = wsUrl.trim().ifBlank { DEFAULT_WS_URL },
            apiUrl = apiUrl,
            token = token.trim()
        )
        _profiles.value = _profiles.value + p
        repoFor(id)
        setActiveProfile(id)
    }

    /** 快速添加配置。 */
    fun addProfileQuick() {
        val nextIndex = _profiles.value.size + 1
        val id = "pico-${System.currentTimeMillis()}"
        val p = PicoProfile(
            id = id,
            name = "picoclaw $nextIndex",
            url = DEFAULT_WS_URL,
            apiUrl = DEFAULT_API_URL,
            token = ""
        )
        _profiles.value = _profiles.value + p
        repoFor(id)
        setActiveProfile(id)
    }

    fun removeActiveProfile(): Boolean {
        val list = _profiles.value
        if (list.size <= 1) return false
        val activeId = _activeProfileId.value
        val idx = list.indexOfFirst { it.id == activeId }
        if (idx < 0) return false
        // H4 修复：使用 closeRepo 关闭并释放资源
        closeRepo(activeId)
        val newList = list.filterNot { it.id == activeId }
        _profiles.value = newList
        val newIdx = (idx - 1).coerceAtLeast(0).coerceAtMost(newList.lastIndex)
        val newActive = newList[newIdx]
        setActiveProfile(newActive.id)
        saveProfilesPrefs()
        return true
    }

    fun acceptPrivacy() {
        _privacyAccepted.value = true
        prefs.edit().putBoolean(KEY_PRIVACY_ACCEPTED, true).apply()
    }

    // ==================== 连接操作 ====================

    fun connect() {
        val repo = _activeRepo.value
        repo.clearError()
        repo.connect(
            wsUrl = _wsUrl.value,
            apiUrl = _apiUrl.value,
            token = _authToken.value.takeIf { it.isNotBlank() },
            sessionId = null  // 自动生成
        )
    }

    fun disconnect() = _activeRepo.value.disconnect()

    fun refreshAll() {
        viewModelScope.launch { _activeRepo.value.refreshAll() }
    }

    fun refreshGatewayStatus() {
        viewModelScope.launch { _activeRepo.value.refreshGatewayStatus() }
    }

    fun refreshModels() {
        viewModelScope.launch { _activeRepo.value.refreshModels() }
    }

    fun refreshSkills() {
        viewModelScope.launch { _activeRepo.value.refreshSkills() }
    }

    // ==================== 模型操作 ====================

    fun setDefaultModel(modelRef: String) {
        viewModelScope.launch {
            _activeRepo.value.setDefaultModel(modelRef).onSuccess {
                _saveSuccessToast.value = "模型已保存"
            }
        }
    }

    fun clearConfigSetError() = _activeRepo.value.clearConfigSetError()
    fun clearSaveSuccessToast() { _saveSuccessToast.value = null }

    // ==================== Skill 操作 ====================

    /** 搜索 Skill。 */
    fun searchSkills(query: String) {
        viewModelScope.launch {
            _searchLoading.value = true
            _searchError.value = null
            _activeRepo.value.searchSkills(query)
                .onSuccess { _searchResults.value = it }
                .onFailure { e ->
                    _searchError.value = e.message ?: "搜索失败"
                }
            _searchLoading.value = false
        }
    }

    /** 安装 Skill。 */
    fun installSkill(slug: String, registry: String = "clawhub") {
        viewModelScope.launch {
            _activeRepo.value.installSkill(slug, registry).onSuccess {
                _saveSuccessToast.value = "安装成功"
                refreshSkills()
            }.onFailure { e ->
                _searchError.value = e.message ?: "安装失败"
            }
        }
    }

    /** 卸载 Skill。 */
    fun uninstallSkill(name: String) {
        viewModelScope.launch {
            _activeRepo.value.uninstallSkill(name).onSuccess {
                _saveSuccessToast.value = "已删除"
                refreshSkills()
            }.onFailure { e ->
                _searchError.value = e.message ?: "删除失败"
            }
        }
    }

    // ==================== 聊天操作 ====================

    fun cacheAndClearChatMessages() = _activeRepo.value.cacheAndClearChatMessages()
    fun restoreChatMessages() = _activeRepo.value.restoreChatMessages()

    fun sendChatMessage(body: String) {
        viewModelScope.launch { _activeRepo.value.sendChatMessage(body) }
    }

    // ==================== 错误清理 ====================

    fun clearError() = _activeRepo.value.clearError()
    fun clearChatSendError() = _activeRepo.value.clearChatSendError()

    override fun onCleared() {
        // H1/H4 修复：关闭所有 Repository，释放协程和资源
        repositories.keys.toList().forEach { closeRepo(it) }
        repositories.clear()
    }

    companion object {
        /**
         * 从 WebSocket URL 推导 HTTP API URL。
         * ws://host:port → http://host:port
         * wss://host:port → https://host:port
         */
        private fun deriveApiUrl(wsUrl: String): String {
            return when {
                wsUrl.startsWith("ws://", ignoreCase = true) -> "http://" + wsUrl.removePrefix("ws://")
                wsUrl.startsWith("wss://", ignoreCase = true) -> "https://" + wsUrl.removePrefix("wss://")
                else -> wsUrl
            }
        }
    }
}
