package com.linkdisk.app.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavPathTest {
    @Test
    fun `keeps unicode spaces and plus signs`() {
        assertEquals(listOf("我的 文件", "a+b.txt"), WebDavPath.segments("/我的 文件/a+b.txt"))
    }

    @Test
    fun `removes duplicate separators`() {
        assertEquals(listOf("photos", "2026"), WebDavPath.segments("//photos///2026/"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects parent traversal`() {
        WebDavPath.segments("/photos/../private")
    }

    @Test
    fun `detects descendants without matching siblings`() {
        assertTrue(WebDavPath.isDescendant(listOf("a", "b"), listOf("a")))
        assertFalse(WebDavPath.isDescendant(listOf("abc"), listOf("a")))
        assertFalse(WebDavPath.isDescendant(listOf("a"), listOf("a")))
    }
}
