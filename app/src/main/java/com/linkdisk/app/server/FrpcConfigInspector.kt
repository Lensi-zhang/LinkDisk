package com.linkdisk.app.server

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

data class FrpcTunnelCandidate(
    val name: String,
    val type: String,
    val localPort: Int,
    val publicUrl: String,
)

sealed interface FrpcInspectionResult {
    data class Success(val candidates: List<FrpcTunnelCandidate>) : FrpcInspectionResult

    data class Failure(val message: String) : FrpcInspectionResult
}

object FrpcConfigInspector {
    fun inspect(rawConfig: String): FrpcInspectionResult {
        if (rawConfig.isBlank()) return FrpcInspectionResult.Failure("配置不能为空")

        return try {
            val document = parse(rawConfig)
            val candidates = document.proxies.flatMapIndexed { index, proxy ->
                buildCandidates(proxy, index, document.serverAddress)
            }
            if (candidates.isEmpty()) {
                FrpcInspectionResult.Failure("未找到代理，请检查 [[proxies]] 或 INI 代理节")
            } else {
                FrpcInspectionResult.Success(candidates)
            }
        } catch (error: InspectionException) {
            FrpcInspectionResult.Failure(error.message)
        } catch (_: Exception) {
            FrpcInspectionResult.Failure("无法识别配置结构，请检查 TOML 或 INI 格式")
        }
    }

    private fun parse(rawConfig: String): Document {
        val lines = rawConfig.removePrefix("\uFEFF").lines()
        val legacyIni = lines.any { line ->
            stripComments(line, semicolonComments = true).trim()
                .equals("[common]", ignoreCase = true)
        }
        val globalFields = mutableMapOf<String, String>()
        val proxies = mutableListOf<Proxy>()
        var currentProxy: Proxy? = null
        var ignoreFields = false

        lines.forEachIndexed { index, originalLine ->
            val lineNumber = index + 1
            val line = stripComments(originalLine, legacyIni).trim()
            if (line.isEmpty()) return@forEachIndexed

            if (line.startsWith("[")) {
                val section = parseSection(
                    line = line,
                    lineNumber = lineNumber,
                    legacyIni = legacyIni,
                    proxies = proxies,
                )
                currentProxy = section.proxy
                ignoreFields = section.ignoreFields
                return@forEachIndexed
            }
            if (ignoreFields) return@forEachIndexed

            val separator = line.indexOf('=')
            if (separator <= 0) fail("第 $lineNumber 行缺少有效的键值对")
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (key.isEmpty() || value.isEmpty()) fail("第 $lineNumber 行缺少有效的键值对")

            val target = currentProxy?.fields ?: globalFields
            if (key in RECOGNIZED_FIELDS) target[key] = value
        }

        val serverAddress = globalFields.string("serverAddr", "server_addr")
        return Document(serverAddress, proxies)
    }

    private fun parseSection(
        line: String,
        lineNumber: Int,
        legacyIni: Boolean,
        proxies: MutableList<Proxy>,
    ): Section {
        if (legacyIni) {
            val match = INI_SECTION.matchEntire(line) ?: fail("第 $lineNumber 行的节格式无效")
            val name = match.groupValues[1].trim()
            if (name.isEmpty()) fail("第 $lineNumber 行的节名称不能为空")
            if (name.equals("common", ignoreCase = true)) return Section(null, ignoreFields = false)
            val proxy = Proxy(name = name, fields = mutableMapOf()).also(proxies::add)
            return Section(proxy, ignoreFields = false)
        }

        if (TOML_PROXY_SECTION.matches(line)) {
            val proxy = Proxy(name = null, fields = mutableMapOf()).also(proxies::add)
            return Section(proxy, ignoreFields = false)
        }
        if (!TOML_SECTION.matches(line)) fail("第 $lineNumber 行的 TOML 节格式无效")
        return Section(null, ignoreFields = true)
    }

    private fun buildCandidates(
        proxy: Proxy,
        index: Int,
        serverAddress: String?,
    ): List<FrpcTunnelCandidate> {
        val label = "第 ${index + 1} 个代理"
        val name = proxy.fields.string("name")?.let { value ->
            parseScalar(value, "$label 的 name")
        } ?: proxy.name ?: fail("$label 缺少 name")
        val type = proxy.fields.string("type")?.let { value ->
            parseScalar(value, "$label 的 type").lowercase(Locale.US)
        } ?: "tcp"
        val localPort = proxy.fields.string("localPort", "local_port")?.let { value ->
            parsePort(value, "$label 的 localPort", 1024..65535)
        } ?: fail("$label 缺少 localPort")

        if (type !in SUPPORTED_TYPES) fail("$label 的 type 不受向导支持")
        return when (type) {
            "http", "https" -> {
                val rawDomains = proxy.fields.string("customDomains", "custom_domains")
                    ?: fail("$label 缺少 customDomains")
                parseDomains(rawDomains, label).map { domain ->
                    FrpcTunnelCandidate(
                        name = name,
                        type = type,
                        localPort = localPort,
                        publicUrl = publicUrl("$type://$domain", label),
                    )
                }
            }
            "tcp" -> {
                val host = serverAddress?.let { value ->
                    parseScalar(value, "serverAddr")
                } ?: fail("TCP 代理缺少顶层 serverAddr")
                val rawRemotePort = proxy.fields.string("remotePort", "remote_port")
                    ?: fail("$label 缺少 remotePort")
                val remotePort = parsePort(rawRemotePort, "$label 的 remotePort", 1..65535)
                val urlHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
                listOf(
                    FrpcTunnelCandidate(
                        name = name,
                        type = type,
                        localPort = localPort,
                        publicUrl = publicUrl("http://$urlHost:$remotePort", label),
                    ),
                )
            }
            else -> error("unreachable")
        }
    }

    private fun parseDomains(rawValue: String, label: String): List<String> {
        val value = rawValue.trim()
        val rawDomains = if (value.startsWith('[')) {
            if (!value.endsWith(']')) fail("$label 的 customDomains 格式无效")
            splitOutsideQuotes(value.substring(1, value.length - 1), label)
        } else {
            splitOutsideQuotes(value, label)
        }
        if (rawDomains.isEmpty()) fail("$label 的 customDomains 不能为空")
        return rawDomains.mapIndexed { index, rawDomain ->
            if (rawDomain.isBlank() && index == rawDomains.lastIndex) return@mapIndexed null
            parseScalar(rawDomain, "$label 的 customDomains")
        }.filterNotNull().ifEmpty { fail("$label 的 customDomains 不能为空") }
    }

    private fun splitOutsideQuotes(value: String, label: String): List<String> {
        if (value.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var quote: Char? = null
        var escaped = false
        var start = 0
        value.forEachIndexed { index, character ->
            if (escaped) {
                escaped = false
                return@forEachIndexed
            }
            if (quote == '"' && character == '\\') {
                escaped = true
                return@forEachIndexed
            }
            if (character == '"' || character == '\'') {
                if (quote == character) {
                    quote = null
                } else if (quote == null) {
                    quote = character
                }
            } else if (character == ',' && quote == null) {
                result += value.substring(start, index)
                start = index + 1
            }
        }
        if (quote != null || escaped) fail("$label 的 customDomains 引号不完整")
        result += value.substring(start)
        return result
    }

    private fun parseScalar(rawValue: String, field: String): String {
        val value = rawValue.trim()
        if (value.isEmpty()) fail("$field 不能为空")
        val quote = value.first()
        if (quote != '"' && quote != '\'') return value
        if (value.length < 2 || value.last() != quote) fail("$field 的引号不完整")

        val content = value.substring(1, value.length - 1)
        val result = StringBuilder()
        var index = 0
        while (index < content.length) {
            val character = content[index]
            if (character == quote) fail("$field 的引号格式无效")
            if (quote == '"' && character == '\\') {
                if (++index >= content.length) fail("$field 的转义格式无效")
                when (val escaped = content[index]) {
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000c')
                    '"', '\\' -> result.append(escaped)
                    else -> fail("$field 包含不支持的转义")
                }
            } else {
                result.append(character)
            }
            index++
        }
        return result.toString().ifEmpty { fail("$field 不能为空") }
    }

    private fun parsePort(rawValue: String, field: String, range: IntRange): Int {
        val value = parseScalar(rawValue, field)
        val port = value.toIntOrNull() ?: fail("$field 必须是整数端口")
        if (port !in range) fail("$field 必须在 ${range.first} 到 ${range.last} 之间")
        return port
    }

    private fun publicUrl(value: String, label: String): String {
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            fail("$label 的公网地址无效")
        }
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null ||
            uri.fragment != null || uri.rawPath.isNotEmpty() || uri.port == 0 || uri.port > 65535
        ) {
            fail("$label 的公网地址无效")
        }
        return value
    }

    private fun stripComments(line: String, semicolonComments: Boolean): String {
        var quote: Char? = null
        var escaped = false
        line.forEachIndexed { index, character ->
            if (escaped) {
                escaped = false
                return@forEachIndexed
            }
            if (quote == '"' && character == '\\') {
                escaped = true
                return@forEachIndexed
            }
            if (character == '"' || character == '\'') {
                if (quote == character) {
                    quote = null
                } else if (quote == null) {
                    quote = character
                }
            } else if (quote == null && (character == '#' || (semicolonComments && character == ';'))) {
                return line.substring(0, index)
            }
        }
        return line
    }

    private fun Map<String, String>.string(vararg keys: String): String? {
        keys.forEach { key -> this[key]?.let { return it } }
        return null
    }

    private fun fail(message: String): Nothing = throw InspectionException(message)

    private class InspectionException(override val message: String) : Exception(message)

    private data class Document(
        val serverAddress: String?,
        val proxies: List<Proxy>,
    )

    private data class Section(
        val proxy: Proxy?,
        val ignoreFields: Boolean,
    )

    private data class Proxy(
        val name: String?,
        val fields: MutableMap<String, String>,
    )

    private val INI_SECTION = Regex("^\\[([^]]+)]$")
    private val TOML_PROXY_SECTION = Regex("^\\[\\[\\s*proxies\\s*]]$")
    private val TOML_SECTION = Regex("^(\\[[A-Za-z0-9_.-]+]|\\[\\[[A-Za-z0-9_.-]+]])$")
    private val RECOGNIZED_FIELDS = setOf(
        "name",
        "type",
        "serverAddr",
        "server_addr",
        "localPort",
        "local_port",
        "remotePort",
        "remote_port",
        "customDomains",
        "custom_domains",
    )
    private val SUPPORTED_TYPES = setOf("http", "https", "tcp")
}
