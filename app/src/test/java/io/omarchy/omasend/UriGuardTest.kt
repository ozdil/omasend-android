package io.omarchy.omasend

import io.omarchy.omasend.network.UriGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UriGuardTest {
    private val pkg = "io.omarchy.omasend"
    private val own = listOf("io.omarchy.omasend.fileprovider", "io.omarchy.omasend.provider")

    private fun ok(scheme: String?, authority: String?) = UriGuard.isSendable(scheme, authority, pkg, own)

    @Test
    fun fileUrisAreRejected() {
        assertFalse(ok("file", null))
        assertFalse(ok("file", ""))
        assertFalse(ok("FILE", "localhost"))
    }

    @Test
    fun ownProvidersAreRejected() {
        assertFalse(ok("content", "io.omarchy.omasend.fileprovider"))
        assertFalse(ok("content", "io.omarchy.omasend.provider"))
        assertFalse(ok("content", "IO.OMARCHY.OMASEND.FILEPROVIDER"))
        // cross-profile form: content://10@authority/...
        assertFalse(ok("content", "10@io.omarchy.omasend.fileprovider"))
        // any authority in our package namespace
        assertFalse(ok("content", "io.omarchy.omasend.something"))
        assertFalse(ok("content", "io.omarchy.omasend"))
    }

    @Test
    fun otherSchemesAndBlankAuthoritiesAreRejected() {
        assertFalse(ok("android.resource", "io.omarchy.omasend"))
        assertFalse(ok("http", "example.com"))
        assertFalse(ok(null, "media"))
        assertFalse(ok("content", null))
        assertFalse(ok("content", ""))
    }

    @Test
    fun otherAppsContentUrisAreAllowed() {
        assertTrue(ok("content", "media"))
        assertTrue(ok("content", "com.android.providers.downloads.documents"))
        assertTrue(ok("content", "com.google.android.apps.photos.contentprovider"))
        assertTrue(ok("content", "0@media"))
        // a lookalike package that merely shares a prefix is a different app
        assertTrue(ok("content", "io.omarchy.omasendfake.provider"))
    }
}
