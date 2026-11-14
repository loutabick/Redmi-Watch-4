package com.example.watchping

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/** Расшифровка рукописного текста через OpenAI API. Нужен собственный ключ API. */
object OpenAiOcr {
    private const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
    private const val MODEL = "gpt-4.1-mini"
    private const val MAX_SIDE = 2400

    private const val PROMPT =
        "Ты — специалист по точной расшифровке рукописных документов. " +
            "Внимательно изучи всё изображение целиком, включая края, поля, верхнюю и нижнюю части. " +
            "Определи язык по содержимому (в первую очередь русский и английский). " +
            "Перепиши весь видимый текст дословно, сохраняя порядок, абзацы, списки и переносы строк. " +
            "Не пересказывай, не исправляй грамматику и орфографию, не переводи и не дополняй текст. " +
            "Учитывай связное рукописное письмо, сокращения, цифры, даты и знаки препинания. " +
            "Если символ или слово невозможно уверенно прочитать, используй [?] только на этом месте. " +
            "Не пропускай фрагменты, даже если почерк неровный или текст расположен под углом. " +
            "Если текста нет, верни пустой ответ. Выведи только расшифровку без пояснений."

    fun recognize(apiKey: String, bitmap: Bitmap): String {
        val scaled = scale(bitmap)
        val jpeg = ByteArrayOutputStream().also {
            scaled.compress(Bitmap.CompressFormat.JPEG, 95, it)
        }.toByteArray()
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)

        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", PROMPT))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject()
                            .put("url", "data:image/jpeg;base64,$b64")
                            .put("detail", "high")
                    )
            )

        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 4096)
            .put("temperature", 0)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))

        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(resp).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    resp.take(200)
                }
                throw IllegalStateException("ошибка $code: $msg")
            }
            val message = JSONObject(resp)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
            val contentText = message.opt("content")
            return when (contentText) {
                is String -> contentText.trim()
                is JSONArray -> {
                    val result = StringBuilder()
                    for (i in 0 until contentText.length()) {
                        val item = contentText.optJSONObject(i) ?: continue
                        if (item.optString("type") == "text") result.append(item.optString("text"))
                    }
                    result.toString().trim()
                }
                else -> ""
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun scale(src: Bitmap): Bitmap {
        val biggest = max(src.width, src.height)
        if (biggest <= MAX_SIDE) return src
        val k = MAX_SIDE.toFloat() / biggest
        return Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true)
    }
}
