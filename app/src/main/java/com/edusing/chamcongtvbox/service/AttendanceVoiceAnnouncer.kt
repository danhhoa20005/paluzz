package com.edusing.chamcongtvbox.service

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Bộ đọc lời chào tiếng Việt offline bằng `TextToSpeech` tích hợp sẵn của Android (0 KB dung lượng APK):
 * phát giọng nói *"Xin chào [Họ tên]"* qua loa TV Box hoặc điện thoại ngay khi có người chấm công thành công.
 */
internal class AttendanceVoiceAnnouncer(
    context: Context,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val appContext = context.applicationContext
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready: Boolean = false
    @Volatile private var lastSpokenText: String = ""
    @Volatile private var lastSpokenAtMs: Long = 0L

    fun speakCheckIn(userName: String, cardId: String) {
        val text = formatGreeting(userName, cardId)
        val now = clockMillis()
        if (text == lastSpokenText && (now - lastSpokenAtMs) < MIN_REPEAT_MS) return
        lastSpokenText = text
        lastSpokenAtMs = now
        ensureInitializedAndSpeak(text)
    }

    fun shutdown() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        ready = false
    }

    private fun ensureInitializedAndSpeak(text: String) {
        val existing = tts
        if (existing != null && ready) {
            runCatching { existing.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cc_${clockMillis()}") }
            return
        }
        if (existing != null) return
        runCatching {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts ?: return@TextToSpeech
                    val vi = Locale.forLanguageTag("vi-VN")
                    val res = engine.setLanguage(vi)
                    if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                        engine.setSpeechRate(1.02f)
                    }
                    ready = true
                    runCatching { engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cc_init") }
                }
            }
        }
    }

    companion object {
        private const val MIN_REPEAT_MS = 2_000L

        /** Chuẩn hoá câu chào tiếng Việt tự nhiên, bỏ ký tự đặc biệt dư thừa. */
        fun formatGreeting(userName: String, cardId: String): String {
            val cleanName = userName.trim().replace(Regex("\\s+"), " ")
            return when {
                cleanName.isNotEmpty() -> "Xin chào $cleanName"
                cardId.isNotBlank() && cardId != "-" -> "Chấm công thành công mã $cardId"
                else -> "Chấm công thành công"
            }
        }
    }
}
