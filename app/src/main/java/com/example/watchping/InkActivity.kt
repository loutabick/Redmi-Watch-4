package com.example.watchping

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions

/** Рукописный ввод: пишете строку, нажимаете «Распознать», текст добавляется в поле ниже. */
class InkActivity : Activity() {

    private lateinit var inkView: InkView
    private lateinit var inkResult: EditText
    private lateinit var inkStatus: TextView
    private lateinit var langRu: TextView
    private lateinit var langEn: TextView

    private var recognizer: DigitalInkRecognizer? = null
    private var langTag = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ink)

        inkView = findViewById(R.id.inkView)
        inkResult = findViewById(R.id.inkResult)
        inkStatus = findViewById(R.id.inkStatus)
        langRu = findViewById(R.id.langRu)
        langEn = findViewById(R.id.langEn)

        langRu.setOnClickListener { setLanguage("ru") }
        langEn.setOnClickListener { setLanguage("en-US") }
        findViewById<Button>(R.id.clearButton).setOnClickListener { inkView.clear() }
        findViewById<Button>(R.id.recognizeButton).setOnClickListener { recognize() }
        findViewById<Button>(R.id.cancelButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.doneButton).setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, inkResult.text.toString().trim()))
            finish()
        }

        setLanguage("ru")
    }

    private fun styleTabs() {
        val ruOn = langTag == "ru"
        langRu.setBackgroundResource(if (ruOn) R.drawable.bg_tab_on else R.drawable.bg_tab_off)
        langRu.setTextColor(if (ruOn) Color.WHITE else Color.BLACK)
        langEn.setBackgroundResource(if (ruOn) R.drawable.bg_tab_off else R.drawable.bg_tab_on)
        langEn.setTextColor(if (ruOn) Color.BLACK else Color.WHITE)
    }

    private fun setLanguage(tag: String) {
        if (tag == langTag && recognizer != null) return
        recognizer?.close()
        recognizer = null
        langTag = tag
        styleTabs()

        val identifier = try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)
        } catch (e: Exception) {
            null
        }
        if (identifier == null) {
            inkStatus.text = "Этот язык не поддерживается"
            return
        }
        val model = DigitalInkRecognitionModel.builder(identifier).build()
        val manager = RemoteModelManager.getInstance()
        inkStatus.text = "Проверяю языковую модель…"

        manager.isModelDownloaded(model)
            .addOnSuccessListener { downloaded ->
                if (tag != langTag) return@addOnSuccessListener
                if (downloaded) {
                    onModelReady(model, tag)
                } else {
                    inkStatus.text = "Скачиваю модель распознавания (один раз)…"
                    manager.download(model, DownloadConditions.Builder().build())
                        .addOnSuccessListener { onModelReady(model, tag) }
                        .addOnFailureListener {
                            if (tag == langTag) inkStatus.text = "Не удалось скачать модель: ${it.message}"
                        }
                }
            }
            .addOnFailureListener {
                if (tag == langTag) inkStatus.text = "Ошибка: ${it.message}"
            }
    }

    private fun onModelReady(model: DigitalInkRecognitionModel, tag: String) {
        if (tag != langTag) return
        recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        inkStatus.text = "Готово. Напишите строку и нажмите «Распознать»"
    }

    private fun recognize() {
        val r = recognizer
        if (r == null) {
            Toast.makeText(this, "Модель ещё не готова", Toast.LENGTH_SHORT).show()
            return
        }
        if (!inkView.hasInk) {
            Toast.makeText(this, "Сначала напишите что-нибудь", Toast.LENGTH_SHORT).show()
            return
        }
        inkStatus.text = "Распознаю…"
        r.recognize(inkView.buildInk())
            .addOnSuccessListener { result ->
                val best = result.candidates.firstOrNull()?.text ?: ""
                if (best.isBlank()) {
                    inkStatus.text = "Не удалось разобрать. Пишите крупнее и по одной строке"
                } else {
                    val old = inkResult.text.toString()
                    inkResult.setText(if (old.isBlank()) best else "$old $best")
                    inkResult.setSelection(inkResult.text.length)
                    inkView.clear()
                    inkStatus.text = "Добавлено. Можно писать дальше или нажать «Готово»"
                }
            }
            .addOnFailureListener { inkStatus.text = "Ошибка распознавания: ${it.message}" }
    }

    override fun onDestroy() {
        recognizer?.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TEXT = "text"
    }
}
