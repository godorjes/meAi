package app.polyglot.dev.feature.config

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.polyglot.dev.PolyglotApp
import app.polyglot.dev.data.local.ProviderEntity
import app.polyglot.dev.data.net.ApiException
import app.polyglot.dev.data.net.ErrorKind
import app.polyglot.dev.data.repo.CatalogRepository
import app.polyglot.dev.data.repo.ConfigRepository
import app.polyglot.dev.data.repo.ModelOrigin
import app.polyglot.dev.data.repo.ProviderPresets
import app.polyglot.dev.data.repo.SaveProviderResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class ConfigListViewModel(app: Application) : AndroidViewModel(app) {
    private val repo: ConfigRepository = (app as PolyglotApp).container.configRepository

    val providers: StateFlow<List<ProviderEntity>> = repo.providers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun clearMessage() { _message.value = null }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            if (!repo.canDeleteProvider(id)) {
                _message.value = "该服务正在生成回复，请先停止回复"
                return@launch
            }
            repo.deleteProvider(id)
        }
    }

    fun modelCount(entity: ProviderEntity): Int = repo.modelsOf(entity).size
}

/** 需要校验的字段，顺序即表单从上到下的顺序 */
enum class ConfigField { PROVIDER_TYPE, API_KEY, MODEL, MODEL_NAME, BASE_URL }

/** 一份校验结果：字段 → 该字段下方的提示 */
data class FieldErrors(val map: Map<ConfigField, String> = emptyMap()) {
    val isValid: Boolean get() = map.isEmpty()
    operator fun get(field: ConfigField): String? = map[field]
    val first: ConfigField? get() = ORDER.firstOrNull { map.containsKey(it) }

    companion object {
        val ORDER = listOf(
            ConfigField.PROVIDER_TYPE, ConfigField.API_KEY, ConfigField.MODEL,
            ConfigField.MODEL_NAME, ConfigField.BASE_URL
        )
    }
}

/** 请求页面把焦点移到某个字段；token 保证同一个字段也能重复触发 */
data class FocusRequest(val field: ConfigField, val token: Long)

/**
 * 极简配置流程：选择厂家 → 粘贴 API Key → 选择模型 → 测试连接／保存。
 * 普通流程只要求用户输入 Key；名称按厂家自动生成，其余放到"高级设置"里。
 */
data class ConfigForm(
    val id: String? = null,
    val providerType: String = "openai",
    val baseUrl: String = "",
    val apiKey: String = "",
    val selectedModelId: String = "",
    /**
     * 这个模型服务的显示名称，保存在 providers.name，只用于在本机区分配置，不发给模型。
     * 默认跟随所选模型（见 [ConfigEditViewModel.updateSelectedModel]）。
     */
    val modelName: String = "",
    /** 用户是否手动改过名称；改过之后就不再跟随所选模型 */
    val nameEdited: Boolean = false,
    /** 高级设置是否展开 */
    val advancedOpen: Boolean = false,
    /** 高级设置里选择手动填写模型 ID */
    val manualModelEntry: Boolean = false,
    val savedTestState: String = "none",
    val savedTestedAt: Long? = null,
    /**
     * 表单参数版本。厂家、地址、Key、模型任一改动都会 +1，
     * 用来判断一次测试结果是否还对应当前参数，屏蔽旧请求的晚到结果。
     */
    val revision: Long = 0
) {
    val isNew: Boolean get() = id == null
}

/** 模型选择器中的一项：来源状态决定怎么标注证据 */
data class ModelChoice(
    val id: String,
    val desc: String,
    val origin: ModelOrigin
)

sealed interface TestUiState {
    data object Idle : TestUiState
    data object Testing : TestUiState
    data object Success : TestUiState
    data class Fail(val kind: ErrorKind?) : TestUiState
}

/** 当前厂家有没有每日缓存可读：列表来自本地，不再每次在线请求 */
enum class CatalogUiState { IDLE, FRESH }

class ConfigEditViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app as PolyglotApp
    private val repo: ConfigRepository = container.container.configRepository
    private val catalogRepo: CatalogRepository = container.container.catalogRepository

    private val _form = MutableStateFlow(ConfigForm())
    val form: StateFlow<ConfigForm> = _form

    private val _testing = MutableStateFlow(false)
    private val _result = MutableStateFlow<Pair<Long, TestUiState>?>(null)

    /** 界面看到的唯一测试状态：按钮与右侧文字都由它派生 */
    val test: StateFlow<TestUiState> =
        combine(_testing, _result, _form) { testing, result, form ->
            when {
                testing -> TestUiState.Testing
                result != null && result.first == form.revision -> result.second
                form.savedTestState == "success" -> TestUiState.Success
                form.savedTestState == "fail" -> TestUiState.Fail(null)
                else -> TestUiState.Idle
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TestUiState.Idle)

    /**
     * 模型选择列表：**只读本地**。
     * 每日定时任务拉取的缓存在前（服务返回），配置里保存但不在缓存里的保留并标注，
     * 没有缓存时才用公共参考目录兜底（例如千问暂用预置目录）。
     */
    val modelChoices: StateFlow<List<ModelChoice>> =
        combine(_form, catalogRepo.observeAll(), catalogRepo.observeAppModelLists()) { f, catalog, cached ->
            val forProvider = catalog.filter { it.providerType == f.providerType }
            val descById = forProvider.associate { it.modelId to it.desc }
            val cachedRows = cached.filter { it.providerType == f.providerType }
            val choices = LinkedHashMap<String, ModelChoice>()

            // 1) 每日缓存：定时任务拉取的最新前五
            cachedRows.forEach { row ->
                choices[row.modelId] = ModelChoice(
                    row.modelId, descById[row.modelId].orEmpty(), ModelOrigin.SERVICE_RETURNED
                )
            }
            // 2) 配置里保存着、但不在缓存里的：保留原值并标注
            savedModelIds(f).forEach { id ->
                if (id !in choices) {
                    choices[id] = ModelChoice(id, descById[id].orEmpty(), ModelOrigin.NOT_RETURNED_THIS_TIME)
                }
            }
            // 3) 没有缓存时才用公共参考目录兜底，不和缓存混在一起
            if (cachedRows.isEmpty()) {
                forProvider.forEach { row ->
                    if (row.modelId !in choices) {
                        choices[row.modelId] = ModelChoice(row.modelId, row.desc, ModelOrigin.PUBLIC_REFERENCE)
                    }
                }
            }
            choices.values.toList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 当前厂家有没有每日缓存 */
    val catalogState: StateFlow<CatalogUiState> =
        combine(_form, catalogRepo.observeAppModelLists()) { f, cached ->
            if (cached.any { it.providerType == f.providerType }) CatalogUiState.FRESH else CatalogUiState.IDLE
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CatalogUiState.IDLE)

    /** 当前厂家缓存最近一次拉取时间，用于界面提示 */
    val listUpdatedAt: StateFlow<Long?> =
        combine(_form, catalogRepo.observeAppModelLists()) { f, cached ->
            cached.filter { it.providerType == f.providerType }.maxOfOrNull { it.fetchedAt }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _errors = MutableStateFlow(FieldErrors())
    val errors: StateFlow<FieldErrors> = _errors

    private val _focusRequest = MutableStateFlow<FocusRequest?>(null)
    val focusRequest: StateFlow<FocusRequest?> = _focusRequest
    private var focusToken = 0L

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved

    private var loaded = false
    private var loadedModelIds: List<String> = emptyList()

    private fun savedModelIds(f: ConfigForm): List<String> =
        (loadedModelIds + f.selectedModelId).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun clearMessage() { _message.value = null }

    fun load(configId: String?) {
        if (loaded) return
        loaded = true
        if (configId == null) {
            // 新配置：厂家默认值，地址跟随预设
            _form.value = ConfigForm(
                providerType = ProviderPresets.selectable.first().type,
                baseUrl = ProviderPresets.selectable.first().defaultBaseUrl
            )
            return
        }
        viewModelScope.launch {
            val entity = repo.providerById(configId) ?: return@launch
            loadedModelIds = repo.decodeModels(entity.modelIdsJson)
            _form.value = ConfigForm(
                id = entity.id,
                providerType = entity.providerType,
                baseUrl = entity.baseUrl,
                apiKey = repo.readApiKey(entity.id).orEmpty(),
                selectedModelId = entity.selectedModelId,
                // 已保存配置的名称是既成事实，不再跟随模型自动改写
                modelName = entity.name,
                nameEdited = true,
                savedTestState = entity.testState,
                savedTestedAt = entity.testedAt
            )
        }
    }

    // ---------- 表单编辑 ----------

    private fun mutate(invalidatesQuery: Boolean, block: (ConfigForm) -> ConfigForm) {
        val current = _form.value
        val next = block(current)
        _form.value = if (invalidatesQuery) next.copy(revision = current.revision + 1) else next
    }

    private fun clearError(field: ConfigField) {
        if (_errors.value.map.containsKey(field)) _errors.value = FieldErrors(_errors.value.map - field)
    }

    private fun showErrors(problems: Map<ConfigField, String>) {
        _errors.value = FieldErrors(problems)
        val first = FieldErrors(problems).first ?: return
        focusToken += 1
        _focusRequest.value = FocusRequest(first, focusToken)
    }

    /**
     * 切换厂家：清空当前表单的模型选择（模型列表只读本地缓存，切换不触发任何请求）。
     * 不同厂家的旧 Key 不自动带过去；已保存的原配置不受影响。
     */
    fun updateProviderType(type: String) {
        clearError(ConfigField.PROVIDER_TYPE)
        val preset = ProviderPresets.byType(type)
        mutate(invalidatesQuery = true) { current ->
            current.copy(
                providerType = type,
                baseUrl = preset.defaultBaseUrl,
                selectedModelId = "",
                // 换厂家会清掉模型，跟着模型自动填的名称也一并清掉，避免留下上一个厂家模型的名称
                modelName = if (current.nameEdited) current.modelName else "",
                manualModelEntry = type == "custom"
            )
        }
    }

    fun updateApiKey(v: String) {
        clearError(ConfigField.API_KEY)
        mutate(invalidatesQuery = true) { it.copy(apiKey = v) }
    }

    fun updateBaseUrl(v: String) {
        clearError(ConfigField.BASE_URL)
        mutate(invalidatesQuery = true) { it.copy(baseUrl = v) }
    }

    fun updateModelName(v: String) = mutate(invalidatesQuery = false) {
        it.copy(modelName = v, nameEdited = true)
    }

    /**
     * 选择模型。用户没手动改过名称时，名称跟随所选模型 ——
     * 新配置的名称默认就是当前选中的模型，不必再让用户填一遍。
     */
    fun updateSelectedModel(v: String) {
        clearError(ConfigField.MODEL)
        val model = v.trim()
        mutate(invalidatesQuery = true) { current ->
            current.copy(
                selectedModelId = model,
                modelName = if (current.nameEdited) current.modelName else model
            )
        }
    }

    fun toggleAdvanced() = mutate(invalidatesQuery = false) { it.copy(advancedOpen = !it.advancedOpen) }

    fun setManualModelEntry(manual: Boolean) =
        mutate(invalidatesQuery = false) { it.copy(manualModelEntry = manual) }

    // ---------- 测试连接 ----------

    /**
     * 只测试当前选定的模型；整个按钮与右侧文字由同一个状态流派生。
     * 校验不过就不发起请求，并在对应字段下给出提示。
     */
    fun testConnection() {
        val f = _form.value
        val problems = validateForRequest(f)
        if (problems.isNotEmpty()) { showErrors(problems); return }
        _errors.value = FieldErrors()
        if (_testing.value) return
        val revision = f.revision
        _testing.value = true
        viewModelScope.launch {
            val modelId = f.selectedModelId.trim()
            try {
                // 聊天测试用独立有限总时限，不能无限等待
                withTimeout(TEST_TIMEOUT_MS) {
                    repo.testConnectionRaw(f.baseUrl, f.apiKey, modelId)
                }
                _result.value = revision to TestUiState.Success
                f.id?.let { repo.recordVerification(it, modelId, success = true, kind = null) }
            } catch (e: TimeoutCancellationException) {
                _result.value = revision to TestUiState.Fail(ErrorKind.TIMEOUT)
                f.id?.let { repo.recordVerification(it, modelId, false, ErrorKind.TIMEOUT) }
            } catch (e: ApiException) {
                _result.value = revision to TestUiState.Fail(e.kind)
                f.id?.let { repo.recordVerification(it, modelId, false, e.kind) }
            } catch (e: Exception) {
                _result.value = revision to TestUiState.Fail(null)
                f.id?.let { repo.recordVerification(it, modelId, false, null) }
            } finally {
                _testing.value = false
            }
        }
    }

    // ---------- 保存 ----------

    fun save() {
        val f = _form.value
        val problems = validateForSave(f)
        if (problems.isNotEmpty()) { showErrors(problems); return }
        _errors.value = FieldErrors()
        viewModelScope.launch {
            val selected = f.selectedModelId.trim()
            try {
                when (val result = repo.saveProvider(
                    existingId = f.id,
                    name = f.modelName,
                    providerType = f.providerType,
                    baseUrl = f.baseUrl,
                    apiKey = f.apiKey,
                    modelIds = loadedModelIds,
                    selectedModelId = selected
                )) {
                    is SaveProviderResult.Saved -> {
                        _form.value = _form.value.copy(
                            id = result.entity.id,
                            // 保存后名称以落库结果为准，之后的改动不再自动跟随模型
                            modelName = result.entity.name,
                            nameEdited = true,
                            savedTestState = result.entity.testState,
                            savedTestedAt = result.entity.testedAt
                        )
                        _saved.value = true
                    }
                    SaveProviderResult.Busy ->
                        _message.value = "该服务正在生成回复，请先停止回复再修改"
                    SaveProviderResult.InvalidName ->
                        showErrors(mapOf(ConfigField.PROVIDER_TYPE to "请选择服务商"))
                }
            } catch (e: Exception) {
                // 加密或写库失败：不改原配置，明确报错
                _message.value = "保存失败，配置未修改。请检查存储空间后重试"
            }
        }
    }

    // ---------- 校验 ----------

    /** 保存前校验：厂家有效、Key 非空、模型已选或有效、名称非空、自定义地址合法 */
    private fun validateForSave(f: ConfigForm): Map<ConfigField, String> = buildMap {
        putAll(validateCommon(f))
    }

    /** 测试只需要能真正发出请求的字段 */
    private fun validateForRequest(f: ConfigForm): Map<ConfigField, String> = buildMap {
        putAll(validateCommon(f))
    }

    private fun validateCommon(f: ConfigForm): Map<ConfigField, String> = buildMap {
        if (ProviderPresets.all.none { it.type == f.providerType }) {
            put(ConfigField.PROVIDER_TYPE, "请选择服务商")
        }
        if (f.apiKey.isBlank()) put(ConfigField.API_KEY, "请粘贴 API Key")
        if (f.selectedModelId.isBlank()) put(ConfigField.MODEL, "请选择模型")
        if (f.modelName.isBlank()) put(ConfigField.MODEL_NAME, "请填写模型名称")
        // 预设地址由 App 提供；自定义模式下仍需校验 HTTPS 与合法主机名
        if (f.providerType == "custom" || f.baseUrl.isBlank()) {
            putAll(validateBaseUrl(f.baseUrl))
        }
    }

    private fun validateBaseUrl(raw: String): Map<ConfigField, String> {
        val url = raw.trim()
        return when {
            url.isEmpty() -> mapOf(ConfigField.BASE_URL to "请填写 API 地址")
            !url.startsWith("https://") -> mapOf(ConfigField.BASE_URL to "首版仅支持 HTTPS 地址")
            runCatching { java.net.URI(url).host }.getOrNull().isNullOrBlank() ->
                mapOf(ConfigField.BASE_URL to "API 地址格式不正确")
            else -> emptyMap()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 30_000L
    }
}
