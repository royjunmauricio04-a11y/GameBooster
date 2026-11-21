package com.gamebooster.app.core

/** Tracks changes in the selected validated non-VPN network; returns true when reconnection is warranted. */
class NetworkTransitionPolicy {
    private var lastSignature: String? = null
    private var wasOffline = false

    fun reset(signature: String?) {
        lastSignature = signature
        wasOffline = false
    }

    /** A null signature marks offline; the next available signature requests a reconnect. */
    fun observe(signature: String?): Boolean {
        if (signature == null) {
            wasOffline = true
            return false
        }
        val changed = wasOffline || (lastSignature != null && signature != lastSignature)
        lastSignature = signature
        wasOffline = false
        return changed
    }
}
