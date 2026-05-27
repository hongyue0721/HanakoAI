package `fun`.kirari.hanako.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

private const val HISTORY_IMAGES_DIR = "history_images"

fun Bitmap.toHistoryBase64(maxDimension: Int = 1280, quality: Int = 82): String {
    val scaled = scaleDownIfNeeded(maxDimension)
    val output = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
    if (scaled !== this) {
        scaled.recycle()
    }
    return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
}

fun String.decodeHistoryBitmap(): Bitmap? {
    return runCatching {
        val bytes = Base64.decode(this, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

fun Bitmap.saveToHistoryFile(context: Context, id: String, maxDimension: Int = 1280, quality: Int = 82): String {
    val dir = File(context.filesDir, HISTORY_IMAGES_DIR)
    if (!dir.exists()) dir.mkdirs()
    val file = File(dir, "$id.jpg")
    val scaled = scaleDownIfNeeded(maxDimension)
    file.outputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
    }
    if (scaled !== this) {
        scaled.recycle()
    }
    return file.absolutePath
}

fun String.loadHistoryBitmap(): Bitmap? {
    return runCatching {
        val file = File(this)
        if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }.getOrNull()
}

fun migrateBase64ToFile(context: Context, result: ProcessingResult): ProcessingResult {
    if (result.screenshotPath != null) return result
    val base64 = result.screenshotBase64 ?: return result
    val bitmap = base64.decodeHistoryBitmap() ?: return result
    val path = bitmap.saveToHistoryFile(context, result.id)
    bitmap.recycle()
    return result.copy(screenshotBase64 = null, screenshotPath = path)
}

private fun Bitmap.scaleDownIfNeeded(maxDimension: Int): Bitmap {
    val longestEdge = maxOf(width, height)
    if (longestEdge <= maxDimension) return this
    val scale = maxDimension.toFloat() / longestEdge.toFloat()
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
}
