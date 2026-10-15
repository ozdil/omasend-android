package io.omarchy.omasend

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.omarchy.omasend.crypto.DeviceKey
import io.omarchy.omasend.network.CoreNode
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Live Phase 2 interop: sends a file and clipboard text to the paired desktop over the
 * encrypted QUIC core. Requires a prior DesktopInteropTest run (QR pairing) and
 * -e quicHost <host:port> (e.g. 10.0.2.2:53318 from the emulator).
 */
@RunWith(AndroidJUnit4::class)
class DesktopQuicInteropTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sendFileAndClipboardOverQuic() {
        val hostPort = args.getString("quicHost")
        assumeTrue("quicHost argument not provided", hostPort != null)
        val node = CoreNode.node
        assertNotNull("core node must be running in the app process", node)
        val desktop = DeviceKey.TrustStore(context).load().firstOrNull { it.own_device }
        assertNotNull("pair with the desktop first (DesktopInteropTest)", desktop)
        // Optional override: a debug receiver's endpoint id (core/examples/listen.rs).
        val pk = args.getString("quicPk") ?: desktop!!.pk

        val f = File(context.cacheDir, "quic_interop.bin")
        f.writeBytes(ByteArray(3 * 1024 * 1024 + 5) { (it * 31).toByte() })
        val id = node!!.sendFiles(pk, listOf(hostPort!!), listOf(f.absolutePath), listOf("omasend_quic_interop.bin"), "")
        assertTrue(id.isNotEmpty())
        node.sendClipboard(pk, listOf(hostPort), "OmaSend QUIC test")
        f.delete()
    }
}
