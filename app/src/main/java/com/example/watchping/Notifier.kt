package com.example.watchping

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Публикует обычные уведомления на телефоне. Mi Fitness пересылает их на часы,
 * и они остаются в шторке уведомлений часов.
 */
object Notifier {
    const val CHANNEL_ID = "watchping_messages"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Сообщения для часов",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления, которые пересылаются на Redmi Watch 4"
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Делит текст на части не длиннее [limit] и отправляет каждую отдельным уведомлением. */
    fun send(ctx: Context, title: String, text: String, limit: Int): Int {
        ensureChannel(ctx)
        val parts = split(text, limit)
        val base = (System.currentTimeMillis() % 1_000_000).toInt()
        parts.forEachIndexed { i, part ->
            val partTitle = if (parts.size > 1) "$title · ${i + 1}/${parts.size}" else title
            post(ctx, base + i, partTitle, part)
        }
        return parts.size
    }

    /** Одно длинное уведомление с отметками длины: видно, на каком числе часы ставят «…». */
    fun sendCalibration(ctx: Context) {
        ensureChannel(ctx)
        val body = buildString {
            var n = 10
            while (n <= 400) {
                append(n.toString().padEnd(9, '.'))
                append(' ')
                n += 10
            }
        }.trim()
        post(ctx, 999_999, "Тест длины", body)
    }

    private fun post(ctx: Context, id: Int, title: String, body: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val n = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        nm.notify(id, n)
    }

    /**
     * Режет по концам предложений, затем по пробелам, и только в крайнем случае посреди слова.
     * Каждая часть строго не длиннее [limit].
     */
    fun split(text: String, limit: Int): List<String> {
        var rest = text.trim()
        if (rest.length <= limit) return listOf(rest)
        val out = mutableListOf<String>()
        while (rest.length > limit) {
            var cut = -1
            // 1) конец предложения во второй половине окна
            var i = limit
            while (i >= limit / 2) {
                val prev = rest[i - 1]
                if ((prev == '.' || prev == '!' || prev == '?' || prev == '…' || prev == '\n') &&
                    rest[i].isWhitespace()
                ) {
                    cut = i
                    break
                }
                i--
            }
            // 2) любой пробел
            if (cut == -1) {
                i = limit
                while (i >= 1) {
                    if (rest[i].isWhitespace()) {
                        cut = i
                        break
                    }
                    i--
                }
            }
            // 3) слово длиннее лимита
            if (cut == -1) cut = limit
            val piece = rest.substring(0, cut).trim()
            if (piece.isNotEmpty()) out += piece
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}
