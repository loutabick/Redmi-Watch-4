package com.example.watchping

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var titleInput: EditText
    private lateinit var messageInput: EditText
    private lateinit var status: TextView
    private lateinit var preview: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        titleInput = findViewById(R.id.titleInput)
        messageInput = findViewById(R.id.messageInput)
        status = findViewById(R.id.status)
        preview = findViewById(R.id.preview)

        Notifier.ensureChannel(this)
        if (!Notifier.hasPermission(this) && Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        findViewById<Button>(R.id.sendButton).setOnClickListener { sendText() }
        findViewById<Button>(R.id.pickButton).setOnClickListener { pickImage() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }

        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** Поддержка «Поделиться» из других приложений (фото или текст). */
    private fun handleIncoming(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION")
            val uri = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            if (uri != null) processImage(uri)
        } else if (intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { messageInput.setText(it) }
        }
    }

    private fun sendText() {
        if (!Notifier.hasPermission(this)) {
            Toast.makeText(this, "Разрешите уведомления для WatchPing", Toast.LENGTH_LONG).show()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        }
        val text = messageInput.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "Введите текст", Toast.LENGTH_SHORT).show()
            return
        }
        val title = titleInput.text.toString().ifBlank { "WatchPing" }
        val count = Notifier.send(this, title, text)
        status.text = "Отправлено уведомлений: $count"
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(intent, "Выберите фото"), REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            data?.data?.let { processImage(it) }
        }
    }

    private fun processImage(uri: Uri) {
        status.text = "Загружаю фото…"
        thread {
            try {
                val bitmap = Ocr.loadBitmap(this, uri)
                runOnUiThread { preview.setImageBitmap(bitmap) }
                val text = Ocr.recognize(this, bitmap) { msg -> runOnUiThread { status.text = msg } }
                runOnUiThread {
                    if (text.isBlank()) {
                        status.text = "Текст на фото не найден"
                    } else {
                        titleInput.setText("Фото")
                        messageInput.setText(text)
                        status.text = "Текст распознан. Проверьте и нажмите «Отправить на часы»"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Ошибка: ${e.message}" }
            }
        }
    }

    companion object {
        private const val REQ_PICK = 100
    }
}
