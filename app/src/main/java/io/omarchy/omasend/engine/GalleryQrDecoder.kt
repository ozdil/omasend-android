package io.omarchy.omasend.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import io.omarchy.omasend.crypto.OmaIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

object GalleryQrDecoder {

    /**
     * Decodes a QR code or barcode containing an OmaID from a local media URI.
     */
    suspend fun decodeOmaIdFromUri(context: Context, uri: Uri): Result<String> =
        decodeTextFromUri(context, uri).mapCatching { rawText ->
            parseOmaIdFromScannedPayload(rawText) ?: throw IllegalArgumentException("No valid OmaID in: $rawText")
        }

    /**
     * Decodes the raw text of the QR code in a local media URI (an OmaID or a pairing link).
     */
    suspend fun decodeTextFromUri(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeStream(inputStream, null, options)
            } ?: throw IllegalStateException("Could not decode image: $uri")

            try {
                val intArray = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(intArray, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

                val source = RGBLuminanceSource(bitmap.width, bitmap.height, intArray)
                val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

                val hints = mapOf(
                    DecodeHintType.TRY_HARDER to true,
                    DecodeHintType.CHARACTER_SET to "UTF-8"
                )
                val reader = MultiFormatReader().apply { setHints(hints) }
                val result = reader.decode(binaryBitmap)

                result.text?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("Empty QR code")
            } finally {
                bitmap.recycle()
            }
        }
    }

    /**
     * Parses and extracts a valid 16-digit OmaID from plain text, deep links, or web URLs.
     */
    fun parseOmaIdFromScannedPayload(payload: String): String? {
        val trimmed = payload.trim()

        // 1. Check URI schemas: omasend://identity/<id>, omasend://pair?id=<id>, https://omasend.../pair#id=<id>
        if (trimmed.startsWith("omasend://") || trimmed.startsWith("omarchy://") || trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val idParamMatch = Regex("""[?&#]id=([0-9A-Za-z\-]+)""").find(trimmed)
            if (idParamMatch != null) {
                val candidate = idParamMatch.groupValues[1]
                if (OmaIdentity.isValid(candidate)) {
                    return OmaIdentity.format(candidate)
                }
            }

            // Path based: omasend://identity/4921-8374-9281-7462
            val pathCandidate = trimmed.substringAfterLast("/")
            if (OmaIdentity.isValid(pathCandidate)) {
                return OmaIdentity.format(pathCandidate)
            }
        }

        // 2. Direct 16-digit format or Luhn formatted string
        if (OmaIdentity.isValid(trimmed)) {
            return OmaIdentity.format(trimmed)
        }

        // 3. Fallback: Search for any 16-digit Luhn sequence in the text
        val matches = Regex("""\b([0-9]{4}[-\s]?[0-9]{4}[-\s]?[0-9]{4}[-\s]?[0-9]{4})\b""").findAll(trimmed)
        for (m in matches) {
            val candidate = m.value
            if (OmaIdentity.isValid(candidate)) {
                return OmaIdentity.format(candidate)
            }
        }

        return null
    }
}
