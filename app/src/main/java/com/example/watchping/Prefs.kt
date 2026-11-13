package com.example.watchping

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(val id: Long, val title: String, val text: String, val time: Long)

/** Настройки и история отправленных сообщений (хранятся на телефоне). */
object Prefs {
    private const val FILE = "entertext"
    private const val KEY_CHUNK = "chunk"
    private const val KEY_API = "api_key"
    private const val KEY_HISTORY = "history"
    private const val HISTORY_LIMIT = 300

    const val DEFAULT_CHUNK = 120
    const val MIN_CHUNK = 40
    const val MAX_CHUNK = 300

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun chunkSize(ctx: Context): Int =
        sp(ctx).getInt(KEY_CHUNK, DEFAULT_CHUNK).coerceIn(MIN_CHUNK, MAX_CHUNK)

    fun setChunkSize(ctx: Context, value: Int) {
        sp(ctx).edit().putInt(KEY_CHUNK, value.coerceIn(MIN_CHUNK, MAX_CHUNK)).apply()
    }

    fun apiKey(ctx: Context): String = sp(ctx).getString(KEY_API, "") ?: ""

    fun setApiKey(ctx: Context, value: String) {
        sp(ctx).edit().putString(KEY_API, value.trim()).apply()
    }

    fun history(ctx: Context): List<HistoryItem> {
        val raw = sp(ctx).getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryItem(o.getLong("id"), o.getString("title"), o.getString("text"), o.getLong("time"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveHistory(ctx: Context, list: List<HistoryItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("text", it.text)
                    .put("time", it.time)
            )
        }
        sp(ctx).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun addHistory(ctx: Context, title: String, text: String) {
        val now = System.currentTimeMillis()
        val updated = listOf(HistoryItem(now, title, text, now)) + history(ctx)
        saveHistory(ctx, updated.take(HISTORY_LIMIT))
    }

    fun deleteHistory(ctx: Context, id: Long) {
        saveHistory(ctx, history(ctx).filter { it.id != id })
    }

    fun clearHistory(ctx: Context) {
        saveHistory(ctx, emptyList())
    }
}
