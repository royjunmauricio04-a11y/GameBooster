package com.gamebooster.app.data

/** Validation for user-entered game profiles. Pure Kotlin so it can be unit-tested on the JVM. Mirrors the SQL CHECKs. */
object GameInput {
    private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")
    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** Returns an error message, or null when valid. */
    fun validate(packageName: String, label: String, host: String, port: Int): String? = when {
        packageName.length > 200 || !PKG.matches(packageName) -> "Invalid app package."
        label.isBlank() || label.length > 80 -> "Game name must be 1–80 characters."
        !HOST.matches(host) -> "Enter a hostname or IP only (no http://, spaces or path)."
        port !in 1..65535 -> "Port must be 1–65535."
        else -> null
    }
}
