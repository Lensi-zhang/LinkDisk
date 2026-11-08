package com.linkdisk.app.server

object WebDavPath {
    fun segments(path: String): List<String> {
        val result = path.split('/').filter { it.isNotEmpty() }
        result.forEach { segment ->
            require(segment != "." && segment != "..") { "路径不能包含相对目录" }
            require(!segment.contains('\\')) { "路径不能包含反斜杠" }
            require(segment.none { it.code < 32 || it.code == 127 }) { "路径不能包含控制字符" }
        }
        return result
    }

    fun isDescendant(candidate: List<String>, parent: List<String>): Boolean =
        candidate.size > parent.size && candidate.take(parent.size) == parent
}
