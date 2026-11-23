package com.example.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager

/**
 * Acquires a short, bounded [PowerManager.PARTIAL_WAKE_LOCK] (15 seconds max) while
 * dispatching an incoming call alert/ringtone, then releases it safely in `finally`.
 */
object CallWakeHelper {
    private const val WAKE_LOCK_TAG = "JustChat:IncomingCallWakeLock"
    private const val MAX_WAKE_DURATION_MS = 15_000L

    @SuppressLint("WakelockTimeout")
    fun wakeForIncomingCall(context: Context, onWake: () -> Unit) {
        val powerManager = context.applicationContext
            .getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager == null) {
            onWake()
            return
        }

        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply {
            setReferenceCounted(false)
        }

        try {
            wakeLock.acquire(MAX_WAKE_DURATION_MS) // 15 seconds max, battery safe
            onWake()
        } finally {
            if (wakeLock.isHeld) {
                try {
                    wakeLock.release()
                } catch (_: Throwable) {
                }
            }
        }
    }
}
