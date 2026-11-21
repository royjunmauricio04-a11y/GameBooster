package com.gamebooster.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTransitionPolicyTest {
    @Test
    fun initialAndUnchangedNetworkDoNotRequestReconnect() {
        val policy = NetworkTransitionPolicy()
        assertFalse(policy.observe("wifi:1"))
        assertFalse(policy.observe("wifi:1"))
    }

    @Test
    fun changedNetworkRequestsReconnectOnce() {
        val policy = NetworkTransitionPolicy()
        policy.reset("wifi:1")

        assertTrue(policy.observe("cell:2"))
        assertFalse(policy.observe("cell:2"))
    }

    @Test
    fun reconnectIsRequestedWhenNetworkReturnsAfterOfflinePeriod() {
        val policy = NetworkTransitionPolicy()
        policy.reset("wifi:1")

        assertFalse(policy.observe(null))
        assertTrue(policy.observe("wifi:1"))
        assertFalse(policy.observe("wifi:1"))
    }

    @Test
    fun resettingSignatureDoesNotTriggerAReconnect() {
        val policy = NetworkTransitionPolicy()
        policy.observe(null)
        policy.reset("cell:2")

        assertFalse(policy.observe("cell:2"))
    }
}
