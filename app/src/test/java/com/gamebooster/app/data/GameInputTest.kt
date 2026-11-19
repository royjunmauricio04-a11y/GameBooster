package com.gamebooster.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GameInputTest {
    private fun v(pkg: String = "com.example.game", label: String = "Game", host: String = "game.example.com", port: Int = 443) =
        GameInput.validate(pkg, label, host, port)

    @Test fun validProfile() = assertNull(v())
    @Test fun ipv4Host() = assertNull(v(host = "203.0.113.7"))
    @Test fun singleCharHost() = assertNull(v(host = "a"))
    @Test fun urlRejected() = assertNotNull(v(host = "http://game.example.com"))
    @Test fun pathRejected() = assertNotNull(v(host = "game.example.com/x"))
    @Test fun spaceRejected() = assertNotNull(v(host = "game example.com"))
    @Test fun emptyHostRejected() = assertNotNull(v(host = ""))
    @Test fun leadingDashRejected() = assertNotNull(v(host = "-bad.example.com"))
    @Test fun portBounds() { assertNotNull(v(port = 0)); assertNotNull(v(port = 65536)); assertNull(v(port = 65535)); assertNull(v(port = 1)) }
    @Test fun labelBounds() { assertNotNull(v(label = " ")); assertNotNull(v(label = "x".repeat(81))); assertNull(v(label = "x".repeat(80))) }
    @Test fun packageRules() {
        assertNotNull(v(pkg = "nodots")); assertNotNull(v(pkg = "com..x")); assertNotNull(v(pkg = "1com.x"))
        assertNotNull(v(pkg = "com.example.game; drop table"))
        assertEquals(null, v(pkg = "com.supercell.clashofclans"))
    }
}
