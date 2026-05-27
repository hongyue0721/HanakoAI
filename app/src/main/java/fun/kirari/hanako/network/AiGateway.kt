package `fun`.kirari.hanako.network

import android.graphics.Bitmap
import `fun`.kirari.hanako.automation.AutomationResult
import `fun`.kirari.hanako.automation.automationSystemPrompt
import `fun`.kirari.hanako.data.AssistantPreset
import `fun`.kirari.hanako.data.ModelProviderConfig
import `fun`.kirari.hanako.debug.AppDebugLogStore
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AiGateway(
    private val client: OkHttpClient = OkHttpClient.Builder()
        // SSE streaming should not be cut off by a fixed socket read timeout.
        // First-token timing is enforced separately by SseStreamClient.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
    internal val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val tag = "HanakoAiGateway"
    internal val sseClient = SseStreamClient(client)
    internal val JSON = "application/json; charset=utf-8".toMediaType()

    private fun assistantPromptWithCopyMarker(systemPrompt: String): String {
        val trimmed = systemPrompt.trim()
        if (trimmed.isBlank()) return trimmed
        return """
            你可以在回答中插入如下格式的可复制文本块：
            [copy:内容]
            其中“内容”会显示为一个小标签，点击复制图标后会写入同样的文本到剪贴板。
            对于问题的答案或用户需要填写到某个表单中的内容，你必须给出一键复制的标签。

            $trimmed
        """.trimIndent()
    }

    suspend fun streamOcrThenChat(
        ocrProvider: ModelProviderConfig,
        ocrModel: String,
        textProvider: ModelProviderConfig,
        textModel: String,
        assistant: AssistantPreset,
        bitmap: Bitmap,
        firstDeltaTimeoutMillis: Long,
        onOcrDelta: (String) -> Unit,
        onAnswerDelta: (String) -> Unit
    ): Pair<String, String> {
        AppDebugLogStore.i(tag, "streamOcrThenChat start ocrModel=$ocrModel textModel=$textModel bitmap=${bitmap.width}x${bitmap.height}")
        val ocrText = streamVision(
            provider = ocrProvider,
            model = ocrModel,
            systemPrompt = assistant.ocrPrompt,
            userPrompt = "请执行 OCR。",
            imageBase64 = bitmap.toBase64Jpeg(),
            firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
            onDelta = onOcrDelta
        )
        val answer = streamText(
            provider = textProvider,
            model = textModel,
            systemPrompt = assistantPromptWithCopyMarker(assistant.textPrompt),
            userPrompt = "以下是 OCR 结果，请完成任务：\n$ocrText",
            firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
            onDelta = onAnswerDelta
        )
        AppDebugLogStore.i(tag, "streamOcrThenChat success ocrLength=${ocrText.length} answerLength=${answer.length}")
        return ocrText to answer
    }

    suspend fun streamOcrThenAutomation(
        ocrProvider: ModelProviderConfig,
        ocrModel: String,
        textProvider: ModelProviderConfig,
        textModel: String,
        assistant: AssistantPreset,
        bitmap: Bitmap,
        firstDeltaTimeoutMillis: Long,
        onOcrDelta: (String) -> Unit,
        onThoughtDelta: (String) -> Unit
    ): Pair<String, AutomationResult> {
        AppDebugLogStore.i(tag, "streamOcrThenAutomation start ocrModel=$ocrModel textModel=$textModel bitmap=${bitmap.width}x${bitmap.height}")
        val ocrText = streamVision(
            provider = ocrProvider,
            model = ocrModel,
            systemPrompt = assistant.ocrPrompt,
            userPrompt = "请执行 OCR。",
            imageBase64 = bitmap.toBase64Jpeg(),
            firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
            onDelta = onOcrDelta
        )
        val answer = streamAutomation(
            provider = textProvider,
            model = textModel,
            systemPrompt = automationSystemPrompt(assistant.textPrompt),
            userPrompt = "以下是 OCR 结果，请先输出思考过程，再通过一次工具调用给出自动模式动作：\n$ocrText",
            imageBase64 = null,
            firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
            onThoughtDelta = onThoughtDelta
        )
        AppDebugLogStore.i(
            tag,
            "streamOcrThenAutomation success ocrLength=${ocrText.length} thoughtLength=${answer.thought.length} action=${answer.action.type} actionText=${answer.action.text}"
        )
        return ocrText to answer
    }

    suspend fun streamExtractedTextThenChat(
        textProvider: ModelProviderConfig,
        textModel: String,
        assistant: AssistantPreset,
        extractedText: String,
        firstDeltaTimeoutMillis: Long,
        onAnswerDelta: (String) -> Unit
    ): String {
        AppDebugLogStore.i(tag, "streamExtractedTextThenChat start textModel=$textModel extractedLength=${extractedText.length}")
        val answer = streamText(
            provider = textProvider,
            model = textModel,
            systemPrompt = assistantPromptWithCopyMarker(assistant.textPrompt),
            userPrompt = "以下是 OCR 结果，请完成任务：\n$extractedText",
            firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
            onDelta = onAnswerDelta
        )
        AppDebugLogStore.i(tag, "streamExtractedTextThenChat success answerLength=${answer.length}")
        return answer
    }

    suspend fun streamExtractedTextThenAutomation(
        textProvider: ModelProviderConfig,
        textModel: String,
        assistant: AssistantPreset,
        extractedText: String,
        firstDeltaTimeoutMillis: Long,
        onThoughtDelta: (String) -> Unit
    ): AutomationResult {
        AppDebugLogStore.i(
            tag,
            "streamExtractedTextThenAutomation start textModel=$textModel extractedLength=${extractedText.length}"
        )
        val result = streamAutomation(
            provider = textProvider,
            model = textModel,
            systemPrompt = automationSystemPrompt(assistant.textPrompt),
            userPrompt = "以下是 OCR 结果，请先输出思考过程，再通过一次工具调用给出自动模式动作：\n$extractedText",
            imageBase64 = null,
            firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
            onThoughtDelta = onThoughtDelta
        )
        AppDebugLogStore.i(
            tag,
            "streamExtractedTextThenAutomation success thoughtLength=${result.thought.length} action=${result.action.type}"
        )
        return result
    }

    suspend fun streamVisionDirect(
        provider: ModelProviderConfig,
        model: String,
        assistant: AssistantPreset,
        bitmap: Bitmap,
        firstDeltaTimeoutMillis: Long,
        onAnswerDelta: (String) -> Unit
    ): String {
        AppDebugLogStore.i(tag, "streamVisionDirect start visionModel=$model bitmap=${bitmap.width}x${bitmap.height}")
        val answer = streamVision(
            provider = provider,
            model = model,
            systemPrompt = assistantPromptWithCopyMarker(assistant.visionPrompt),
            userPrompt = "请直接基于图片内容完成任务。",
            imageBase64 = bitmap.toBase64Jpeg(),
            firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
            onDelta = onAnswerDelta
        )
        AppDebugLogStore.i(tag, "streamVisionDirect success answerLength=${answer.length}")
        return answer
    }

    suspend fun streamAutomationDirect(
        provider: ModelProviderConfig,
        model: String,
        assistant: AssistantPreset,
        bitmap: Bitmap,
        firstDeltaTimeoutMillis: Long,
        onThoughtDelta: (String) -> Unit
    ): AutomationResult {
        AppDebugLogStore.i(tag, "streamAutomationDirect start visionModel=$model bitmap=${bitmap.width}x${bitmap.height}")
        val result = streamAutomation(
            provider = provider,
            model = model,
            systemPrompt = automationSystemPrompt(assistant.visionPrompt),
            userPrompt = "请根据整张屏幕截图先输出思考过程，再通过一次工具调用给出自动模式动作。",
            imageBase64 = bitmap.toBase64Jpeg(),
            firstDeltaTimeoutMillis = firstDeltaTimeoutMillis,
            onThoughtDelta = onThoughtDelta
        )
        AppDebugLogStore.i(
            tag,
            "streamAutomationDirect success thoughtLength=${result.thought.length} action=${result.action.type} actionText=${result.action.text}"
        )
        return result
    }
}
