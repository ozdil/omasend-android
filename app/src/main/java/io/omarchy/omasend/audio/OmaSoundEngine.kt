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
    private var soundIdReceiveChime: Int = 0
    private val loadedSounds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    init {
        initSoundPool()
    }

    private fun initSoundPool() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(audioAttributes)
            .build()

        soundPool?.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0 && sampleId != 0) {
                loadedSounds.add(sampleId)
            }
        }

        soundPool?.let { pool ->
            soundIdSend = pool.load(context, R.raw.send_whoosh, 1)
            soundIdReceive = pool.load(context, R.raw.receive_whoosh, 1)
            soundIdReceiveChime = pool.load(context, R.raw.receive_chime, 1)
        }
    }

    /**
     * Plays the samurai send vocal + katana sound (send_whoosh.wav: "OMAKASE!") when a file or clipboard is dispatched.
     */
    fun playSendSound() {
        playSound(soundIdSend, volume = 0.95f)
    }

    /**
     * Plays the samurai receive/accept vocal + katana sound (receive_whoosh.wav: "ONEGAI SHIMASU!") when a file or transfer is accepted/received.
     */
    fun playReceiveSound() {
        val targetId = if (loadedSounds.contains(soundIdReceive)) soundIdReceive else soundIdReceiveChime
        playSound(targetId, volume = 1.0f)
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
            loadedSounds.clear()
        } catch (_: Exception) {}
    }
}
