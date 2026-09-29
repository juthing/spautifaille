package com.spautifaille.ui.common

import android.os.SystemClock

/**
 * Horloge monotone `SystemClock.elapsedRealtime` (celle de [com.spautifaille.domain.player.SleepTimer.At.endsAtElapsedMs]),
 * injectable pour les tests.
 */
fun interface ElapsedClock {
    fun elapsedRealtimeMs(): Long
}

object SystemElapsedClock : ElapsedClock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}
