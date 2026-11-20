package com.gamebooster.app.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.gamebooster.app.BoosterApp
import com.gamebooster.app.data.GameProfile
import com.gamebooster.app.data.InstalledApp
import com.gamebooster.app.data.Server
import kotlinx.coroutines.flow.StateFlow

/** UI-facing facade. All connection logic lives in the process-scoped BoosterEngine. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val e = (app as BoosterApp).engine
    val state: StateFlow<UiState> = e.state
    val needConsent: StateFlow<Intent?> = e.needConsent

    fun signIn(email: String, pw: String) = e.signIn(email, pw)
    fun signUp(email: String, pw: String) = e.signUp(email, pw)
    fun signOut() = e.signOut()
    fun refreshCatalog() = e.refreshCatalog()
    fun selectGame(g: GameProfile) = e.selectGame(g)
    fun selectServer(s: Server?) = e.selectServer(s)
    fun testServers() = e.testServers()
    fun toggle() = e.toggle()
    fun onConsentResult(granted: Boolean) = e.onConsentResult(granted)
    fun loadInstalledApps() = e.loadInstalledApps()
    fun addManualGame(a: InstalledApp, host: String, port: Int) = e.addManualGame(a, host, port)
    fun removeManualGame(g: GameProfile) = e.removeManualGame(g)
    fun setGameServer(g: GameProfile, s: Server?) = e.setGameServer(g, s)
    fun setAutoReconnect(on: Boolean) = e.setAutoReconnect(on)
    fun setSmartSwitch(on: Boolean) = e.setSmartSwitch(on)
    fun setOverlay(on: Boolean) = e.setOverlay(on)
    fun clearHistory() = e.clearHistory()
    fun dismissError() = e.dismissError()
    fun report(): String = e.diagnosticsReport()
}
