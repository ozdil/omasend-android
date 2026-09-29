package io.omarchy.omasend.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * OmaSend V2 Dokunsal Haptik Kompozisyon Motoru.
 *
 * Linear Resonant Actuator (LRA) ve klasik titresim motorlari icin
 * transfer baslangicinda tiklama, tamamlanmada tok darbe ve hata aninda
 * titresim uretir.
 */
object OmaSendHaptics {

    private fun getVibrator(context: Context): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Transfer basladiginda hafif mekanik tiklama hissi (0.7f).
     */
    fun performTransferStart(context: Context) {
        val vibrator = getVibrator(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f)
                    .compose()
                vibrator.vibrate(effect)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(25L)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Transfer basariyla tamamlandiginda cift vuruslu tok kapanis hissi ve akustik tamamlama sesi:
     * PRIMITIVE_THUD + PRIMITIVE_QUICK_RISE + transfer_complete.wav
     */
    fun performTransferSuccess(context: Context) {
        try {
            val mp = android.media.MediaPlayer.create(context.applicationContext, io.omarchy.omasend.R.raw.transfer_complete)
            mp?.setOnCompletionListener { it.release() }
            mp?.start()
        } catch (_: Exception) {}

        val vibrator = getVibrator(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator.areAllPrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_THUD,
                    VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
                )) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.6f, 35)
                    .compose()
                vibrator.vibrate(effect)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 40, 50, 60), -1)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Hata veya transfer reddi durumunda kesintili uyari titresimi.
     */
    fun performTransferError(context: Context) {
        val vibrator = getVibrator(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 50, 40, 50), -1)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Radar cihaz tespit edildiginde mikron duzeyinde tiklama.
     */
    fun performPeerDiscovered(context: Context) {
        val vibrator = getVibrator(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(10L)
            }
        } catch (_: Exception) {
        }
    }
}
