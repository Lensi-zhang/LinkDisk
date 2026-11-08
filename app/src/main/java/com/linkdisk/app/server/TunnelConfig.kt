package com.linkdisk.app.server

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

data class TunnelConfig(
    val localPort: Int = 8080,
    val publicUrl: String = "",
    val rawConfig: String = "",
) {
    val executableName: String
        get() = "libfrpc.so"

    internal val runtimeConfigExtension: String
        get() = if (LEGACY_COMMON_SECTION.containsMatchIn(rawConfig)) "ini" else "toml"

    internal val sensitiveValues: List<String>
        get() = RAW_TOKEN_PATTERN.findAll(rawConfig)
            .flatMap { match ->
                val quoted = match.groupValues[1]
                val singleQuoted = match.groupValues[2]
                val plain = match.groupValues[3]
                sequenceOf(quoted, decodeQuotedToken(quoted), singleQuoted, plain.trim())
            }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    fun validate() {
        require(localPort in 1024..65535) { "本地端口必须在 1024..65535 之间" }
        require(rawConfig.isNotBlank()) { "原生 frpc 配置不能为空" }
        require(rawConfig.none(::isUnsupportedControl)) { "原生 frpc 配置包含不支持的控制字符" }
        validatePublicUrl()
    }

    fun normalizedPublicUrl(): String {
        val uri = validatePublicUrl()
        return "${uri.scheme.lowercase(Locale.US)}://${uri.rawAuthority}/"
    }

    fun runtimeConfig(): String {
        validate()
        return rawConfig
    }

    override fun toString(): String =
        "TunnelConfig(localPort=$localPort, publicUrl=<redacted>, rawConfig=<redacted>)"

    private fun validatePublicUrl(): URI {
        val uri = try {
            URI(publicUrl.trim())
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException("公网 URL 格式无效")
        }
        val scheme = uri.scheme?.lowercase(Locale.US)
        require(scheme == "http" || scheme == "https") { "公网 URL 仅支持 HTTP 或 HTTPS" }
        require(!uri.host.isNullOrBlank()) { "公网 URL 必须包含有效主机名或 IP" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "公网 URL 不能包含凭据、查询参数或片段"
        }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "公网 URL 不能包含路径" }
        require(uri.port == -1 || uri.port in 1..65535) { "公网 URL 端口无效" }
        return uri
    }

    private companion object {
        val LEGACY_COMMON_SECTION = Regex("(?m)^\\s*\\[common\\]\\s*(?:[#;].*)?$")
        val RAW_TOKEN_PATTERN = Regex(
            "(?im)^\\s*(?:auth\\.)?token\\s*=\\s*" +
                "(?:\"((?:\\\\.|[^\"\\\\])*)\"|'([^']*)'|([^#\\r\\n]+))",
        )

        fun decodeQuotedToken(value: String): String = buildString {
            var index = 0
            while (index < value.length) {
                if (value[index] != '\\' || index + 1 >= value.length) {
                    append(value[index++])
                    continue
                }
                when (val escaped = value[++index]) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'b' -> append('\b')
                    'f' -> append('\u000c')
                    else -> append(escaped)
                }
                index++
            }
        }

        fun isUnsupportedControl(character: Char): Boolean =
            Character.isISOControl(character) && character != '\n' && character != '\r' && character != '\t'
    }
}
