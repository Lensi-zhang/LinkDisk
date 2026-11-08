package com.linkdisk.app.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelConfigTest {
    @Test
    fun `native config always uses the official frpc executable`() {
        val config = TunnelConfig()
        assertEquals("libfrpc.so", config.executableName)
        assertEquals("toml", config.runtimeConfigExtension)
    }

    @Test
    fun `native TOML is preserved and HTTP or HTTPS public URLs are accepted`() {
        val rawConfig = "serverAddr = \"tunnel.example.com\"\nauth.token = \"panel-secret\"\n"
        val http = config(publicUrl = "http://node.example.com:8080/", rawConfig = rawConfig)
        val https = config(publicUrl = "https://files.example.com", rawConfig = rawConfig)

        http.validate()
        https.validate()
        assertEquals(rawConfig, http.runtimeConfig())
        assertEquals("http://node.example.com:8080/", http.normalizedPublicUrl())
        assertEquals("https://files.example.com/", https.normalizedPublicUrl())
        assertEquals(listOf("panel-secret"), http.sensitiveValues)
    }

    @Test
    fun `legacy common section selects INI without changing the config`() {
        val rawConfig = "[common]\nserver_addr = tunnel.example.com\ntoken = plain-secret\n"
        val config = config(rawConfig = rawConfig)

        assertEquals("ini", config.runtimeConfigExtension)
        assertEquals(rawConfig, config.runtimeConfig())
    }

    @Test
    fun `public URL rejects credentials paths queries and fragments`() {
        val invalidUrls = listOf(
            "ftp://files.example.com/",
            "https://user:password@files.example.com/",
            "https://files.example.com/webdav",
            "https://files.example.com/?download=1",
            "https://files.example.com/#private",
            "https:///missing-host",
        )
        invalidUrls.forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                config(publicUrl = url).validate()
            }
        }
    }

    @Test
    fun `invalid port blank config and control characters are rejected`() {
        val invalidConfigs = listOf(
            config(localPort = 80),
            config(rawConfig = ""),
            config(rawConfig = "   \n"),
            config(rawConfig = "serverAddr = \"bad\u0000host\""),
        )
        invalidConfigs.forEach { config ->
            assertThrows(IllegalArgumentException::class.java) { config.validate() }
        }
    }

    @Test
    fun `config strings and validation failures never expose native tokens`() {
        val token = "do-not-leak-this-token"
        val config = config(
            publicUrl = "https://invalid.example.com/path",
            rawConfig = "auth.token = \"$token\"",
        )
        val error = assertThrows(IllegalArgumentException::class.java) { config.validate() }

        assertFalse(config.toString().contains(token))
        assertFalse(config.toString().contains(config.rawConfig))
        assertFalse(error.message.orEmpty().contains(token))
    }

    @Test
    fun `native config redacts quoted escaped and unquoted tokens`() {
        val config = config(
            rawConfig = "token = plain-secret\n" +
                "auth.token = \"double-secret\"\n" +
                "token = 'single-secret'\n" +
                "auth.token = \"escaped\\tsecret\"\n",
        )
        assertTrue(config.sensitiveValues.contains("plain-secret"))
        assertTrue(config.sensitiveValues.contains("double-secret"))
        assertTrue(config.sensitiveValues.contains("single-secret"))
        assertTrue(config.sensitiveValues.contains("escaped\\tsecret"))
        assertTrue(config.sensitiveValues.contains("escaped\tsecret"))
    }

    private fun config(
        publicUrl: String = "https://files.example.com/",
        rawConfig: String = "serverAddr = \"tunnel.example.com\"\nauth.token = \"panel-secret\"\n",
        localPort: Int = 8080,
    ) = TunnelConfig(
        localPort = localPort,
        publicUrl = publicUrl,
        rawConfig = rawConfig,
    )
}
