package com.example.watchping

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Публикует обычные уведомления на телефоне. Mi Fitness пересылает их на часы
 * и они остаются в шторке уведомлений часов, пока вы их не смахнёте.
 */
object Notifier {
    const val CHANNEL_ID = "watchping_messages"

    // Длинный текст на экране часов обрезается, поэтому делим на части.
    private const val MAX_CHUNK = 350

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

    /** Возвращает количество отправленных уведомлений. */
    fun send(ctx: Context, title: String, text: String): Int {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val parts = split(text.trim())
        val base = (System.currentTimeMillis() % 1_000_000).toInt()

        parts.forEachIndexed { i, part ->
            val partTitle = if (parts.size > 1) "$title (${i + 1}/${parts.size})" else title
            val n = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(partTitle)
                .setContentText(part)
                .setStyle(Notification.BigTextStyle().bigText(part))
                .setAutoCancel(false)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build()
            nm.notify(base + i, n)
        }
        return parts.size
    }

    private fun split(text: String): List<String> {
        if (text.length <= MAX_CHUNK) return listOf(text)
        val result = mutableListOf<String>()
        var current = StringBuilder()
        for (word in text.split(Regex("(?<=\\s)"))) {
            if (current.length + word.length > MAX_CHUNK && current.isNotEmpty()) {
                result += current.toString().trim()
                current = StringBuilder()
            }
            current.append(word)
        }
        if (current.isNotBlank()) result += current.toString().trim()
        return result
    }
}
