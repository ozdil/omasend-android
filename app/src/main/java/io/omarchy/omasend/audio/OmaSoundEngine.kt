package io.omarchy.omasend.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import io.omarchy.omasend.R

class OmaSoundEngine(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var soundPool: SoundPool? = null

    private var soundIdSend: Int = 0
    private var soundIdReceive: Int = 0

    init {
        initSoundPool()
    }

    private fun initSoundPool() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(audioAttributes)
            .build()

        soundPool?.let { pool ->
            soundIdSend = pool.load(context, R.raw.send_whoosh, 1)
            soundIdReceive = pool.load(context, R.raw.receive_whoosh, 1)
        }
    }

    fun playSendSound() {
        playSound(soundIdSend, volume = 0.85f)
    }

    fun playReceiveSound() {
        playSound(soundIdReceive, volume = 0.85f)
    }

    private val prefs = context.getSharedPreferences("omasend_prefs", Context.MODE_PRIVATE)

    fun isSoundEnabled(): Boolean {
        return prefs.getBoolean("sound_effects_enabled", true)
    }

    fun setSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("sound_effects_enabled", enabled).apply()
    }

    private fun playSound(soundId: Int, volume: Float) {
        if (soundId == 0 || !isSoundEnabled()) return

        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
            return
        }

        try {
            soundPool?.play(
                soundId,
                volume,
                volume,
                1,
                0,
                1.0f
            )
        } catch (_: Exception) {}
    }

    fun release() {
        try {
            soundPool?.release()
            soundPool = null
        } catch (_: Exception) {}
    }
}
