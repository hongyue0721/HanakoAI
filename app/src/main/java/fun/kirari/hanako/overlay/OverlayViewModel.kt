package `fun`.kirari.hanako.overlay

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `fun`.kirari.hanako.automation.AutomationResult
import `fun`.kirari.hanako.automation.BubbleDisplayState
import `fun`.kirari.hanako.capture.ScreenCaptureManager
import `fun`.kirari.hanako.data.AutomationActionType
import `fun`.kirari.hanako.data.LOCAL_OCR_PROVIDER_ID
import `fun`.kirari.hanako.data.ModelPurpose
import `fun`.kirari.hanako.data.ProcessingEvent
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.ProcessingRoute
import `fun`.kirari.hanako.data.ProcessingStatus
import `fun`.kirari.hanako.data.ModelSelection
import `fun`.kirari.hanako.data.resolveModelName
import `fun`.kirari.hanako.data.resolveModelProvider
import `fun`.kirari.hanako.data.SettingsStore
import `fun`.kirari.hanako.data.toHistoryBase64
import `fun`.kirari.hanako.debug.AppDebugLogStore
import `fun`.kirari.hanako.localocr.LocalOcrManager
import `fun`.kirari.hanako.network.AiGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class OverlayViewModel(
    private val appContext: Context,
    private val store: SettingsStore,
    private val gateway: AiGateway
) : ViewModel() {
    private val tag = "HanakoOverlayVM"
    private val processingTimeoutMillis = 90_000L
    private val localOcrManager = LocalOcrManager(appContext)
    private val _uiState = MutableStateFlow(OverlayUiState())
    val uiState: StateFlow<OverlayUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            store.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
    }

    fun setLaunchMode(mode: OverlayLaunchMode) {
        AppDebugLogStore.i(tag, "setLaunchMode mode=$mode")
        _uiState.update { state ->
            state.copy(
                launchMode = mode,
                autoRunState = if (mode == OverlayLaunchMode.NORMAL) AutoRunState.IDLE else state.autoRunState,
                autoCopiedLabel = if (mode == OverlayLaunchMode.NORMAL) null else state.autoCopiedLabel,
                bubbleDisplayState = if (mode == OverlayLaunchMode.NORMAL) BubbleDisplayState.IDLE else state.bubbleDisplayState,
                bubbleLetters = if (mode == OverlayLaunchMode.NORMAL) null else state.bubbleLetters,
                error = null
            )
        }
    }

    fun openCropSheet() {
        AppDebugLogStore.i(tag, "openCropSheet launchMode=${_uiState.value.launchMode}")
        if (_uiState.value.launchMode == OverlayLaunchMode.AUTO) {
            AppDebugLogStore.i(tag, "openCropSheet delegated to processFullScreen for auto mode")
            processFullScreen()
            return
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    ScreenCaptureManager.captureLatestBitmap(appContext, _uiState.value.settings.screenCaptureMethod)
                }
            }.onSuccess { bitmap ->
                AppDebugLogStore.i(tag, "openCropSheet capture success width=${bitmap.width} height=${bitmap.height}")
                _uiState.update {
                    it.copy(
                        screenshot = bitmap,
                        selectedBitmap = null,
                        liveOcrText = "",
                        liveAnswerText = "",
                        result = null,
                        error = null,
                        working = false,
                        sheetVisible = true,
                        sheetMode = OverlaySheetMode.CROP,
                        autoRunState = AutoRunState.IDLE,
                        autoCopiedLabel = null,
                        bubbleDisplayState = BubbleDisplayState.IDLE,
                        bubbleLetters = null
                    )
                }
            }.onFailure { error ->
                AppDebugLogStore.e(tag, "openCropSheet failed", error)
                _uiState.update {
                    it.copy(
                        error = error.message ?: "截屏失败",
                        sheetVisible = true,
                        sheetMode = OverlaySheetMode.CROP
                    )
                }
            }
        }
    }

    fun processFullScreen() {
        AppDebugLogStore.i(tag, "processFullScreen start launchMode=${_uiState.value.launchMode}")
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    liveOcrText = "",
                    liveAnswerText = "",
                    result = null,
                    error = null,
                    working = true,
                    sheetVisible = false,
                    autoRunState = AutoRunState.RUNNING,
                    autoCopiedLabel = null,
                    bubbleDisplayState = BubbleDisplayState.RUNNING,
                    bubbleLetters = null
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    ScreenCaptureManager.captureLatestBitmap(appContext, _uiState.value.settings.screenCaptureMethod)
                }
            }.onSuccess { bitmap ->
                AppDebugLogStore.i(tag, "processFullScreen capture success width=${bitmap.width} height=${bitmap.height}")
                processAutoBitmap(bitmap)
            }.onFailure { error ->
                AppDebugLogStore.e(tag, "processFullScreen failed", error)
                _uiState.update {
                    it.copy(
                        working = false,
                        autoRunState = AutoRunState.IDLE,
                        error = error.message ?: "截屏失败"
                    )
                }
            }
        }
    }

    fun process(bitmap: Bitmap) {
        val state = _uiState.value
        AppDebugLogStore.i(
            tag,
            "process start route=${state.settings.processingRoute} bitmap=${bitmap.width}x${bitmap.height}"
        )
        val assistant = state.settings.assistants.firstOrNull { it.id == state.settings.selectedAssistantId } ?: return
        val ocrProvider = state.settings.resolveModelProvider(ModelPurpose.OCR)
        val ocrModel = state.settings.resolveModelName(ModelPurpose.OCR)
        val textProvider = state.settings.resolveModelProvider(ModelPurpose.TEXT)
        val textModel = state.settings.resolveModelName(ModelPurpose.TEXT)
        val visionProvider = state.settings.resolveModelProvider(ModelPurpose.VISION)
        val visionModel = state.settings.resolveModelName(ModelPurpose.VISION)
        val firstDeltaTimeoutMillis = state.settings.automation.autoModeTimeoutSeconds.coerceAtLeast(1) * 1000L
        val historyId = java.util.UUID.randomUUID().toString()
        val screenshotBase64 = bitmap.toHistoryBase64()
        val baseResult = ProcessingResult(
            id = historyId,
            assistantName = assistant.name,
            route = state.settings.processingRoute,
            status = ProcessingStatus.RUNNING,
            modelSummary = when (state.settings.processingRoute) {
                ProcessingRoute.OCR_THEN_LLM -> buildModelSummary(textModel, textProvider?.name)
                ProcessingRoute.MULTIMODAL_DIRECT -> buildModelSummary(visionModel, visionProvider?.name)
            },
            detail = "请求已开始",
            screenshotBase64 = screenshotBase64,
            events = listOf(ProcessingEvent(title = "请求开始", detail = "已创建处理记录"))
        )

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedBitmap = bitmap,
                    liveOcrText = "",
                    liveAnswerText = "",
                    result = null,
                    error = null,
                    working = true,
                    sheetVisible = true,
                    sheetMode = OverlaySheetMode.RESULT
                )
            }
            upsertHistory(baseResult)
            runCatching {
                withTimeout(processingTimeoutMillis) {
                    when (state.settings.processingRoute) {
                        ProcessingRoute.OCR_THEN_LLM -> {
                            AppDebugLogStore.i(tag, "process route=OCR_THEN_LLM ocrModel=$ocrModel textModel=$textModel")
                            val usingLocalOcr = state.settings.ocrModelSelection.providerId == LOCAL_OCR_PROVIDER_ID
                            if ((!usingLocalOcr && (ocrProvider == null || ocrModel.isBlank())) || textProvider == null || textModel.isBlank()) {
                                error("请先在模型设置中配置 OCR 和文本模型")
                            }
                            if (usingLocalOcr) {
                                val (ocrText, answer) = runLocalOcrThenChat(
                                    bitmap = bitmap,
                                    assistant = assistant,
                                    textProvider = textProvider,
                                    textModel = textModel,
                                    firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
                                    onOcrDelta = { delta ->
                                        _uiState.update { current ->
                                            current.copy(liveOcrText = current.liveOcrText + delta)
                                        }
                                    },
                                    onAnswerDelta = { delta ->
                                        _uiState.update { current ->
                                            current.copy(liveAnswerText = current.liveAnswerText + delta)
                                        }
                                    }
                                )
                                val ocrCompleted = baseResult.copy(
                                    detail = "OCR 完成，等待答案生成",
                                    extractedText = ocrText,
                                    events = baseResult.events + ProcessingEvent(
                                        title = "OCR 完成",
                                        detail = "已提取 ${ocrText.length} 个字符"
                                    )
                                )
                                upsertHistory(ocrCompleted)
                                return@withTimeout ProcessingResult(
                                    id = historyId,
                                    assistantName = assistant.name,
                                    route = ProcessingRoute.OCR_THEN_LLM,
                                    status = ProcessingStatus.SUCCESS,
                                    modelSummary = buildModelSummary(textModel, textProvider?.name),
                                    detail = "处理完成",
                                    extractedText = ocrText,
                                    answer = answer,
                                    screenshotBase64 = screenshotBase64,
                                    events = ocrCompleted.events + ProcessingEvent(
                                        title = "答案完成",
                                        detail = "已生成 ${answer.length} 个字符"
                                    ),
                                    createdAtMillis = baseResult.createdAtMillis
                                )
                            }
                            val remoteOcrProvider = requireNotNull(ocrProvider)
                            val (ocrText, answer) = gateway.streamOcrThenChat(
                                ocrProvider = remoteOcrProvider,
                                ocrModel = ocrModel,
                                textProvider = textProvider,
                                textModel = textModel,
                                assistant = assistant,
                                bitmap = bitmap,
                                firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
                                onOcrDelta = { delta ->
                                    _uiState.update { current ->
                                        current.copy(liveOcrText = current.liveOcrText + delta)
                                    }
                                },
                                onAnswerDelta = { delta ->
                                    _uiState.update { current ->
                                        current.copy(liveAnswerText = current.liveAnswerText + delta)
                                    }
                                }
                            )
                            val ocrCompleted = baseResult.copy(
                                detail = "OCR 完成，等待答案生成",
                                extractedText = ocrText,
                                events = baseResult.events + ProcessingEvent(
                                    title = "OCR 完成",
                                    detail = "已提取 ${ocrText.length} 个字符"
                                )
                            )
                            upsertHistory(ocrCompleted)
                            ProcessingResult(
                                id = historyId,
                                assistantName = assistant.name,
                                route = ProcessingRoute.OCR_THEN_LLM,
                                status = ProcessingStatus.SUCCESS,
                                modelSummary = buildModelSummary(textModel, textProvider?.name),
                                detail = "处理完成",
                                extractedText = ocrText,
                                answer = answer,
                                screenshotBase64 = screenshotBase64,
                                events = ocrCompleted.events + ProcessingEvent(
                                    title = "答案完成",
                                    detail = "已生成 ${answer.length} 个字符"
                                ),
                                createdAtMillis = baseResult.createdAtMillis
                            )
                        }

                        ProcessingRoute.MULTIMODAL_DIRECT -> {
                            AppDebugLogStore.i(tag, "process route=MULTIMODAL_DIRECT visionModel=$visionModel")
                            if (visionProvider == null || visionModel.isBlank()) {
                                error("请先在模型设置中配置多模态模型")
                            }
                            val answer = gateway.streamVisionDirect(
                                provider = visionProvider,
                                model = visionModel,
                                assistant = assistant,
                                bitmap = bitmap,
                                firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
                                onAnswerDelta = { delta ->
                                    _uiState.update { current ->
                                        current.copy(liveAnswerText = current.liveAnswerText + delta)
                                    }
                                }
                            )
                            ProcessingResult(
                                id = historyId,
                                assistantName = assistant.name,
                                route = ProcessingRoute.MULTIMODAL_DIRECT,
                                status = ProcessingStatus.SUCCESS,
                                modelSummary = buildModelSummary(visionModel, visionProvider?.name),
                                detail = "处理完成",
                                answer = answer,
                                screenshotBase64 = screenshotBase64,
                                events = baseResult.events + ProcessingEvent(
                                    title = "答案完成",
                                    detail = "已生成 ${answer.length} 个字符"
                                ),
                                createdAtMillis = baseResult.createdAtMillis
                            )
                        }
                    }
                }
            }.onSuccess { result ->
                AppDebugLogStore.i(
                    tag,
                    "process success resultId=${result.id} answerLength=${result.answer.length} extractedLength=${result.extractedText.length}"
                )
                upsertHistory(result)
                _uiState.update {
                    it.copy(
                        working = false,
                        result = result,
                        liveOcrText = result.extractedText,
                        liveAnswerText = result.answer,
                        autoRunState = AutoRunState.IDLE,
                        autoCopiedLabel = null,
                        bubbleDisplayState = BubbleDisplayState.IDLE,
                        bubbleLetters = null
                    )
                }
            }.onFailure { error ->
                AppDebugLogStore.e(tag, "process failed", error)
                val isTimeout = error is TimeoutCancellationException
                val message = error.message?.ifBlank { null } ?: if (isTimeout) "请求超时（90 秒）" else "处理失败"
                upsertHistory(
                    baseResult.copy(
                        status = if (isTimeout) ProcessingStatus.TIMEOUT else ProcessingStatus.ERROR,
                        detail = message,
                        extractedText = _uiState.value.liveOcrText,
                        answer = _uiState.value.liveAnswerText,
                        events = baseResult.events + ProcessingEvent(
                            title = if (isTimeout) "请求超时" else "请求失败",
                            detail = message
                        )
                    )
                )
                _uiState.update {
                    it.copy(
                        working = false,
                        autoRunState = AutoRunState.IDLE,
                        error = message
                    )
                }
            }
        }
    }

    fun closeSheet() {
        _uiState.update { it.copy(sheetVisible = false, error = null) }
    }

    fun consumeAutoCompletedState() {
        AppDebugLogStore.d(
            tag,
            "consumeAutoCompletedState state=${_uiState.value.autoRunState} bubble=${_uiState.value.bubbleDisplayState}"
        )
        _uiState.update { state ->
            if (state.launchMode == OverlayLaunchMode.AUTO && state.autoRunState == AutoRunState.COMPLETED) {
                if (state.bubbleDisplayState == BubbleDisplayState.COPIED) {
                    state.copy(autoRunState = AutoRunState.IDLE, bubbleDisplayState = BubbleDisplayState.IDLE)
                } else {
                    state.copy(autoRunState = AutoRunState.IDLE)
                }
            } else {
                state
            }
        }
    }

    fun onBubbleTappedAfterLettersShown() {
        AppDebugLogStore.i(
            tag,
            "onBubbleTappedAfterLettersShown launchMode=${_uiState.value.launchMode} bubble=${_uiState.value.bubbleDisplayState} letters=${_uiState.value.bubbleLetters}"
        )
        _uiState.update { state ->
            if (state.launchMode == OverlayLaunchMode.AUTO && state.bubbleDisplayState == BubbleDisplayState.SHOWING_LETTERS) {
                AppDebugLogStore.i(tag, "onBubbleTappedAfterLettersShown clearing letters and entering pending reset")
                state.copy(
                    bubbleDisplayState = BubbleDisplayState.SHOWING_LETTERS_PENDING_RESET,
                    bubbleLetters = null
                )
            } else {
                state
            }
        }
    }

    fun selectAssistant(assistantId: String) {
        viewModelScope.launch {
            store.update { current ->
                if (current.assistants.any { it.id == assistantId }) {
                    current.copy(selectedAssistantId = assistantId)
                } else {
                    current
                }
            }
        }
    }

    fun selectPreviousAssistant() {
        val current = _uiState.value.settings
        val assistants = current.assistants
        if (assistants.isEmpty()) return
        val selectedIndex = assistants.indexOfFirst { it.id == current.selectedAssistantId }.takeIf { it >= 0 } ?: 0
        val previousIndex = if (selectedIndex == 0) assistants.lastIndex else selectedIndex - 1
        selectAssistant(assistants[previousIndex].id)
    }

    fun selectNextAssistant() {
        val current = _uiState.value.settings
        val assistants = current.assistants
        if (assistants.isEmpty()) return
        val selectedIndex = assistants.indexOfFirst { it.id == current.selectedAssistantId }.takeIf { it >= 0 } ?: 0
        val nextIndex = if (selectedIndex == assistants.lastIndex) 0 else selectedIndex + 1
        selectAssistant(assistants[nextIndex].id)
    }

    fun updateModelSelection(purpose: ModelPurpose, selection: ModelSelection) {
        viewModelScope.launch {
            store.update { current ->
                when (purpose) {
                    ModelPurpose.TEXT -> current.copy(textModelSelection = selection)
                    ModelPurpose.VISION -> current.copy(visionModelSelection = selection)
                    ModelPurpose.OCR -> current.copy(ocrModelSelection = selection)
                }
            }
        }
    }

    fun updateModelSelectionWithFavorite(
        purpose: ModelPurpose,
        selection: ModelSelection,
        favoriteModel: Boolean = false
    ) {
        viewModelScope.launch {
            store.update { current ->
                val next = when (purpose) {
                    ModelPurpose.TEXT -> current.copy(textModelSelection = selection)
                    ModelPurpose.VISION -> current.copy(visionModelSelection = selection)
                    ModelPurpose.OCR -> current.copy(ocrModelSelection = selection)
                }
                if (!favoriteModel || selection.providerId == null || selection.model.isBlank()) {
                    next
                } else {
                    next.copy(
                        providers = next.providers.map { provider ->
                            if (provider.id != selection.providerId) {
                                provider
                            } else {
                                provider.copy(
                                    favoriteModels = (provider.favoriteModels + selection.model)
                                        .map(String::trim)
                                        .filter(String::isNotBlank)
                                        .distinctBy { it.lowercase() }
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    fun toggleFavoriteModel(providerId: String, modelId: String) {
        val trimmedModelId = modelId.trim()
        if (trimmedModelId.isBlank()) return
        viewModelScope.launch {
            store.update { current ->
                current.copy(
                    providers = current.providers.map { provider ->
                        if (provider.id != providerId) {
                            provider
                        } else {
                            val exists = provider.favoriteModels.any { it.equals(trimmedModelId, ignoreCase = true) }
                            provider.copy(
                                favoriteModels = (if (exists) {
                                    provider.favoriteModels.filterNot { it.equals(trimmedModelId, ignoreCase = true) }
                                } else {
                                    provider.favoriteModels + trimmedModelId
                                }).map(String::trim)
                                    .filter(String::isNotBlank)
                                    .distinctBy { it.lowercase() }
                            )
                        }
                    }
                )
            }
        }
    }

    fun toggleProcessingRoute() {
        viewModelScope.launch {
            store.update { current ->
                current.copy(
                    processingRoute = when (current.processingRoute) {
                        ProcessingRoute.OCR_THEN_LLM -> ProcessingRoute.MULTIMODAL_DIRECT
                        ProcessingRoute.MULTIMODAL_DIRECT -> ProcessingRoute.OCR_THEN_LLM
                    }
                )
            }
        }
    }

    private fun buildModelSummary(model: String, providerName: String?): String {
        val trimmedModel = model.trim()
        val trimmedProvider = providerName?.trim().orEmpty()
        if (trimmedModel.isBlank()) return ""
        return if (trimmedProvider.isBlank()) trimmedModel else "$trimmedModel（$trimmedProvider）"
    }

    private suspend fun processAutoBitmap(bitmap: Bitmap) {
        val state = _uiState.value
        AppDebugLogStore.i(
            tag,
            "processAutoBitmap start route=${state.settings.processingRoute} bitmap=${bitmap.width}x${bitmap.height}"
        )
        val assistant = state.settings.assistants.firstOrNull { it.id == state.settings.selectedAssistantId }
            ?: error("请先配置助手")
        val ocrProvider = state.settings.resolveModelProvider(ModelPurpose.OCR)
        val ocrModel = state.settings.resolveModelName(ModelPurpose.OCR)
        val textProvider = state.settings.resolveModelProvider(ModelPurpose.TEXT)
        val textModel = state.settings.resolveModelName(ModelPurpose.TEXT)
        val visionProvider = state.settings.resolveModelProvider(ModelPurpose.VISION)
        val visionModel = state.settings.resolveModelName(ModelPurpose.VISION)
        val firstDeltaTimeoutMillis = state.settings.automation.autoModeTimeoutSeconds.coerceAtLeast(1) * 1000L
        val historyId = java.util.UUID.randomUUID().toString()
        val screenshotBase64 = bitmap.toHistoryBase64()
        val baseResult = ProcessingResult(
            id = historyId,
            assistantName = assistant.name,
            route = state.settings.processingRoute,
            status = ProcessingStatus.RUNNING,
            modelSummary = when (state.settings.processingRoute) {
                ProcessingRoute.OCR_THEN_LLM -> buildModelSummary(textModel, textProvider?.name)
                ProcessingRoute.MULTIMODAL_DIRECT -> buildModelSummary(visionModel, visionProvider?.name)
            },
            detail = "自动流程已开始",
            screenshotBase64 = screenshotBase64,
            events = listOf(ProcessingEvent(title = "请求开始", detail = "已创建自动处理记录"))
        )
        upsertHistory(baseResult)

        runCatching<Pair<`fun`.kirari.hanako.data.AutomationActionRecord, ProcessingResult>> {
            withTimeout<Pair<`fun`.kirari.hanako.data.AutomationActionRecord, ProcessingResult>>(processingTimeoutMillis) {
                val result: ProcessingResult = when (state.settings.processingRoute) {
                    ProcessingRoute.OCR_THEN_LLM -> {
                        AppDebugLogStore.i(tag, "processAutoBitmap route=OCR_THEN_LLM ocrModel=$ocrModel textModel=$textModel")
                        val usingLocalOcr = state.settings.ocrModelSelection.providerId == LOCAL_OCR_PROVIDER_ID
                        if ((!usingLocalOcr && (ocrProvider == null || ocrModel.isBlank())) || textProvider == null || textModel.isBlank()) {
                            error("请先在模型设置中配置 OCR 和文本模型")
                        }
                        if (usingLocalOcr) {
                            val (ocrText, automationResult) = runLocalOcrThenAutomation(
                                bitmap = bitmap,
                                assistant = assistant,
                                textProvider = textProvider,
                                textModel = textModel,
                                firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
                                onOcrDelta = { delta ->
                                    _uiState.update { current ->
                                        current.copy(liveOcrText = current.liveOcrText + delta)
                                    }
                                },
                                onThoughtDelta = { delta ->
                                    _uiState.update { current ->
                                        current.copy(liveAnswerText = current.liveAnswerText + delta)
                                    }
                                }
                            )
                            val ocrCompleted = baseResult.copy(
                                detail = "OCR 完成，等待自动动作",
                                extractedText = ocrText,
                                automationThought = automationResult.thought,
                                events = baseResult.events + ProcessingEvent(
                                    title = "OCR 完成",
                                    detail = "已提取 ${ocrText.length} 个字符"
                                )
                            )
                            upsertHistory(ocrCompleted)
                            ProcessingResult(
                                id = historyId,
                                assistantName = assistant.name,
                                route = ProcessingRoute.OCR_THEN_LLM,
                                status = ProcessingStatus.SUCCESS,
                                modelSummary = buildModelSummary(textModel, textProvider?.name),
                                detail = "自动处理完成",
                                extractedText = ocrText,
                                answer = "",
                                automationThought = automationResult.thought,
                                automationAction = automationResult.action,
                                screenshotBase64 = screenshotBase64,
                                events = ocrCompleted.events + ProcessingEvent(
                                    title = "工具动作完成",
                                    detail = "${automationResult.action.type}: ${automationResult.action.text}"
                                ),
                                createdAtMillis = baseResult.createdAtMillis
                            )
                        }
                        val remoteOcrProvider = requireNotNull(ocrProvider)
                        val (ocrText, automationResult) = gateway.streamOcrThenAutomation(
                            ocrProvider = remoteOcrProvider,
                            ocrModel = ocrModel,
                            textProvider = textProvider,
                            textModel = textModel,
                            assistant = assistant,
                            bitmap = bitmap,
                            firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
                            onOcrDelta = { delta ->
                                _uiState.update { current ->
                                    current.copy(liveOcrText = current.liveOcrText + delta)
                                }
                            },
                            onThoughtDelta = { delta ->
                                _uiState.update { current ->
                                    current.copy(liveAnswerText = current.liveAnswerText + delta)
                                }
                            }
                        )
                        val ocrCompleted = baseResult.copy(
                            detail = "OCR 完成，等待自动动作",
                            extractedText = ocrText,
                            automationThought = automationResult.thought,
                            events = baseResult.events + ProcessingEvent(
                                title = "OCR 完成",
                                detail = "已提取 ${ocrText.length} 个字符"
                            )
                        )
                        upsertHistory(ocrCompleted)
                        ProcessingResult(
                            id = historyId,
                            assistantName = assistant.name,
                            route = ProcessingRoute.OCR_THEN_LLM,
                            status = ProcessingStatus.SUCCESS,
                            modelSummary = buildModelSummary(textModel, textProvider?.name),
                            detail = "自动处理完成",
                            extractedText = ocrText,
                            answer = "",
                            automationThought = automationResult.thought,
                            automationAction = automationResult.action,
                            screenshotBase64 = screenshotBase64,
                            events = ocrCompleted.events + ProcessingEvent(
                                title = "工具动作完成",
                                detail = "${automationResult.action.type}: ${automationResult.action.text}"
                            ),
                            createdAtMillis = baseResult.createdAtMillis
                        )
                    }

                    ProcessingRoute.MULTIMODAL_DIRECT -> {
                        AppDebugLogStore.i(tag, "processAutoBitmap route=MULTIMODAL_DIRECT visionModel=$visionModel")
                        if (visionProvider == null || visionModel.isBlank()) {
                            error("请先在模型设置中配置多模态模型")
                        }
                        val automationResult = gateway.streamAutomationDirect(
                            provider = visionProvider,
                            model = visionModel,
                            assistant = assistant,
                            bitmap = bitmap,
                            firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
                            onThoughtDelta = { delta ->
                                _uiState.update { current ->
                                    current.copy(liveAnswerText = current.liveAnswerText + delta)
                                }
                            }
                        )
                        ProcessingResult(
                            id = historyId,
                            assistantName = assistant.name,
                            route = ProcessingRoute.MULTIMODAL_DIRECT,
                            status = ProcessingStatus.SUCCESS,
                            modelSummary = buildModelSummary(visionModel, visionProvider.name),
                            detail = "自动处理完成",
                            answer = "",
                            automationThought = automationResult.thought,
                            automationAction = automationResult.action,
                            screenshotBase64 = screenshotBase64,
                            events = baseResult.events + ProcessingEvent(
                                title = "工具动作完成",
                                detail = "${automationResult.action.type}: ${automationResult.action.text}"
                            ),
                            createdAtMillis = baseResult.createdAtMillis
                        )
                    }
                }
                val action = result.automationAction ?: error("自动模式未返回工具动作")
                AppDebugLogStore.i(
                    tag,
                    "processAutoBitmap gateway success resultId=${result.id} thoughtLength=${result.automationThought.length} action=${action.type} actionText=${action.text}"
                )
                upsertHistory(result)
                AppDebugLogStore.i(tag, "processAutoBitmap history persisted resultId=${result.id}")
                action to result
            }
        }.onSuccess { (action, result) ->
            val bubbleState = when (action.type) {
                AutomationActionType.SET_CLIPBOARD -> BubbleDisplayState.COPIED
                AutomationActionType.SHOW_BUBBLE_LETTERS -> BubbleDisplayState.SHOWING_LETTERS
            }
            AppDebugLogStore.i(
                tag,
                "processAutoBitmap ui success resultId=${result.id} bubbleState=$bubbleState letters=${action.text.takeIf { action.type == AutomationActionType.SHOW_BUBBLE_LETTERS }}"
            )
            _uiState.update {
                it.copy(
                    screenshot = bitmap,
                    selectedBitmap = bitmap,
                    working = false,
                    result = result,
                    liveAnswerText = result.automationThought,
                    autoRunState = AutoRunState.COMPLETED,
                    autoCopiedLabel = action.text.takeIf { action.type == AutomationActionType.SET_CLIPBOARD },
                    bubbleDisplayState = bubbleState,
                    bubbleLetters = action.text.takeIf { action.type == AutomationActionType.SHOW_BUBBLE_LETTERS },
                    error = null
                )
            }
        }.onFailure { error ->
            AppDebugLogStore.e(tag, "processAutoBitmap failed", error)
            val isTimeout = error is TimeoutCancellationException
            val message = error.message?.ifBlank { null } ?: if (isTimeout) "请求超时（90 秒）" else "处理失败"
            upsertHistory(
                baseResult.copy(
                    status = if (isTimeout) ProcessingStatus.TIMEOUT else ProcessingStatus.ERROR,
                    detail = message,
                    extractedText = _uiState.value.liveOcrText,
                    automationThought = _uiState.value.liveAnswerText,
                    events = baseResult.events + ProcessingEvent(
                        title = if (isTimeout) "请求超时" else "请求失败",
                        detail = message
                    )
                )
            )
            _uiState.update {
                it.copy(
                    working = false,
                    autoRunState = AutoRunState.IDLE,
                    bubbleDisplayState = BubbleDisplayState.IDLE,
                    bubbleLetters = null,
                    error = message
                )
            }
        }
    }

    private suspend fun upsertHistory(result: ProcessingResult) {
        store.update { current ->
            val history = (listOf(result) + current.history.filterNot { it.id == result.id }).take(20)
            current.copy(lastResult = result, history = history)
        }
    }

    private suspend fun runLocalOcrThenChat(
        bitmap: Bitmap,
        assistant: `fun`.kirari.hanako.data.AssistantPreset,
        textProvider: `fun`.kirari.hanako.data.ModelProviderConfig?,
        textModel: String,
        firstDeltaTimeoutMillis: Long,
        onOcrDelta: (String) -> Unit,
        onAnswerDelta: (String) -> Unit
    ): Pair<String, String> {
        val provider = requireNotNull(textProvider)
        val ocrText = withContext(Dispatchers.Default) {
            localOcrManager.recognize(bitmap)
        }
        onOcrDelta(ocrText)
        val answer = gateway.streamExtractedTextThenChat(
            textProvider = provider,
            textModel = textModel,
            assistant = assistant,
            extractedText = ocrText,
            firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
            onAnswerDelta = onAnswerDelta
        )
        return ocrText to answer
    }

    private suspend fun runLocalOcrThenAutomation(
        bitmap: Bitmap,
        assistant: `fun`.kirari.hanako.data.AssistantPreset,
        textProvider: `fun`.kirari.hanako.data.ModelProviderConfig?,
        textModel: String,
        firstDeltaTimeoutMillis: Long,
        onOcrDelta: (String) -> Unit,
        onThoughtDelta: (String) -> Unit
    ): Pair<String, AutomationResult> {
        val provider = requireNotNull(textProvider)
        val ocrText = withContext(Dispatchers.Default) {
            localOcrManager.recognize(bitmap)
        }
        onOcrDelta(ocrText)
        val result = gateway.streamExtractedTextThenAutomation(
            textProvider = provider,
            textModel = textModel,
            assistant = assistant,
            extractedText = ocrText,
            firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
            onThoughtDelta = onThoughtDelta
        )
        return ocrText to result
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return OverlayViewModel(
                        appContext = appContext,
                        store = SettingsStore(appContext),
                        gateway = AiGateway()
                    ) as T
                }
            }
    }
}
