package `fun`.kirari.hanako.automation

import android.graphics.Bitmap

/**
 * 悬浮球状态定义
 * 使用密封类确保状态的穷尽性检查
 */
sealed class BubbleState {
    /** 空闲状态 */
    data object Idle : BubbleState()

    /** 处理中状态（AI 正在处理） */
    data object Processing : BubbleState()

    /** 已复制状态（结果已复制到剪贴板） */
    data class Copied(val label: String?) : BubbleState()

    /** 显示字母状态（自动模式显示选项字母） */
    data class ShowingLetters(val letters: String) : BubbleState()

    /** 多页截图模式 - 等待点击 */
    data class MultiPageCapture(
        val capturedBitmaps: List<Bitmap> = emptyList(),
        val captureCount: Int = 0
    ) : BubbleState() {
        fun addCapture(bitmap: Bitmap): MultiPageCapture {
            return copy(
                capturedBitmaps = capturedBitmaps + bitmap,
                captureCount = captureCount + 1
            )
        }
    }

    /** 多页截图模式 - 正在截图 */
    data class MultiPageCapturing(
        val capturedBitmaps: List<Bitmap> = emptyList(),
        val captureCount: Int = 0
    ) : BubbleState()

    /** 多页截图模式 - 截图成功（短暂显示） */
    data class MultiPageCaptureSuccess(
        val capturedBitmaps: List<Bitmap> = emptyList(),
        val captureCount: Int = 0
    ) : BubbleState()
}

/**
 * 悬浮球事件定义
 * 所有可能触发状态转换的事件
 */
sealed class BubbleEvent {
    /** 开始处理（普通模式） */
    data object StartProcessing : BubbleEvent()

    /** 处理完成（带复制） */
    data class CopyComplete(val label: String) : BubbleEvent()

    /** 处理完成（带字母） */
    data class LettersComplete(val letters: String) : BubbleEvent()

    /** 单击事件 */
    data object SingleTap : BubbleEvent()

    /** 长按事件 */
    data object LongPress : BubbleEvent()

    /** 双击事件 */
    data object DoubleTap : BubbleEvent()

    /** 取消处理事件 */
    data object CancelProcessing : BubbleEvent()

    /** 重置事件 */
    data object Reset : BubbleEvent()

    /** 超时事件 */
    data object Timeout : BubbleEvent()

    /** 开始截图事件 */
    data object CaptureStart : BubbleEvent()

    /** 截图完成事件 */
    data class CaptureTaken(val bitmap: Bitmap) : BubbleEvent()

    /** 截图失败事件 */
    data object CaptureFailed : BubbleEvent()

    /** 截图成功动画完成事件 */
    data object CaptureSuccessAnimationDone : BubbleEvent()

    /** 发送截图事件 */
    data object SendCaptures : BubbleEvent()
}
