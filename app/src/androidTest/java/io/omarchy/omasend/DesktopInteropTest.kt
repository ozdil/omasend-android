package io.omarchy.omasend

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.omarchy.omasend.crypto.DeviceKey
import io.omarchy.omasend.model.TransferFileInfo
import io.omarchy.omasend.network.OmaSendClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live interoperability test against a running desktop omasend-engine (protocol v2).
 * Skipped unless run with instrumentation arguments, e.g.:
 *   adb shell am instrument -w -e pairUri 'omasend://pair?...' -e desktopHost 10.0.2.2 \
 *     io.omarchy.omasend.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class DesktopInteropTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun pairSendAndClipboardAgainstDesktop() {
        val pairUri = args.getString("pairUri")
        val host = args.getString("desktopHost") ?: "10.0.2.2"
        assumeTrue("pairUri argument not provided", pairUri != null)

        // The emulator reaches the host through 10.0.2.2, so point the QR's host there.
        val offer = DeviceKey.parsePairUri(pairUri!!).getOrThrow()
        val uri = pairUri.replace("&h=${java.net.URLEncoder.encode(offer.host, "UTF-8")}&", "&h=$host&")
        val client = OmaSendClient(context)

        // 1. QR pairing: desktop key is pinned from the QR and its signature verified.
        val key = client.pairWithUri(uri).getOrThrow()
        assertEquals(offer.pk, key.pk)
        assertTrue("offer was created with --own", key.own_device)

        // 2. Signed transfer request from a verified own device is auto-accepted.
        val payload = "omasend-interop-${System.currentTimeMillis()}".toByteArray()
        val name = "omasend_interop_test.txt"
        val (token, status) = client.sendTransferRequestWithStatus(
            host, offer.port, listOf(TransferFileInfo(name, payload.size.toLong()))
        ).getOrThrow()
        assertEquals("ACCEPTED", status)

        // 3. Signed streaming upload of that transfer succeeds.
        client.uploadFileStream(host, offer.port, token, name, payload.size.toLong(), payload.inputStream()) { _, _, _ -> }
            .getOrThrow()

        // 4. Clipboard push from a verified device is accepted.
        client.sendClipboard(host, offer.port, "OmaSend test").getOrThrow()
    }
}
