package com.example.watchping

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min

data class OcrResult(val text: String, val engine: String, val warning: String?)

object Ocr {
    private val languages = listOf("rus", "eng")
    private const val BASE_URL = "https://github.com/tesseract-ocr/tessdata_best/raw/main/"

    /** Загружает фото с учётом поворота и уменьшает слишком большие. */
    fun loadBitmap(ctx: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(ctx.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val biggest = max(info.size.width, info.size.height)
            // округляем вверх: фото с камеры (4000+ px) уменьшаем минимум вдвое, иначе не хватит памяти
            decoder.setTargetSampleSize(max(1, (biggest + 2399) / 2400))
        }
    }

    /** Вызывать из фонового потока. Фото обрабатывается OpenAI; без ключа показывается понятная ошибка. */
    fun recognize(ctx: Context, bitmap: Bitmap, onStatus: (String) -> Unit): OcrResult {
        val key = Prefs.apiKey(ctx)
        if (key.isBlank()) {
            throw IllegalStateException("Сначала добавьте OpenAI API-ключ во вкладке «Настройки»")
        }
        onStatus("Отправляю фотографию в OpenAI для распознавания…")
        return try {
            OcrResult(OpenAiOcr.recognize(key, bitmap), "OpenAI", null)
        } catch (e: Exception) {
            throw IllegalStateException("OpenAI не смог обработать фото: ${e.message}", e)
        }
    }

    // ---------- локальное распознавание (Tesseract) ----------

    private fun ensureLanguageData(ctx: Context, onStatus: (String) -> Unit): File {
        val root = File(ctx.filesDir, "ocr")
        val tessdata = File(root, "tessdata").apply { mkdirs() }
        for (lang in languages) {
            val target = File(tessdata, "$lang.traineddata")
            if (target.exists() && target.length() > 1_000_000) continue
            onStatus("Загружаю языковые данные ($lang), один раз…")
            val tmp = File(tessdata, "$lang.traineddata.part")
            val conn = URL(BASE_URL + "$lang.traineddata").openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            try {
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            } finally {
                conn.disconnect()
            }
            if (tmp.length() < 1_000_000) {
                tmp.delete()
                throw IllegalStateException("не удалось загрузить языковые данные")
            }
            tmp.renameTo(target)
        }
        return root
    }

    private fun recognizeLocal(ctx: Context, bitmap: Bitmap, onStatus: (String) -> Unit): String {
        val dataRoot = ensureLanguageData(ctx, onStatus)
        onStatus("Подготавливаю изображение…")
        val prepared = binarize(bitmap)
        onStatus("Распознаю текст…")
        val api = TessBaseAPI()
        try {
            if (!api.init(dataRoot.absolutePath, languages.joinToString("+"))) {
                throw IllegalStateException("не удалось запустить распознавание")
            }
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            api.setImage(prepared)
            return cleanText(api.utF8Text ?: "")
        } finally {
            api.recycle()
        }
    }

    /** Серый + адаптивная бинаризация: убирает тени и неравномерный свет на фото листа. */
    private fun binarize(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            gray[i] = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        val stride = w + 1
        val integral = IntArray(stride * (h + 1))
        for (y in 1..h) {
            var rowSum = 0
            for (x in 1..w) {
                rowSum += gray[(y - 1) * w + (x - 1)]
                integral[y * stride + x] = integral[(y - 1) * stride + x] + rowSum
            }
        }
        val r = max(12, min(w, h) / 30)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w, x + r + 1)
                val area = (x1 - x0) * (y1 - y0)
                val sum = integral[y1 * stride + x1] - integral[y0 * stride + x1] -
                    integral[y1 * stride + x0] + integral[y0 * stride + x0]
                val mean = sum / area
                val v = if (gray[y * w + x] < mean * 88 / 100) 0 else 255
                out[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private val junk = Regex("[^\\u0400-\\u04FFA-Za-z0-9\\s.,;:!?()\\-'\"«»№%+/=…]")

    /** Убирает «непонятные символы» и строки, которые состоят в основном из мусора. */
    private fun cleanText(raw: String): String {
        val lines = raw.lines().mapNotNull { line ->
            val cleaned = junk.replace(line, " ")
            val tokens = cleaned.split(Regex("\\s+")).filter { t -> t.any { it.isLetterOrDigit() } }
            if (tokens.isEmpty()) return@mapNotNull null
            val joined = tokens.joinToString(" ")
            val letters = joined.count { it.isLetterOrDigit() }
            val visible = joined.count { !it.isWhitespace() }
            if (letters < 2 || letters * 100 / max(1, visible) < 70) null else joined
        }
        return lines.joinToString("\n").trim()
    }
}
