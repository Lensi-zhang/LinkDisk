package com.linkdisk.app.server

import android.net.Uri
import android.util.Base64
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLConnection
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.min

class WebDavServer(
    rootDirectory: File,
    port: Int,
    private val username: String,
    private val password: String,
) : NanoHTTPD(BIND_ADDRESS, port) {
    private val root = rootDirectory

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (!isAuthorized(session)) return unauthorized()
            val segments = WebDavPath.segments(session.uri)
            when (session.method) {
                Method.OPTIONS -> options()
                Method.PROPFIND -> propFind(segments, header(session, "Depth"))
                Method.PROPPATCH -> propPatch(segments)
                Method.GET, Method.HEAD -> download(segments, header(session, "Range"))
                Method.PUT -> upload(session, segments)
                Method.MKCOL -> makeCollection(segments)
                Method.DELETE -> delete(segments)
                Method.COPY -> copyOrMove(session, segments, false)
                Method.MOVE -> copyOrMove(session, segments, true)
                Method.LOCK -> lock(segments)
                Method.UNLOCK -> emptyResponse(Status.NO_CONTENT)
                else -> textResponse(Status.METHOD_NOT_ALLOWED, "不支持该 WebDAV 方法")
            }
        } catch (error: IllegalArgumentException) {
            textResponse(Status.BAD_REQUEST, error.message ?: "请求路径无效")
        } catch (error: SecurityException) {
            Log.w(TAG, "WebDAV permission denied", error)
            textResponse(Status.FORBIDDEN, "没有访问该路径的存储权限")
        } catch (error: Exception) {
            Log.e(TAG, "WebDAV request failed: ${session.method} ${session.uri}", error)
            textResponse(Status.INTERNAL_ERROR, error.message ?: "服务器处理请求失败")
        }
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        if (username.isBlank()) return true
        val expected = "Basic " + Base64.encodeToString(
            "$username:$password".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val actual = header(session, "Authorization").orEmpty()
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            actual.toByteArray(Charsets.UTF_8),
        )
    }

    private fun unauthorized() = textResponse(Status.UNAUTHORIZED, "需要身份验证").apply {
        addHeader("WWW-Authenticate", "Basic realm=\"LinkDisk\", charset=\"UTF-8\"")
    }

    private fun options() = emptyResponse(Status.OK).apply {
        addHeader("DAV", "1, 2")
        addHeader("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK")
        addHeader("MS-Author-Via", "DAV")
    }

    private fun propFind(segments: List<String>, depth: String?): Response {
        val file = resolve(segments) ?: return textResponse(Status.NOT_FOUND, "路径不存在")
        val xml = StringBuilder(XML_HEADER)
        xml.append("<D:multistatus xmlns:D=\"DAV:\">")
        appendProperty(xml, file, segments)
        if (file.isDirectory && depth?.trim() != "0") {
            file.listFiles().orEmpty().sortedBy { it.name.lowercase(Locale.ROOT) }.forEach { child ->
                appendProperty(xml, child, segments + child.name)
            }
        }
        xml.append("</D:multistatus>")
        return newFixedLengthResponse(
            Status.MULTI_STATUS,
            "application/xml; charset=utf-8",
            xml.toString(),
        )
    }

    private fun appendProperty(xml: StringBuilder, file: File, segments: List<String>) {
        val isDirectory = file.isDirectory
        val href = buildString {
            append('/')
            append(segments.joinToString("/") { Uri.encode(it) })
            if (isDirectory && segments.isNotEmpty()) append('/')
        }
        val modified = file.lastModified()
        val displayName = if (segments.isEmpty()) "LinkDisk" else file.name
        xml.append("<D:response>")
        xml.append("<D:href>${href.xmlEscape()}</D:href>")
        xml.append("<D:propstat><D:prop>")
        xml.append("<D:displayname>${displayName.xmlEscape()}</D:displayname>")
        if (modified > 0) {
            xml.append("<D:getlastmodified>${rfc1123Date(modified)}</D:getlastmodified>")
            xml.append("<D:creationdate>${iso8601Date(modified)}</D:creationdate>")
        }
        xml.append("<D:getetag>\"${modified}-${file.length()}\"</D:getetag>")
        if (isDirectory) {
            xml.append("<D:resourcetype><D:collection/></D:resourcetype>")
            xml.append("<D:getcontenttype>httpd/unix-directory</D:getcontenttype>")
        } else {
            xml.append("<D:resourcetype/>")
            xml.append("<D:getcontentlength>${file.length()}</D:getcontentlength>")
            xml.append("<D:getcontenttype>${mimeType(file).xmlEscape()}</D:getcontenttype>")
        }
        xml.append("<D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope>")
        xml.append("<D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock>")
        xml.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
    }

    private fun propPatch(segments: List<String>): Response {
        if (resolve(segments) == null) return textResponse(Status.NOT_FOUND, "路径不存在")
        val href = "/" + segments.joinToString("/") { Uri.encode(it) }
        val xml = "$XML_HEADER<D:multistatus xmlns:D=\"DAV:\"><D:response>" +
            "<D:href>${href.xmlEscape()}</D:href><D:propstat><D:prop/>" +
            "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>"
        return newFixedLengthResponse(Status.MULTI_STATUS, "application/xml; charset=utf-8", xml)
    }

    private fun download(segments: List<String>, rangeHeader: String?): Response {
        val file = resolve(segments) ?: return textResponse(Status.NOT_FOUND, "文件不存在")
        if (file.isDirectory) return textResponse(Status.METHOD_NOT_ALLOWED, "目录不能作为文件下载")
        val length = file.length()
        val range = if (rangeHeader.isNullOrBlank()) null else parseRange(rangeHeader, length)
        if (!rangeHeader.isNullOrBlank() && range == null) {
            return textResponse(Status.RANGE_NOT_SATISFIABLE, "请求范围无效").apply {
                addHeader("Content-Range", "bytes */$length")
            }
        }
        val start = range?.start ?: 0L
        val contentLength = if (range == null) length else range.end - range.start + 1
        val input = FileInputStream(file)
        skipFully(input, start)
        val boundedInput = BoundedInputStream(input, contentLength)
        val status = if (range == null) Status.OK else Status.PARTIAL_CONTENT
        return newFixedLengthResponse(status, mimeType(file), boundedInput, contentLength).apply {
            addHeader("Accept-Ranges", "bytes")
            if (range != null) addHeader("Content-Range", "bytes ${range.start}-${range.end}/$length")
        }
    }

    private fun upload(session: IHTTPSession, segments: List<String>): Response {
        val name = segments.lastOrNull() ?: return textResponse(Status.FORBIDDEN, "不能覆盖共享根目录")
        val parent = resolve(segments.dropLast(1)) ?: return textResponse(Status.CONFLICT, "上级目录不存在")
        if (!parent.isDirectory) return textResponse(Status.CONFLICT, "上级路径不是目录")
        val existing = resolve(segments)
        if (existing?.isDirectory == true) return textResponse(Status.METHOD_NOT_ALLOWED, "不能覆盖目录")

        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val temporaryPath = files["content"]?.takeIf { it.isNotEmpty() }
        if (temporaryPath == null && contentLength(session) > 0) {
            return textResponse(Status.BAD_REQUEST, "上传内容为空")
        }
        val target = existing ?: File(parent, name)
        FileOutputStream(target).use { output ->
            if (temporaryPath != null) {
                FileInputStream(File(temporaryPath)).use { input -> input.copyTo(output) }
            }
        }
        return emptyResponse(if (existing == null) Status.CREATED else Status.NO_CONTENT)
    }

    private fun makeCollection(segments: List<String>): Response {
        if (segments.isEmpty()) return textResponse(Status.METHOD_NOT_ALLOWED, "共享根目录已存在")
        if (resolve(segments) != null) return textResponse(Status.METHOD_NOT_ALLOWED, "同名文件或目录已存在")
        val parent = resolve(segments.dropLast(1)) ?: return textResponse(Status.CONFLICT, "上级目录不存在")
        if (!parent.isDirectory) return textResponse(Status.CONFLICT, "上级路径不是目录")
        return if (File(parent, segments.last()).mkdir()) {
            emptyResponse(Status.CREATED)
        } else {
            textResponse(Status.INTERNAL_ERROR, "无法创建目录")
        }
    }

    private fun delete(segments: List<String>): Response {
        if (segments.isEmpty()) return textResponse(Status.FORBIDDEN, "不能删除共享根目录")
        val file = resolve(segments) ?: return textResponse(Status.NOT_FOUND, "路径不存在")
        return if (file.deleteRecursively()) {
            emptyResponse(Status.NO_CONTENT)
        } else {
            textResponse(Status.INTERNAL_ERROR, "删除失败")
        }
    }

    private fun copyOrMove(session: IHTTPSession, sourceSegments: List<String>, move: Boolean): Response {
        if (sourceSegments.isEmpty()) return textResponse(Status.FORBIDDEN, "不能复制或移动共享根目录")
        val source = resolve(sourceSegments) ?: return textResponse(Status.NOT_FOUND, "源路径不存在")
        val destinationHeader = header(session, "Destination")
            ?: return textResponse(Status.BAD_REQUEST, "缺少 Destination 请求头")
        val destinationUri = Uri.parse(destinationHeader)
        val rawPath = if (destinationUri.scheme.isNullOrBlank()) {
            destinationHeader.substringBefore('?').substringBefore('#')
        } else {
            destinationUri.path ?: "/"
        }
        val destinationSegments = WebDavPath.segments(rawPath)
        val destinationName = destinationSegments.lastOrNull()
            ?: return textResponse(Status.FORBIDDEN, "目标不能是共享根目录")
        if (WebDavPath.isDescendant(destinationSegments, sourceSegments)) {
            return textResponse(Status.CONFLICT, "不能将目录复制或移动到其内部")
        }
        if (sourceSegments == destinationSegments) return emptyResponse(Status.NO_CONTENT)

        val parent = resolve(destinationSegments.dropLast(1))
            ?: return textResponse(Status.CONFLICT, "目标上级目录不存在")
        if (!parent.isDirectory) return textResponse(Status.CONFLICT, "目标上级路径不是目录")
        val existing = resolve(destinationSegments)
        val overwrite = header(session, "Overwrite")?.equals("F", ignoreCase = true) != true
        if (existing != null) {
            if (!overwrite) return textResponse(Status.PRECONDITION_FAILED, "目标已存在")
            if (!existing.deleteRecursively()) return textResponse(Status.INTERNAL_ERROR, "无法覆盖目标")
        }

        val target = File(parent, destinationName)
        if (move && source.renameTo(target)) {
            return emptyResponse(if (existing == null) Status.CREATED else Status.NO_CONTENT)
        }
        if (!copyFile(source, target)) {
            target.deleteRecursively()
            return textResponse(Status.INTERNAL_ERROR, "复制失败")
        }
        if (move && !source.deleteRecursively()) {
            target.deleteRecursively()
            return textResponse(Status.INTERNAL_ERROR, "移动时无法删除源文件")
        }
        return emptyResponse(if (existing == null) Status.CREATED else Status.NO_CONTENT)
    }

    private fun copyFile(source: File, target: File): Boolean {
        if (source.isDirectory) {
            if (!target.mkdir()) return false
            return source.listFiles().orEmpty().all { child ->
                copyFile(child, File(target, child.name))
            }
        }
        return runCatching {
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            true
        }.getOrDefault(false)
    }

    private fun lock(segments: List<String>): Response {
        val token = "urn:uuid:${UUID.randomUUID()}"
        val href = "/" + segments.joinToString("/") { Uri.encode(it) }
        val xml = "$XML_HEADER<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock>" +
            "<D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope>" +
            "<D:depth>infinity</D:depth><D:owner>LinkDisk</D:owner><D:timeout>Second-3600</D:timeout>" +
            "<D:locktoken><D:href>$token</D:href></D:locktoken>" +
            "<D:lockroot><D:href>${href.xmlEscape()}</D:href></D:lockroot>" +
            "</D:activelock></D:lockdiscovery></D:prop>"
        return newFixedLengthResponse(Status.OK, "application/xml; charset=utf-8", xml).apply {
            addHeader("Lock-Token", "<$token>")
            addHeader("Timeout", "Second-3600")
        }
    }

    private fun resolve(segments: List<String>): File? {
        var current: File = root
        for (segment in segments) {
            current = File(current, segment)
        }
        return current.takeIf { it.exists() }
    }

    private fun mimeType(file: File): String =
        URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"

    private fun parseRange(value: String, length: Long): ByteRange? {
        if (length <= 0) return null
        val match = Regex("^bytes=(\\d*)-(\\d*)$").matchEntire(value.trim()) ?: return null
        val first = match.groupValues[1]
        val last = match.groupValues[2]
        val start: Long
        val end: Long
        when {
            first.isNotEmpty() -> {
                start = first.toLongOrNull() ?: return null
                end = if (last.isEmpty()) length - 1 else last.toLongOrNull() ?: return null
            }
            last.isNotEmpty() -> {
                val suffix = last.toLongOrNull() ?: return null
                if (suffix <= 0) return null
                start = (length - suffix).coerceAtLeast(0)
                end = length - 1
            }
            else -> return null
        }
        if (start >= length || end < start) return null
        return ByteRange(start, min(end, length - 1))
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (input.read() == -1) {
                throw IOException("文件长度不足")
            } else {
                remaining--
            }
        }
    }

    private fun contentLength(session: IHTTPSession): Long =
        header(session, "Content-Length")?.toLongOrNull() ?: 0L

    private fun header(session: IHTTPSession, name: String): String? =
        session.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun rfc1123Date(timestamp: Long): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(Date(timestamp))

    private fun iso8601Date(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestamp))

    private fun String.xmlEscape() = buildString {
        this@xmlEscape.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(character)
            }
        }
    }

    private fun textResponse(status: Response.Status, message: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", message)

    private fun emptyResponse(status: Response.Status): Response =
        newFixedLengthResponse(status, MIME_PLAINTEXT, ByteArrayInputStream(ByteArray(0)), 0)

    private data class ByteRange(val start: Long, val end: Long)

    private class BoundedInputStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = super.read()
            if (value != -1) remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val count = super.read(buffer, offset, min(length.toLong(), remaining).toInt())
            if (count > 0) remaining -= count
            return count
        }

        override fun available(): Int = min(super.available().toLong(), remaining).toInt()
    }

    private companion object {
        const val TAG = "LinkDiskWebDAV"
        const val BIND_ADDRESS = "0.0.0.0"
        const val XML_HEADER = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
    }
}
