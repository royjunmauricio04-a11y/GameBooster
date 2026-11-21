package com.gamebooster.app.core

import java.net.InetAddress

/** Parses numeric DNS server addresses only; hostnames are rejected to avoid pre-tunnel DNS lookups. */
object DnsServerAddress {
    private const val ERROR = "DNS server must be an IPv4 or IPv6 literal; hostnames are rejected to prevent pre-tunnel DNS lookup."

    fun parseLiteral(value: String): InetAddress {
        val text = value.trim()
        require(text.isNotEmpty()) { ERROR }
        val bytes = if (text.indexOf(':') >= 0) parseIpv6(text) else parseIpv4(text)
        return InetAddress.getByAddress(bytes)
    }

    private fun parseIpv4(text: String): ByteArray {
        val parts = text.split('.')
        require(parts.size == 4) { ERROR }
        val octets = parts.map { part ->
            require(part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' }) { ERROR }
            require(part == "0" || part[0] != '0') { ERROR }
            val value = part.toInt()
            require(value in 0..255) { ERROR }
            value.toByte()
        }
        return octets.toByteArray()
    }

    private fun parseIpv6(text: String): ByteArray {
        val compression = text.indexOf("::")
        require(compression == text.lastIndexOf("::")) { ERROR }
        val hasCompression = compression >= 0
        val leftText = if (hasCompression) text.substring(0, compression) else text
        val rightText = if (hasCompression) text.substring(compression + 2) else ""
        val left = groups(leftText)
        val right = groups(rightText)
        val count = left.size + right.size
        require(if (hasCompression) count < 8 else count == 8) { ERROR }
        require((left + right).all { group ->
            group.length in 1..4 && group.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }) { ERROR }

        val words = IntArray(8)
        left.forEachIndexed { index, group -> words[index] = group.toInt(16) }
        val rightStart = 8 - right.size
        right.forEachIndexed { index, group -> words[rightStart + index] = group.toInt(16) }
        return ByteArray(16).also { result ->
            words.forEachIndexed { index, word ->
                result[index * 2] = (word ushr 8).toByte()
                result[index * 2 + 1] = word.toByte()
            }
        }
    }

    private fun groups(segment: String): List<String> = if (segment.isEmpty()) emptyList() else segment.split(':')
}
