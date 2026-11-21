package com.gamebooster.app.core

/**
 * Serializes high-level tunnel transitions. Cancellation of the underlying coroutine
 * remains the caller's responsibility; this gate prevents a second transition from
 * being admitted until the first one releases its slot.
 */
class LifecycleGate {
    enum class Operation { CONNECT, DISCONNECT, RECONNECT }

    private var active: Operation? = null

    @Synchronized
    fun tryStart(operation: Operation): Boolean {
        if (active != null) return false
        active = operation
        return true
    }

    @Synchronized
    fun finish(operation: Operation) {
        if (active == operation) active = null
    }

    @Synchronized
    fun current(): Operation? = active
}
