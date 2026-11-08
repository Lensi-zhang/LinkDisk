package com.lazybody.linkdisk.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrpcConfigInspectorTest {
    @Test
    fun `TOML HTTP supports BOM CRLF quoted domains comments and preserves input`() {
        val token = "do-not-leak-this-token"
        val raw = "\uFEFFserverAddr = \"tunnel.example.com\"\r\n" +
            "auth.token = \"$token#still-secret\"\r\n" +
            "[[proxies]] # web tunnel\r\n" +
            "name = \"web\"\r\n" +
            "type = 'http'\r\n" +
            "localPort = 8022\r\n" +
            "customDomains = [\"files.example.com\", 'files.example.com:8443'] # domains\r\n"
        val original = raw.toCharArray().concatToString()

        val candidates = inspectSuccess(raw)

        assertEquals(raw, original)
        assertEquals(2, candidates.size)
        assertEquals(
            FrpcTunnelCandidate("web", "http", 8022, "http://files.example.com"),
            candidates[0],
        )
        assertEquals("http://files.example.com:8443", candidates[1].publicUrl)
        assertFalse(candidates.toString().contains(token))
    }

    @Test
    fun `TOML HTTPS keeps an explicit public port`() {
        val raw = """
            [[proxies]]
            name = "secure"
            type = "https"
            localPort = "8443"
            customDomains = ["secure.example.com:444"]
        """.trimIndent()

        val candidates = inspectSuccess(raw)

        assertEquals(
            listOf(FrpcTunnelCandidate("secure", "https", 8443, "https://secure.example.com:444")),
            candidates,
        )
    }

    @Test
    fun `TOML TCP uses server address remote port and default type`() {
        val raw = """
            serverAddr = "tunnel.example.com"
            [[proxies]]
            name = "dav"
            localPort = 8022
            remotePort = 16000
        """.trimIndent()

        val candidates = inspectSuccess(raw)

        assertEquals(
            listOf(FrpcTunnelCandidate("dav", "tcp", 8022, "http://tunnel.example.com:16000")),
            candidates,
        )
    }

    @Test
    fun `multiple proxies and domains remain in declaration order`() {
        val raw = """
            serverAddr = "tunnel.example.com"
            [[proxies]]
            name = "first"
            type = "http"
            localPort = 8080
            customDomains = ["one.example.com", "two.example.com"]
            [[proxies]]
            name = "second"
            type = "tcp"
            localPort = 8081
            remotePort = 18081
        """.trimIndent()

        val candidates = inspectSuccess(raw)

        assertEquals(listOf("one.example.com", "two.example.com", "tunnel.example.com"), candidates.map {
            it.publicUrl.substringAfter("://").substringBefore(':').removeSuffix("/")
        })
        assertEquals(listOf("first", "first", "second"), candidates.map { it.name })
        assertEquals(listOf(8080, 8080, 8081), candidates.map { it.localPort })
    }

    @Test
    fun `legacy INI reads common fields aliases and comma separated domains`() {
        val raw = """
            [common]
            server_addr = tunnel.example.com ; connection
            token = ini-secret
            [web]
            type = http
            local_port = 8022
            custom_domains = 'files.example.com', "backup.example.com:8080" # endpoints
            [tcp]
            type = tcp
            local_port = 8023
            remote_port = 16000
        """.trimIndent()

        val candidates = inspectSuccess(raw)

        assertEquals(3, candidates.size)
        assertEquals("http://files.example.com", candidates[0].publicUrl)
        assertEquals("http://backup.example.com:8080", candidates[1].publicUrl)
        assertEquals("http://tunnel.example.com:16000", candidates[2].publicUrl)
        assertEquals(listOf("web", "web", "tcp"), candidates.map { it.name })
    }

    @Test
    fun `official auth transport and nested proxy tables are ignored by scope`() {
        val raw = """
            serverAddr = "tunnel.example.com"
            serverPort = 7000
            [auth]
            method = "token"
            token = "secret"
            serverAddr = "invalid.example.com"
            [transport]
            tcpMux = true
            [[proxies]]
            name = "web"
            type = "http"
            localIP = "127.0.0.1"
            localPort = 8022
            customDomains = ["files.example.com"]
            [proxies.transport]
            useEncryption = true
            useCompression = true
            localPort = 9999
            customDomains = ["invalid.example.com"]
        """.trimIndent()

        val candidates = inspectSuccess(raw)

        assertEquals(
            listOf(FrpcTunnelCandidate("web", "http", 8022, "http://files.example.com")),
            candidates,
        )
    }

    @Test
    fun `missing fields return actionable failures`() {
        val missingLocalPort = failure("[[proxies]]\nname=\"web\"\ntype=\"http\"\ncustomDomains=[\"a.example.com\"]")
        val missingDomains = failure("[[proxies]]\nname=\"web\"\ntype=\"http\"\nlocalPort=8080")
        val missingServer = failure("[[proxies]]\nname=\"dav\"\nlocalPort=8080\nremotePort=18080")

        assertTrue(missingLocalPort.contains("localPort"))
        assertTrue(missingDomains.contains("customDomains"))
        assertTrue(missingServer.contains("serverAddr"))
    }

    @Test
    fun `invalid ports unsupported types and malformed structures are rejected`() {
        val invalid = listOf(
            "serverAddr=\"host.example.com\"\n[[proxies]]\nname=\"dav\"\nlocalPort=80\nremotePort=18080",
            "serverAddr=\"host.example.com\"\n[[proxies]]\nname=\"dav\"\nlocalPort=8080\nremotePort=70000",
            "[[proxies]]\nname=\"udp\"\ntype=\"udp\"\nlocalPort=8080",
            "[[proxies]]\nname=\"web\"\ntype=\"http\"\nlocalPort=8080\ncustomDomains=[\"a.example.com\"",
            "this is not a key value line",
            "",
        )

        invalid.forEach(::failure)
    }

    @Test
    fun `invalid public domains are rejected`() {
        val invalid = listOf(
            "https://user:password@example.com",
            "example.com/path",
            "example.com:99999",
            "not a host",
        )

        invalid.forEach { domain ->
            val message = failure(
                "[[proxies]]\nname=\"web\"\ntype=\"http\"\nlocalPort=8080\n" +
                    "customDomains=[\"$domain\"]",
            )
            assertTrue(message.contains("公网地址"))
        }
    }

    @Test
    fun `failures never expose tokens or original configuration values`() {
        val token = "never-print-this-token"
        val raw = "serverAddr = \"private-node.example.com\"\n" +
            "auth.token = \"$token\"\n" +
            "[[proxies]]\nname=\"private-tunnel\"\ntype=\"udp\"\nlocalPort=8080\n"

        val result = FrpcConfigInspector.inspect(raw)
        val failure = result as FrpcInspectionResult.Failure

        assertFalse(failure.message.contains(token))
        assertFalse(failure.message.contains("private-node.example.com"))
        assertFalse(failure.message.contains(raw))
        assertFalse(result.toString().contains(token))
    }

    private fun inspectSuccess(raw: String): List<FrpcTunnelCandidate> {
        return when (val result = FrpcConfigInspector.inspect(raw)) {
            is FrpcInspectionResult.Success -> result.candidates
            is FrpcInspectionResult.Failure -> throw AssertionError(result.message)
        }
    }

    private fun failure(raw: String): String {
        val result = FrpcConfigInspector.inspect(raw)
        assertTrue(result is FrpcInspectionResult.Failure)
        return (result as FrpcInspectionResult.Failure).message
    }
}
