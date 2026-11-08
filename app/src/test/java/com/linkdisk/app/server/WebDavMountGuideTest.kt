package com.linkdisk.app.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebDavMountGuideTest {
    @Test
    fun `HTTP non-default port generates all platform addresses`() {
        val guide = requireNotNull(WebDavMountGuide.fromRootUrl("http://192.168.1.20:8080/"))

        assertEquals("http://192.168.1.20:8080/", guide.rootUrl)
        assertEquals("\\\\192.168.1.20@8080\\DavWWWRoot", guide.windowsUnc)
        assertEquals("dav://192.168.1.20:8080/", guide.linuxUrl)
    }

    @Test
    fun `HTTP default port uses port 80 in Windows UNC`() {
        listOf("http://dav.example.com", "http://dav.example.com/", "http://dav.example.com:80/")
            .forEach { url ->
                val guide = requireNotNull(WebDavMountGuide.fromRootUrl(url))
                assertEquals("\\\\dav.example.com@80\\DavWWWRoot", guide.windowsUnc)
            }
    }

    @Test
    fun `HTTPS default port uses SSL marker in Windows UNC`() {
        listOf("https://dav.example.com", "https://dav.example.com:443/")
            .forEach { url ->
                val guide = requireNotNull(WebDavMountGuide.fromRootUrl(url))
                assertEquals("\\\\dav.example.com@SSL\\DavWWWRoot", guide.windowsUnc)
                assertEquals("davs://dav.example.com${if (url.endsWith(":443/")) ":443" else ""}/", guide.linuxUrl)
            }
    }

    @Test
    fun `HTTPS non-default port preserves port in UNC and Linux URL`() {
        val guide = requireNotNull(WebDavMountGuide.fromRootUrl("https://dav.example.com:8443/"))

        assertEquals("https://dav.example.com:8443/", guide.rootUrl)
        assertEquals("\\\\dav.example.com@SSL@8443\\DavWWWRoot", guide.windowsUnc)
        assertEquals("davs://dav.example.com:8443/", guide.linuxUrl)
    }

    @Test
    fun `scheme is normalized and explicit default port is preserved`() {
        val guide = requireNotNull(WebDavMountGuide.fromRootUrl(" HTTP://Dav.Example.com:80 "))

        assertEquals("http://Dav.Example.com:80/", guide.rootUrl)
        assertEquals("dav://Dav.Example.com:80/", guide.linuxUrl)
        assertEquals("\\\\Dav.Example.com@80\\DavWWWRoot", guide.windowsUnc)
    }

    @Test
    fun `IPv6 brackets are preserved for URLs and converted for Windows UNC`() {
        val guide = requireNotNull(WebDavMountGuide.fromRootUrl("http://[2001:db8::1]:8080/"))

        assertEquals("http://[2001:db8::1]:8080/", guide.rootUrl)
        assertEquals("dav://[2001:db8::1]:8080/", guide.linuxUrl)
        assertEquals("\\\\2001-db8--1.ipv6-literal.net@8080\\DavWWWRoot", guide.windowsUnc)
    }

    @Test
    fun `invalid root URLs return no mount guide`() {
        val invalidUrls = listOf(
            "",
            "   ",
            "ftp://dav.example.com/",
            "https:///missing-host",
            "http://dav.example.com:70000/",
            "http://dav.example.com:/",
            "https://user:password@dav.example.com/",
            "https://dav.example.com/webdav",
            "https://dav.example.com/?download=1",
            "https://dav.example.com/#private",
        )

        invalidUrls.forEach { url ->
            assertNull(url, WebDavMountGuide.fromRootUrl(url))
        }
        assertNull(WebDavMountGuide.fromRootUrl(null))
    }

    @Test
    fun `public root address never substitutes the phone local port`() {
        val guide = requireNotNull(WebDavMountGuide.fromRootUrl("http://op12.owo.lat/"))

        assertEquals("http://op12.owo.lat/", guide.rootUrl)
        assertEquals("\\\\op12.owo.lat@80\\DavWWWRoot", guide.windowsUnc)
        assertEquals("dav://op12.owo.lat/", guide.linuxUrl)
    }
}
