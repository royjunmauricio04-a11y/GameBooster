package com.gamebooster.app.vpn

import com.gamebooster.app.data.Registration
import com.gamebooster.app.data.Server
import com.wireguard.crypto.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardConfigTest {
    @Test
    fun configCopiesPackageListAndDoesNotExposePrivateKey() {
        val keys = KeyPair()
        val privateKey = keys.privateKey.toBase64()
        val packages = mutableListOf("com.example.game")
        val config = WireGuardConfig(
            server = Server("server-1", "Test", "", "203.0.113.1", 51820, "server-public-key", "https://gateway.example", 443),
            registration = Registration("10.8.0.2", "1.1.1.1"),
            keys = keys,
            packages = packages
        )

        packages += "com.example.other"

        assertEquals(listOf("com.example.game"), config.packages)
        assertTrue(config.toString().contains("serverId=server-1"))
        assertFalse(config.toString().contains(privateKey))
    }
}
