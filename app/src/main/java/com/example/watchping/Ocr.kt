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

/** Распознавание текста (русский + английский) через Tesseract. */
object Ocr {
    private val languages = listOf("rus", "eng")
    private const val BASE_URL = "https://github.com/tesseract-ocr/tessdata_fast/raw/main/"

    /** Загружает фото в память с учётом поворота и уменьшает слишком большие. */
    fun loadBitmap(ctx: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(ctx.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val biggest = max(info.size.width, info.size.height)
            val sample = max(1, biggest / 2400)
            decoder.setTargetSampleSize(sample)
        }
    }

    /** Скачивает языковые данные при первом запуске (около 5 МБ). */
    private fun ensureLanguageData(ctx: Context, onStatus: (String) -> Unit): File {
        val root = ctx.filesDir
        val tessdata = File(root, "tessdata").apply { mkdirs() }
        for (lang in languages) {
            val target = File(tessdata, "$lang.traineddata")
            if (target.exists() && target.length() > 100_000) continue
            onStatus("Загружаю языковые данные ($lang)…")
            val tmp = File(tessdata, "$lang.traineddata.part")
            val conn = URL(BASE_URL + "$lang.traineddata").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            tmp.renameTo(target)
        }
        return root
    }

    /** Вызывать из фонового потока. */
    fun recognize(ctx: Context, bitmap: Bitmap, onStatus: (String) -> Unit): String {
        val dataRoot = ensureLanguageData(ctx, onStatus)
        onStatus("Распознаю текст…")
        val api = TessBaseAPI()
        try {
            if (!api.init(dataRoot.absolutePath, languages.joinToString("+"))) {
                throw IllegalStateException("Не удалось инициализировать OCR")
            }
            api.setImage(bitmap)
            return (api.utF8Text ?: "").trim()
        } finally {
            api.recycle()
        }
    }
}
