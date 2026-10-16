package io.omarchy.omasend

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.omarchy.omasend.engine.OmaIdQrAnalyzer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer

class QrAnalyzerStrideTest {

    private val pairUri = "omasend://pair?v=2&pk=lGS8Gksaubq8R3rT1UxvWGP0NXqu9wVETLKmzhzrieg" +
        "&s=AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE&h=192.168.1.215&p=53317&n=ggs4&o=1&q=53318"

    /** Renders [text] as a QR code into a Y plane laid out like a padded camera frame. */
    private fun paddedFrame(text: String, width: Int, height: Int, rowStride: Int): ByteArray {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4)
        val size = 360
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val buf = ByteArray(rowStride * (height - 1) + width) { 0x30 }
        val ox = (width - size) / 2
        val oy = (height - size) / 2
        for (y in 0 until height) for (x in 0 until width) {
            val inQr = x - ox in 0 until size && y - oy in 0 until size
            val dark = inQr && m.get(x - ox, y - oy)
            buf[y * rowStride + x] = if (!inQr) 0xC8.toByte() else if (dark) 0x20 else 0xF0.toByte()
        }
        return buf
    }

    private fun decode(luma: ByteArray, width: Int, height: Int): String {
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
        }
        val source = PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
        return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }

    @Test
    fun pairingQrDecodesFromPaddedRows() {
        val (w, h, stride) = Triple(640, 480, 704)
        val frame = paddedFrame(pairUri, w, h, stride)
        // Treating the padded plane as tightly packed shears the image and nothing decodes.
        assertThrows(NotFoundException::class.java) { decode(frame, w, h) }
        val packed = OmaIdQrAnalyzer.packLuma(ByteBuffer.wrap(frame), stride, 1, w, h)
        assertEquals(pairUri, decode(packed, w, h))
    }

    @Test
    fun packLumaHonoursPixelStrideAndPassesTightBuffersThrough() {
        val interleaved = byteArrayOf(1, 9, 2, 9, 9, 9, 3, 9, 4)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), OmaIdQrAnalyzer.packLuma(ByteBuffer.wrap(interleaved), 6, 2, 2, 2))
        val tight = byteArrayOf(5, 6, 7, 8)
        assertArrayEquals(tight, OmaIdQrAnalyzer.packLuma(ByteBuffer.wrap(tight), 2, 1, 2, 2))
    }
}
