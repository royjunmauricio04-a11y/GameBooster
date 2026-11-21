package com.gamebooster.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AuthCallbackTokensTest {
    @Test fun readsTokensFromSupabaseFragment() {
        val tokens = parseAuthCallbackTokens(
            "com.gamebooster.app://auth/callback#access_token=access123&refresh_token=refresh456&type=signup"
        )
        assertEquals("access123", tokens.accessToken)
        assertEquals("refresh456", tokens.refreshToken)
    }

    @Test fun readsTokensFromQuery() {
        val tokens = parseAuthCallbackTokens(
            "com.gamebooster.app://auth/callback?access_token=access123&refresh_token=refresh456"
        )
        assertEquals("access123", tokens.accessToken)
        assertEquals("refresh456", tokens.refreshToken)
    }

    @Test fun rejectsOtherHostsAndMissingTokens() {
        listOf(
            "com.gamebooster.app://attacker/callback#access_token=a&refresh_token=b",
            "com.gamebooster.app://auth/callback#error=access_denied&error_description=Email+not+confirmed",
            "com.gamebooster.app://auth/callback#access_token=only-one-token"
        ).forEach { callback ->
            try { parseAuthCallbackTokens(callback); fail("Expected callback rejection for $callback") }
            catch (_: ApiException) { }
        }
    }
}
