package io.omarchy.omasend.engine

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer

class OmaIdQrAnalyzer(
    private val onOmaIdDetected: (String) -> Unit,
    /** Protocol v2 pairing QR codes (omasend://pair?v=2...). */
    private val onPairUriDetected: ((String) -> Unit)? = null
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        val hints = mapOf<DecodeHintType, Any>(
            DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
            DecodeHintType.CHARACTER_SET to "UTF-8",
            DecodeHintType.TRY_HARDER to true
        )
        setHints(hints)
    }

    @Volatile
    private var isPaused = false

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (isPaused) {
            imageProxy.close()
            return
        }

        try {
            val plane = imageProxy.planes.firstOrNull()
            if (plane != null) {
                val width = imageProxy.width
                val height = imageProxy.height
                // The Y plane is usually padded (rowStride > width); ZXing needs tightly packed rows.
                val data = packLuma(plane.buffer, plane.rowStride, plane.pixelStride, width, height)
                val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)

                val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                val result = reader.decodeWithState(binaryBitmap)

                if (result != null && !result.text.isNullOrBlank() && onPairUriDetected != null &&
                    io.omarchy.omasend.account.AccountLogic.isScannableLink(result.text)
                ) {
                    isPaused = true
                    onPairUriDetected.invoke(result.text.trim())
                } else if (result != null && !result.text.isNullOrBlank()) {
                    val candidate = GalleryQrDecoder.parseOmaIdFromScannedPayload(result.text)
                    if (candidate != null) {
                        isPaused = true
                        onOmaIdDetected(candidate)
                    }
                }
            }
        } catch (_: NotFoundException) {
            // Normal scan frame without QR code
        } catch (_: Exception) {
            // Format or checksum errors on partial frames
        } finally {
            reader.reset()
            imageProxy.close()
        }
    }

    fun pause() {
        isPaused = true
    }

    fun resume() {
        isPaused = false
    }

    companion object {
        /**
         * Copies a camera luminance plane into a `width * height` array, dropping row padding
         * and honouring the pixel stride. Without this, devices whose rowStride differs from the
         * image width hand ZXing a sheared image and dense codes (pairing QR) never decode.
         */
        fun packLuma(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int): ByteArray {
            val src = buffer.duplicate().apply { rewind() }
            val out = ByteArray(width * height)
            if (pixelStride == 1 && rowStride == width && src.remaining() >= out.size) {
                src.get(out)
                return out
            }
            val row = ByteArray(rowStride)
            for (y in 0 until height) {
                src.position(y * rowStride)
                val len = minOf(rowStride, src.remaining())
                src.get(row, 0, len)
                for (x in 0 until width) {
                    val i = x * pixelStride
                    if (i < len) out[y * width + x] = row[i]
                }
            }
            return out
        }
    }
}
