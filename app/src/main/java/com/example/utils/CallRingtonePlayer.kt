package com.example.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.util.Log

/**
 * Plays and silences the device's default incoming-call ringtone.
 *
 * Pressing the Power button (screen off) or hardware volume keys calls
 * [onPowerButtonOrScreenOff], which silences only the ringtone without ending
 * the active call or closing the call screen.
 */
object CallRingtonePlayer {
    private const val TAG = "CallRingtonePlayer"

    private var currentRingtone: Ringtone? = null
    @Volatile
    private var isRinging: Boolean = false

    @Synchronized
    fun startDefaultRingtone(context: Context) {
        stopRingtone()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: return
            val ringtone = RingtoneManager.getRingtone(context.applicationContext, uri) ?: return
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.isLooping = true
            }
            isRinging = true
            ringtone.play()
            currentRingtone = ringtone
        } catch (error: Throwable) {
            isRinging = false
            currentRingtone = null
            Log.w(TAG, "Unable to start default ringtone", error)
        }
    }

    @Synchronized
    fun stopRingtone() {
        isRinging = false
        try {
            currentRingtone?.let { ringtone ->
                if (ringtone.isPlaying) {
                    ringtone.stop()
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Error stopping ringtone", error)
        } finally {
            currentRingtone = null
        }
    }

    @Synchronized
    fun onPowerButtonOrScreenOff() {
        // Silence only the ringtone; never end the active call or close the call screen.
        if (isRinging) {
            stopRingtone()
        }
    }

    fun isCurrentlyRinging(): Boolean = isRinging
}
