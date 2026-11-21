package com.gamebooster.app.core

import java.net.Inet4Address
import java.net.Inet6Address
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsServerAddressTest {
    @Test
    fun parsesIpv4WithoutNameResolution() {
        val address = DnsServerAddress.parseLiteral("1.1.1.1")
        assertTrue(address is Inet4Address)
        assertArrayEquals(byteArrayOf(1, 1, 1, 1), address.address)
    }

    @Test
    fun parsesCompressedIpv6Literal() {
        val address = DnsServerAddress.parseLiteral("2606:4700:4700::1111")
        assertTrue(address is Inet6Address)
        assertEquals(16, address.address.size)
        assertEquals(0x26.toByte(), address.address[0])
        assertEquals(0x11.toByte(), address.address[15])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHostnames() {
        DnsServerAddress.parseLiteral("resolver.example.com")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangeIpv4Octets() {
        DnsServerAddress.parseLiteral("1.1.1.256")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedIpv6Groups() {
        DnsServerAddress.parseLiteral("2001:db8::gg")
    }
}
