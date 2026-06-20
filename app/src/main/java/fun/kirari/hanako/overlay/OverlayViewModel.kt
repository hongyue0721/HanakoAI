package `fun`.kirari.hanako.overlay

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `fun`.kirari.hanako.AppContainer
import `fun`.kirari.hanako.automation.BubbleEvent
import `fun`.kirari.hanako.automation.BubbleMenuItem
import `fun`.kirari.hanako.automation.BubbleState
import `fun`.kirari.hanako.automation.BubbleStateMachine
import `fun`.kirari.hanako.data.AutomationActionType
import `fun`.kirari.hanako.data.ModelPurpose
import `fun`.kirari.hanako.data.ProcessingEvent
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.ProcessingRoute
import `fun`.kirari.hanako.data.ProcessingStatus
import `fun`.kirari.hanako.data.ModelSelection
import `fun`.kirari.hanako.data.SettingsRepository
import `fun`.kirari.hanako.debug.AppDebugLogStore
import `fun`.kirari.hanako.localocr.LocalOcrManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class OverlayViewModel(
    private val appContext: Context,
    private val repository: SettingsRepository,
    private val pipeline: ProcessingPipeline
) : ViewModel() {
    private val tag = "HanakoOverlayVM"
    private val processingTimeoutMillis = 90_000L
    private var activeAutoProcessingJob: Job? = null
    
    // 新的状态机
    val bubbleStateMachine = BubbleStateMachine()
    
    private val _uiState = MutableStateFlow(OverlayUiState())
    val uiState: StateFlow<OverlayUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
        
        // 监听状态机变化，同步到 UI 状态
        viewModelScope.launch {
            bubbleStateMachine.state.collect { bubbleState ->
                _uiState.update { it.copy(bubbleState = bubbleState) }
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
                error = null
            )
        }
        // 普通模式重置状态机
        if (mode == OverlayLaunchMode.NORMAL) {
            bubbleStateMachine.forceState(BubbleState.Idle)
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
                `fun`.kirari.hanako.capture.ScreenCaptureManager.captureLatestBitmap(appContext, _uiState.value.settings.screenCaptureMethod)
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
                        autoCopiedLabel = null
                    )
                }
                bubbleStateMachine.forceState(BubbleState.Idle)
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
        activeAutoProcessingJob?.cancel()
        val job = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    liveOcrText = "",
                    liveAnswerText = "",
                    result = null,
                    error = null,
                    working = true,
                    sheetVisible = false,
                    autoRunState = AutoRunState.RUNNING,
                    autoCopiedLabel = null
                )
            }
            bubbleStateMachine.dispatch(BubbleEvent.StartProcessing)
            
            runCatching {
                withContext(Dispatchers.IO) {
                    `fun`.kirari.hanako.capture.ScreenCaptureManager.captureLatestBitmap(appContext, _uiState.value.settings.screenCaptureMethod)
                }
            }.onSuccess { bitmap ->
                AppDebugLogStore.i(tag, "processFullScreen capture success width=${bitmap.width} height=${bitmap.height}")
                processAutoBitmap(bitmap)
            }.onFailure { error ->
                if (error is CancellationException) {
                    AppDebugLogStore.i(tag, "processFullScreen cancelled")
                    return@onFailure
                }
                AppDebugLogStore.e(tag, "processFullScreen failed", error)
                _uiState.update {
                    it.copy(
                        working = false,
                        autoRunState = AutoRunState.IDLE,
                        error = error.message ?: "截屏失败"
                    )
                }
                bubbleStateMachine.forceState(BubbleState.Idle)
            }
        }
        activeAutoProcessingJob = job
        job.invokeOnCompletion {
            if (activeAutoProcessingJob === job) {
                activeAutoProcessingJob = null
            }
        }
    }

    fun process(bitmap: Bitmap) {
        process(listOf(bitmap))
    }

    fun process(bitmaps: List<Bitmap>) {
        val state = _uiState.value
        val firstBitmap = bitmaps.firstOrNull() ?: return
        AppDebugLogStore.i(tag, "process start route=${state.settings.processingRoute} bitmapCount=${bitmaps.size}")

        val models = runCatching { pipeline.resolveModels(state) }.getOrElse { error ->
            _uiState.update { it.copy(error = error.message) }
            return
        }
        val (baseResult, historyId, screenshotPaths) = pipeline.createBaseResult(models, bitmaps, "请求已开始")

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedBitmap = firstBitmap,
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
                    when (models.route) {
                        ProcessingRoute.OCR_THEN_LLM -> {
                            pipeline.validateOcrThenLlmModels(models)
                            val (ocrText, answer, searchOutcome) = pipeline.streamOcrThenChat(
                                models = models,
                                bitmaps = bitmaps,
                                onOcrDelta = { delta ->
                                    _uiState.update { current -> current.copy(liveOcrText = current.liveOcrText + delta) }
                                },
                                onAnswerDelta = { delta ->
                                    _uiState.update { current -> current.copy(liveAnswerText = current.liveAnswerText + delta) }
                                }
                            )
                            pipeline.buildChatResult(baseResult, models, ocrText, answer, historyId, screenshotPaths, searchOutcome)
                        }

                        ProcessingRoute.MULTIMODAL_DIRECT -> {
                            pipeline.validateVisionModels(models)
                            val answer = pipeline.streamVisionDirect(
                                models = models,
                                bitmaps = bitmaps,
                                onAnswerDelta = { delta ->
                                    _uiState.update { current -> current.copy(liveAnswerText = current.liveAnswerText + delta) }
                                }
                            )
                            pipeline.buildChatResult(baseResult, models, "", answer, historyId, screenshotPaths)
                        }
                    }
                }
            }.onSuccess { result ->
                AppDebugLogStore.i(tag, "process success resultId=${result.id} answerLength=${result.answer.length}")
                upsertHistory(result)
                _uiState.update {
                    it.copy(
                        working = false,
                        result = result,
                        liveOcrText = result.extractedText,
                        liveAnswerText = result.answer,
                        autoRunState = AutoRunState.IDLE,
                        autoCopiedLabel = null
                    )
                }
                bubbleStateMachine.forceState(BubbleState.Idle)
            }.onFailure { error ->
                AppDebugLogStore.e(tag, "process failed", error)
                handleError(error, baseResult)
            }
        }
    }

    fun closeSheet() {
        _uiState.update { it.copy(sheetVisible = false, error = null) }
    }

    fun consumeAutoCompletedState() {
        val currentState = bubbleStateMachine.currentState
        AppDebugLogStore.d(tag, "consumeAutoCompletedState state=${_uiState.value.autoRunState} bubble=${currentState::class.simpleName}")
        
        if (_uiState.value.launchMode == OverlayLaunchMode.AUTO && _uiState.value.autoRunState == AutoRunState.COMPLETED) {
            if (currentState is BubbleState.Copied) {
                _uiState.update { it.copy(autoRunState = AutoRunState.IDLE) }
                bubbleStateMachine.forceState(BubbleState.Idle)
            } else {
                _uiState.update { it.copy(autoRunState = AutoRunState.IDLE) }
            }
        }
    }

    /**
     * 将气泡状态重置为 Idle，用于 Error 状态的自动恢复
     */
    fun resetBubbleState() {
        val currentState = bubbleStateMachine.currentState
        AppDebugLogStore.i(tag, "resetBubbleState from ${currentState::class.simpleName}")
        bubbleStateMachine.dispatch(BubbleEvent.Reset)
    }

    fun onBubbleTappedAfterLettersShown() {
        val currentState = bubbleStateMachine.currentState
        AppDebugLogStore.i(tag, "onBubbleTappedAfterLettersShown launchMode=${_uiState.value.launchMode} bubble=${currentState::class.simpleName}")
        
        if (_uiState.value.launchMode == OverlayLaunchMode.AUTO && currentState is BubbleState.ShowingLetters) {
            AppDebugLogStore.i(tag, "onBubbleTappedAfterLettersShown clearing letters and entering pending reset")
            bubbleStateMachine.dispatch(BubbleEvent.SingleTap)
        }
    }

    // 多页截图相关方法
    
    /**
     * 进入多页截图模式
     */
    fun enterMultiPageCaptureMode() {
        AppDebugLogStore.i(tag, "enterMultiPageCaptureMode")
        bubbleStateMachine.dispatch(BubbleEvent.EnterMultiPageCapture)
    }

    /**
     * 截图一次
     */
    fun capturePage() {
        val currentState = bubbleStateMachine.currentState
        AppDebugLogStore.i(tag, "capturePage called state=${currentState::class.simpleName}")
        if (currentState !is BubbleState.MultiPageCapture) {
            AppDebugLogStore.i(tag, "capturePage called but not in MultiPageCapture state")
            return
        }
        
        AppDebugLogStore.i(tag, "capturePage count=${currentState.captureCount}")
        // 先切换到正在截图状态
        bubbleStateMachine.dispatch(BubbleEvent.CaptureStart)
        AppDebugLogStore.i(tag, "capturePage dispatched CaptureStart, new state=${bubbleStateMachine.currentState::class.simpleName}")
        
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    `fun`.kirari.hanako.capture.ScreenCaptureManager.captureLatestBitmap(appContext, _uiState.value.settings.screenCaptureMethod)
                }
            }.onSuccess { bitmap ->
                AppDebugLogStore.i(tag, "capturePage success width=${bitmap.width} height=${bitmap.height}")
                bubbleStateMachine.dispatch(BubbleEvent.CaptureTaken(bitmap))
                AppDebugLogStore.i(tag, "capturePage dispatched CaptureTaken, new state=${bubbleStateMachine.currentState::class.simpleName}")
                // 2 秒后恢复到等待点击状态
                launch {
                    kotlinx.coroutines.delay(2000)
                    bubbleStateMachine.dispatch(BubbleEvent.CaptureSuccessAnimationDone)
                    AppDebugLogStore.i(tag, "capturePage dispatched CaptureSuccessAnimationDone, new state=${bubbleStateMachine.currentState::class.simpleName}")
                }
            }.onFailure { error ->
                AppDebugLogStore.e(tag, "capturePage failed", error)
                _uiState.update { it.copy(error = error.message ?: "截图失败") }
                // 截图失败，恢复到等待点击状态
                bubbleStateMachine.dispatch(BubbleEvent.CaptureFailed)
                AppDebugLogStore.i(tag, "capturePage failed, dispatched CaptureFailed")
            }
        }
    }

    /**
     * 发送截图给 AI
     */
    fun sendCaptures() {
        AppDebugLogStore.i(tag, "sendCaptures called state=${bubbleStateMachine.currentState::class.simpleName}")
        if (!bubbleStateMachine.canSendCaptures()) {
            AppDebugLogStore.i(tag, "sendCaptures called but cannot send")
            return
        }
        
        val bitmaps = bubbleStateMachine.getCapturedBitmaps()
        AppDebugLogStore.i(tag, "sendCaptures count=${bitmaps.size}")
        
        if (bitmaps.isEmpty()) {
            AppDebugLogStore.i(tag, "sendCaptures no bitmaps available")
            return
        }
        
        bubbleStateMachine.dispatch(BubbleEvent.SendCaptures)
        AppDebugLogStore.i(tag, "sendCaptures dispatched SendCaptures, new state=${bubbleStateMachine.currentState::class.simpleName}")
        activeAutoProcessingJob?.cancel()
        val job = viewModelScope.launch {
            processAutoBitmaps(bitmaps)
        }
        activeAutoProcessingJob = job
        job.invokeOnCompletion {
            if (activeAutoProcessingJob === job) {
                activeAutoProcessingJob = null
            }
        }
    }

    /**
     * 退出多页截图模式
     */
    fun exitMultiPageCaptureMode() {
        AppDebugLogStore.i(tag, "exitMultiPageCaptureMode")
        bubbleStateMachine.dispatch(BubbleEvent.DoubleTap)
    }

    /**
     * 处理单击事件（根据当前状态决定行为）
     */
    fun handleSingleTap() {
        val currentState = bubbleStateMachine.currentState
        AppDebugLogStore.i(tag, "handleSingleTap state=${currentState::class.simpleName}")

        when (currentState) {
            is BubbleState.MultiPageCapture -> {
                // 多页截图模式下，单击截图
                capturePage()
            }
            is BubbleState.MultiPageCapturing -> {
                // 正在截图中，忽略单击
                AppDebugLogStore.i(tag, "handleSingleTap ignored, capturing in progress")
            }
            is BubbleState.MultiPageCaptureSuccess -> {
                // 截图成功显示中，忽略单击
                AppDebugLogStore.i(tag, "handleSingleTap ignored, showing capture success")
            }
            is BubbleState.Copied -> {
                // 已复制状态下，单击重置
                bubbleStateMachine.dispatch(BubbleEvent.SingleTap)
            }
            is BubbleState.Error -> {
                // 错误状态下单击重置
                bubbleStateMachine.dispatch(BubbleEvent.SingleTap)
            }
            is BubbleState.MenuExpanded -> {
                // 菜单展开时，单击关闭菜单
                bubbleStateMachine.dispatch(BubbleEvent.CloseMenu)
            }
            else -> {
                // 其他状态（Idle、ShowingLetters、Processing 等），打开裁剪页面
                openCropSheet()
            }
        }
    }

    /**
     * 处理长按事件（根据当前状态决定行为）
     */
    fun handleLongPress() {
        val currentState = bubbleStateMachine.currentState
        val pos = _uiState.value
        AppDebugLogStore.i(tag, "handleLongPress state=${currentState::class.simpleName} anchor=(${pos.bubbleScreenX},${pos.bubbleScreenY})")

        when (currentState) {
            is BubbleState.MultiPageCapture -> {
                AppDebugLogStore.i(tag, "handleLongPress MultiPageCapture bitmaps=${currentState.capturedBitmaps.size}")
                if (currentState.capturedBitmaps.isNotEmpty()) {
                    sendCaptures()
                }
            }
            is BubbleState.MultiPageCapturing -> {
                AppDebugLogStore.i(tag, "handleLongPress ignored, capturing in progress")
            }
            is BubbleState.MultiPageCaptureSuccess -> {
                AppDebugLogStore.i(tag, "handleLongPress MultiPageCaptureSuccess bitmaps=${currentState.capturedBitmaps.size}")
                if (currentState.capturedBitmaps.isNotEmpty()) {
                    sendCaptures()
                }
            }
            is BubbleState.MenuExpanded -> {
                bubbleStateMachine.dispatch(BubbleEvent.CloseMenu)
            }
            is BubbleState.Idle, is BubbleState.ShowingLetters,
            is BubbleState.Copied, is BubbleState.Error -> {
                // 长按直接进入多屏截图模式
                enterMultiPageCaptureMode()
            }
            else -> {
                // Processing 等状态长按展开菜单
                bubbleStateMachine.dispatch(BubbleEvent.LongPress(pos.bubbleScreenX, pos.bubbleScreenY))
            }
        }
    }

    /**
     * 处理双击事件
     */
    fun handleDoubleTap() {
        val currentState = bubbleStateMachine.currentState
        val launchMode = _uiState.value.launchMode
        AppDebugLogStore.i(tag, "handleDoubleTap state=${currentState::class.simpleName} launchMode=$launchMode")

        when (currentState) {
            is BubbleState.MultiPageCapture,
            is BubbleState.MultiPageCapturing,
            is BubbleState.MultiPageCaptureSuccess -> {
                exitMultiPageCaptureMode()
            }
            is BubbleState.Processing -> {
                cancelActiveAutoProcessing()
            }
            is BubbleState.Idle -> {
                // Idle 双击展开菜单
                val pos = _uiState.value
                bubbleStateMachine.dispatch(BubbleEvent.LongPress(pos.bubbleScreenX, pos.bubbleScreenY))
            }
            is BubbleState.MenuExpanded -> {
                bubbleStateMachine.dispatch(BubbleEvent.CloseMenu)
            }
            else -> {}
        }
    }

    fun selectAssistant(assistantId: String) = repository.selectAssistant(viewModelScope, assistantId)

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

    fun updateModelSelection(purpose: ModelPurpose, selection: ModelSelection) =
        repository.updateModelSelection(viewModelScope, purpose, selection)

    fun updateModelSelectionWithFavorite(purpose: ModelPurpose, selection: ModelSelection, favoriteModel: Boolean = false) =
        repository.updateModelSelectionWithFavorite(viewModelScope, purpose, selection, favoriteModel)

    fun toggleFavoriteModel(providerId: String, modelId: String) =
        repository.toggleFavoriteModel(viewModelScope, providerId, modelId)

    fun toggleProcessingRoute() {
        viewModelScope.launch {
            repository.update { current ->
                current.copy(
                    processingRoute = when (current.processingRoute) {
                        ProcessingRoute.OCR_THEN_LLM -> ProcessingRoute.MULTIMODAL_DIRECT
                        ProcessingRoute.MULTIMODAL_DIRECT -> ProcessingRoute.OCR_THEN_LLM
                    }
                )
            }
        }
    }

    fun toggleWebSearch() {
        viewModelScope.launch {
            repository.update { current ->
                current.copy(webSearch = current.webSearch.copy(enabled = !current.webSearch.enabled))
            }
        }
    }

    /**
     * 处理菜单项选择
     */
    fun handleMenuSelect(item: BubbleMenuItem) {
        val previousState = (bubbleStateMachine.currentState as? BubbleState.MenuExpanded)?.previousState
        AppDebugLogStore.i(tag, "handleMenuSelect item=$item previous=${previousState?.let { it::class.simpleName }}")

        // 先关闭菜单，恢复到之前状态
        bubbleStateMachine.dispatch(BubbleEvent.CloseMenu)

        when (item) {
            BubbleMenuItem.MultiPage -> {
                if (previousState is BubbleState.Idle ||
                    previousState is BubbleState.ShowingLetters ||
                    previousState is BubbleState.Copied ||
                    previousState is BubbleState.Error
                ) {
                    enterMultiPageCaptureMode()
                } else {
                    Toast.makeText(appContext, "当前状态无法进入多图模式", Toast.LENGTH_SHORT).show()
                }
            }
            BubbleMenuItem.ToggleRoute -> toggleProcessingRoute()
            BubbleMenuItem.ToggleSearch -> toggleWebSearch()
            BubbleMenuItem.VoiceRecognition -> {
                Toast.makeText(appContext, "语音识别暂未实现", Toast.LENGTH_SHORT).show()
            }
            BubbleMenuItem.Settings -> openMainActivity(appContext)
        }
    }

    /**
     * 更新气泡屏幕位置，用于菜单定位
     */
    fun updateBubblePosition(x: Int, y: Int) {
        val current = _uiState.value
        if (current.bubbleScreenX != x || current.bubbleScreenY != y) {
            _uiState.update { it.copy(bubbleScreenX = x, bubbleScreenY = y) }
        }
    }

    private suspend fun processAutoBitmap(bitmap: Bitmap) {
        processAutoBitmaps(listOf(bitmap))
    }

    private suspend fun processAutoBitmaps(bitmaps: List<Bitmap>) {
        val state = _uiState.value
        val firstBitmap = bitmaps.firstOrNull() ?: return
        AppDebugLogStore.i(tag, "processAutoBitmaps start route=${state.settings.processingRoute} bitmapCount=${bitmaps.size}")

        val models = runCatching { pipeline.resolveModels(state) }.getOrElse { error ->
            _uiState.update { it.copy(working = false, autoRunState = AutoRunState.IDLE, error = error.message) }
            bubbleStateMachine.forceState(BubbleState.Idle)
            return
        }
        val (baseResult, historyId, screenshotPaths) = pipeline.createBaseResult(models, bitmaps, "自动流程已开始")
        upsertHistory(baseResult)

        runCatching<Pair<`fun`.kirari.hanako.data.AutomationActionRecord, ProcessingResult>> {
            withTimeout(processingTimeoutMillis) {
                val (action, result) = when (models.route) {
                    ProcessingRoute.OCR_THEN_LLM -> {
                        pipeline.validateOcrThenLlmModels(models)
                        val (ocrText, automationResult, searchOutcome) = pipeline.streamOcrThenAutomation(
                            models = models,
                            bitmaps = bitmaps,
                            onOcrDelta = { delta ->
                                _uiState.update { current -> current.copy(liveOcrText = current.liveOcrText + delta) }
                            },
                            onThoughtDelta = { delta ->
                                _uiState.update { current -> current.copy(liveAnswerText = current.liveAnswerText + delta) }
                            }
                        )
                        pipeline.buildAutomationResult(baseResult, models, ocrText, automationResult, historyId, screenshotPaths, searchOutcome)
                    }

                    ProcessingRoute.MULTIMODAL_DIRECT -> {
                        pipeline.validateVisionModels(models)
                        val automationResult = pipeline.streamAutomationDirect(
                            models = models,
                            bitmaps = bitmaps,
                            onThoughtDelta = { delta ->
                                _uiState.update { current -> current.copy(liveAnswerText = current.liveAnswerText + delta) }
                            }
                        )
                        pipeline.buildAutomationResult(baseResult, models, "", automationResult, historyId, screenshotPaths)
                    }
                }
                AppDebugLogStore.i(tag, "processAutoBitmaps gateway success resultId=${result.id} action=${action.type}")
                upsertHistory(result)
                action to result
            }
        }.onSuccess { (action, result) ->
            when (action.type) {
                AutomationActionType.SET_CLIPBOARD -> {
                    _uiState.update {
                        it.copy(
                            screenshot = firstBitmap,
                            selectedBitmap = firstBitmap,
                            working = false,
                            result = result,
                            liveAnswerText = result.automationThought,
                            autoRunState = AutoRunState.COMPLETED,
                            autoCopiedLabel = action.text,
                            error = null
                        )
                    }
                    bubbleStateMachine.dispatch(BubbleEvent.CopyComplete(action.text))
                }
                AutomationActionType.SHOW_BUBBLE_LETTERS -> {
                    _uiState.update {
                        it.copy(
                            screenshot = firstBitmap,
                            selectedBitmap = firstBitmap,
                            working = false,
                            result = result,
                            liveAnswerText = result.automationThought,
                            autoRunState = AutoRunState.COMPLETED,
                            autoCopiedLabel = null,
                            error = null
                        )
                    }
                    bubbleStateMachine.dispatch(BubbleEvent.LettersComplete(action.text))
                }
            }
        }.onFailure { error ->
            if (error is CancellationException) {
                AppDebugLogStore.i(tag, "processAutoBitmaps cancelled")
                return
            }
            AppDebugLogStore.e(tag, "processAutoBitmaps failed", error)
            handleError(error, baseResult, isAutoMode = true)
        }
    }

    private fun cancelActiveAutoProcessing() {
        AppDebugLogStore.i(tag, "cancelActiveAutoProcessing")
        activeAutoProcessingJob?.cancel()
        activeAutoProcessingJob = null
        _uiState.update {
            it.copy(
                working = false,
                autoRunState = AutoRunState.IDLE,
                autoCopiedLabel = null,
                error = null
            )
        }
        bubbleStateMachine.dispatch(BubbleEvent.CancelProcessing)
        if (bubbleStateMachine.currentState !is BubbleState.Idle) {
            bubbleStateMachine.forceState(BubbleState.Idle)
        }
    }

    private suspend fun handleError(error: Throwable, baseResult: ProcessingResult, isAutoMode: Boolean = false) {
        val isTimeout = error is TimeoutCancellationException
        val message = error.message?.ifBlank { null } ?: if (isTimeout) "请求超时（90 秒）" else "处理失败"
        upsertHistory(
            baseResult.copy(
                status = if (isTimeout) ProcessingStatus.TIMEOUT else ProcessingStatus.ERROR,
                detail = message,
                extractedText = _uiState.value.liveOcrText,
                answer = if (isAutoMode) "" else _uiState.value.liveAnswerText,
                automationThought = if (isAutoMode) _uiState.value.liveAnswerText.orEmpty() else "",
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
        if (isAutoMode) {
            bubbleStateMachine.dispatch(BubbleEvent.ErrorOccurred(message))
        }
    }

    private suspend fun upsertHistory(result: ProcessingResult) {
        repository.update { current ->
            val history = listOf(result) + current.history.filterNot { it.id == result.id }
            current.copy(lastResult = result, history = history)
        }
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory {
            val container = (appContext.applicationContext as `fun`.kirari.hanako.HanakoApplication).container
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return OverlayViewModel(
                        appContext = appContext,
                        repository = container.settingsRepository,
                        pipeline = ProcessingPipeline(appContext, container.unifiedLLMClient, container.localOcrManager, container.searchOrchestrator)
                    ) as T
                }
            }
        }
    }
}
