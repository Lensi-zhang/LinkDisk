package com.linkdisk.app.server

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

data class WebDavMountGuide(
    val rootUrl: String,
    val windowsUnc: String,
    val linuxUrl: String,
) {
    companion object {
        fun fromRootUrl(value: String?): WebDavMountGuide? {
            val uri = try {
                URI(value?.trim().orEmpty())
            } catch (_: URISyntaxException) {
                return null
            }
            val scheme = uri.scheme?.lowercase(Locale.US)
            val host = uri.host
            val authority = uri.rawAuthority
            if (scheme != "http" && scheme != "https") return null
            if (host.isNullOrBlank() || authority.isNullOrBlank() || authority.endsWith(':')) return null
            if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
            if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null
            if (uri.port == -1 || uri.port in 1..65535) Unit else return null

            val rootUrl = "$scheme://$authority/"
            val port = if (uri.port == -1) {
                if (scheme == "http") 80 else 443
            } else {
                uri.port
            }
            val windowsSuffix = when {
                scheme == "https" && port == 443 -> "@SSL"
                scheme == "https" -> "@SSL@$port"
                else -> "@$port"
            }
            val windowsHost = if (host.contains(':')) {
                host.removeSurrounding("[", "]")
                    .replace("%25", "s")
                    .replace('%', 's')
                    .replace(':', '-') + ".ipv6-literal.net"
            } else {
                host
            }
            return WebDavMountGuide(
                rootUrl = rootUrl,
                windowsUnc = "\\\\$windowsHost$windowsSuffix\\DavWWWRoot",
                linuxUrl = "${if (scheme == "https") "davs" else "dav"}://$authority/",
            )
        }
    }
}
