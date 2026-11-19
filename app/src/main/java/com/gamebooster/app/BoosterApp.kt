package com.gamebooster.app

import android.app.Application
import com.gamebooster.app.ui.BoosterEngine

/** Holds the engine at process scope so tunnel monitoring/reconnect outlive any Activity. */
class BoosterApp : Application() {
    val engine: BoosterEngine by lazy { BoosterEngine(this) }
}
