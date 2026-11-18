package com.zdownloader.app.domain

data class ContentRange(val start: Long, val end: Long, val total: Long?) {
    companion object {
        fun parse(header: String?): ContentRange? {
            val match = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(header.orEmpty()) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = match.groupValues[3].toLongOrNull()
            if(total==null&&match.groupValues[3]!="*") return null
            if(start > end || end == Long.MAX_VALUE || total != null && end >= total) return null
            return ContentRange(start,end,total)
        }
    }
}
