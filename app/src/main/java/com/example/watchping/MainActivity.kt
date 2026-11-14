package com.example.watchping

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var titleInput: EditText
    private lateinit var messageInput: EditText
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var historyList: ListView
    private lateinit var historyEmpty: TextView
    private lateinit var chunkLabel: TextView
    private lateinit var chunkSeek: SeekBar
    private lateinit var keyInput: EditText
    private lateinit var keyStatus: TextView
    private lateinit var pages: List<View>
    private lateinit var tabs: List<TextView>
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        titleInput = findViewById(R.id.titleInput)
        messageInput = findViewById(R.id.messageInput)
        status = findViewById(R.id.status)
        preview = findViewById(R.id.preview)
        historyList = findViewById(R.id.historyList)
        historyEmpty = findViewById(R.id.historyEmpty)
        chunkLabel = findViewById(R.id.chunkLabel)
        chunkSeek = findViewById(R.id.chunkSeek)
        keyInput = findViewById(R.id.keyInput)
        keyStatus = findViewById(R.id.keyStatus)

        pages = listOf(
            findViewById(R.id.pageSend),
            findViewById(R.id.pageHistory),
            findViewById(R.id.pageSettings)
        )
        tabs = listOf(
            findViewById(R.id.tabSend),
            findViewById(R.id.tabHistory),
            findViewById(R.id.tabSettings)
        )
        tabs.forEachIndexed { i, t -> t.setOnClickListener { selectTab(i) } }

        Notifier.ensureChannel(this)
        if (!Notifier.hasPermission(this)) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // Отправка
        findViewById<Button>(R.id.sendButton).setOnClickListener { sendText() }
        findViewById<Button>(R.id.pickButton).setOnClickListener { pickImage() }
        findViewById<Button>(R.id.inkButton).setOnClickListener {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, InkActivity::class.java), REQ_INK)
        }

        // История
        adapter = HistoryAdapter()
        historyList.adapter = adapter
        historyList.setOnItemClickListener { _, _, position, _ -> showHistoryActions(adapter.getItem(position)) }
        findViewById<Button>(R.id.clearHistoryButton).setOnClickListener {
            if (Prefs.history(this).isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Очистить историю?")
                .setPositiveButton("Очистить") { _, _ ->
                    Prefs.clearHistory(this)
                    refreshHistory()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        // Настройки
        val minC = Prefs.MIN_CHUNK
        chunkSeek.max = Prefs.MAX_CHUNK - minC
        chunkSeek.progress = Prefs.chunkSize(this) - minC
        chunkLabel.text = "${Prefs.chunkSize(this)} символов"
        chunkSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + minC
                chunkLabel.text = "$value символов"
                Prefs.setChunkSize(this@MainActivity, value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        findViewById<Button>(R.id.calibrateButton).setOnClickListener {
            if (!ensurePermission()) return@setOnClickListener
            Notifier.sendCalibration(this)
            Toast.makeText(
                this,
                "Откройте уведомление на часах: последнее видимое число до «…» — ваша длина",
                Toast.LENGTH_LONG
            ).show()
        }
        keyInput.setText(Prefs.apiKey(this))
        updateKeyStatus()
        findViewById<Button>(R.id.saveKeyButton).setOnClickListener {
            Prefs.setApiKey(this, keyInput.text.toString())
            updateKeyStatus()
        }
        findViewById<Button>(R.id.systemSettingsButton).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }

        selectTab(0)
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    // ---------- вкладки ----------

    private fun selectTab(index: Int) {
        pages.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, t ->
            val on = i == index
            t.setBackgroundResource(if (on) R.drawable.bg_tab_on else R.drawable.bg_tab_off)
            t.setTextColor(if (on) Color.WHITE else Color.BLACK)
        }
        if (index == 1) refreshHistory()
    }

    // ---------- отправка ----------

    private fun ensurePermission(): Boolean {
        if (Notifier.hasPermission(this)) return true
        Toast.makeText(this, "Разрешите уведомления для Enter Text", Toast.LENGTH_LONG).show()
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        return false
    }

    private fun sendText() {
        if (!ensurePermission()) return
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "Введите текст", Toast.LENGTH_SHORT).show()
            return
        }
        val title = titleInput.text.toString().trim().ifEmpty { "Enter Text" }
        val count = Notifier.send(this, title, text, Prefs.chunkSize(this))
        Prefs.addHistory(this, title, text)
        status.text = if (count == 1) "Отправлено" else "Отправлено: $count уведомл."
    }

    // ---------- фото и рукопись ----------

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
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_PICK -> data?.data?.let { processImage(it) }
            REQ_INK -> {
                val text = data?.getStringExtra(InkActivity.EXTRA_TEXT).orEmpty()
                if (text.isNotBlank()) {
                    val old = messageInput.text.toString()
                    messageInput.setText(if (old.isBlank()) text else "$old $text")
                    messageInput.setSelection(messageInput.text.length)
                    status.text = "Рукописный текст добавлен. Проверьте и отправьте"
                }
            }
        }
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

    private fun processImage(uri: Uri) {
        status.text = "Загружаю фото…"
        thread {
            try {
                val bitmap = Ocr.loadBitmap(this, uri)
                runOnUiThread {
                    preview.setImageBitmap(bitmap)
                    preview.visibility = View.VISIBLE
                }
                val result = Ocr.recognize(this, bitmap) { msg -> runOnUiThread { status.text = msg } }
                runOnUiThread {
                    if (result.text.isBlank()) {
                        status.text = "Текст разобрать не удалось. " +
                            "Почерк лучше читает OpenAI (вкладка «Настройки») или кнопка «Рукопись». " +
                            (result.warning ?: "")
                    } else {
                        if (titleInput.text.isBlank()) titleInput.setText("Фото")
                        messageInput.setText(result.text)
                        status.text = "Распознано (${result.engine}). Проверьте текст и отправьте." +
                            (result.warning?.let { "\n$it" } ?: "")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Ошибка: ${e.message}" }
            }
        }
    }

    // ---------- история ----------

    private fun refreshHistory() {
        val items = Prefs.history(this)
        adapter.setItems(items)
        historyEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showHistoryActions(item: HistoryItem) {
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(arrayOf("Отправить ещё раз", "Вставить в поле ввода", "Удалить")) { _, which ->
                when (which) {
                    0 -> {
                        if (!ensurePermission()) return@setItems
                        Notifier.send(this, item.title, item.text, Prefs.chunkSize(this))
                        Prefs.addHistory(this, item.title, item.text)
                        refreshHistory()
                        Toast.makeText(this, "Отправлено", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        titleInput.setText(item.title)
                        messageInput.setText(item.text)
                        selectTab(0)
                    }
                    2 -> {
                        Prefs.deleteHistory(this, item.id)
                        refreshHistory()
                    }
                }
            }
            .show()
    }

    private inner class HistoryAdapter : BaseAdapter() {
        private var items: List<HistoryItem> = emptyList()
        private val fmt = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))

        fun setItems(list: List<HistoryItem>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView
                ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_history, parent, false)
            val item = items[position]
            v.findViewById<TextView>(R.id.itemTitle).text = item.title
            v.findViewById<TextView>(R.id.itemTime).text = fmt.format(Date(item.time))
            v.findViewById<TextView>(R.id.itemText).text = item.text
            return v
        }
    }

    // ---------- настройки ----------

    private fun updateKeyStatus() {
        keyStatus.text = if (Prefs.apiKey(this).isBlank())
            "Облачное распознавание выключено. Используется локальное."
        else
            "Облачное распознавание включено (OpenAI)."
    }

    companion object {
        private const val REQ_PICK = 100
        private const val REQ_INK = 101
    }
}
