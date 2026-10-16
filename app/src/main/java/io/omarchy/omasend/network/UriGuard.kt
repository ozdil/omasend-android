package io.omarchy.omasend.network

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Confused-deputy guard for outgoing files (A4).
 *
 * ShareActivity is exported and accepts any EXTRA_STREAM URI. Another app could hand it
 * `file:///data/data/io.omarchy.omasend/shared_prefs/...` (PIN, session key) or a URI of one of
 * OmaSend's own providers; OmaSend would then read its own private files with its own
 * permissions and send them to a peer. Only `content:` URIs served by OTHER apps' providers may
 * be sent.
 */
object UriGuard {

    @Volatile
    private var cachedOwnAuthorities: Set<String>? = null

    /**
     * Pure rule, unit-testable without Android: [authority] may carry a `userId@` prefix
     * (cross-profile content URIs), which is stripped before comparing.
     */
    fun isSendable(scheme: String?, authority: String?, ownPackage: String, ownAuthorities: Collection<String>): Boolean {
        if (!"content".equals(scheme, ignoreCase = true)) return false
        val auth = authority?.substringAfterLast('@')?.trim()?.lowercase()
        if (auth.isNullOrEmpty()) return false
        val pkg = ownPackage.lowercase()
        if (auth == pkg || auth.startsWith("$pkg.")) return false
        return ownAuthorities.none { it.trim().lowercase() == auth }
    }

    fun isSendable(context: Context, uri: Uri): Boolean =
        isSendable(uri.scheme, uri.authority, context.packageName, ownAuthorities(context))

    /** The URIs that may NOT be sent (empty when all are fine). */
    fun rejected(context: Context, uris: List<Uri>): List<Uri> = uris.filterNot { isSendable(context, it) }

    const val REJECTED_MESSAGE = "OmaSend refused to send a file from its own private storage."

    @Suppress("DEPRECATION")
    private fun ownAuthorities(context: Context): Set<String> {
        cachedOwnAuthorities?.let { return it }
        val fromManifest = try {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS)
                .providers
                ?.flatMap { p -> p.authority?.split(';').orEmpty() }
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        // Declared in the manifest; listed explicitly in case the lookup above fails.
        val known = listOf("${context.packageName}.fileprovider", "${context.packageName}.provider")
        return (fromManifest + known).toSet().also { cachedOwnAuthorities = it }
    }
}
