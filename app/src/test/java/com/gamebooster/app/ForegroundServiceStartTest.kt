package com.gamebooster.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundServiceStartTest {
    @Test
    fun returnsSuccessWhenStartRequestCompletes() {
        var called = false
        val result = requestForegroundService { called = true }

        assertTrue(called)
        assertTrue(result.isSuccess)
    }

    @Test
    fun preservesSynchronousStartFailureForTheCoordinator() {
        val failure = IllegalStateException("background start rejected")
        val result = requestForegroundService { throw failure }

        assertEquals(false, result.isSuccess)
        assertSame(failure, result.exceptionOrNull())
    }
}
