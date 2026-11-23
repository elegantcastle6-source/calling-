package com.example.utils

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * Persists user preferences for incoming call ringtones (enabled/muted toggle and
 * custom system ringtone URI/title picked via Android's [RingtoneManager] chooser).
 */
object CallRingtoneSettings {
    private const val PREFS_NAME = "call_ringtone_prefs"
    private const val KEY_RINGTONE_ENABLED = "ringtone_enabled"
    private const val KEY_RINGTONE_URI = "selected_ringtone_uri"
    private const val KEY_RINGTONE_TITLE = "selected_ringtone_title"
    private const val DEFAULT_TITLE = "Default Ringtone"

    fun isRingtoneEnabled(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RINGTONE_ENABLED, true)
    }

    fun setRingtoneEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RINGTONE_ENABLED, enabled)
            .apply()
        if (!enabled) {
            CallRingtonePlayer.stopRingtone()
        }
    }

    fun getSavedRingtoneUri(context: Context): Uri? {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RINGTONE_URI, null)
            ?.takeIf { it.isNotBlank() }
        return raw?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }

    fun getSavedRingtoneTitle(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_RINGTONE_TITLE, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_TITLE
    }

    fun savePickedRingtone(context: Context, uri: Uri?) {
        val appContext = context.applicationContext
        val title = if (uri == null) {
            DEFAULT_TITLE
        } else {
            runCatching {
                RingtoneManager.getRingtone(appContext, uri)?.getTitle(appContext)
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Custom Ringtone"
        }
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RINGTONE_URI, uri?.toString())
            .putString(KEY_RINGTONE_TITLE, title)
            .apply()
    }

    fun createRingtonePickerIntent(context: Context): Intent {
        val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val existingUri = getSavedRingtoneUri(context) ?: defaultUri

        return Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingUri)
            if (defaultUri != null) {
                putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, defaultUri)
            }
        }
    }
}

/**
 * Plays and silences the configured (or device default) incoming-call ringtone.
 *
 * Respects [CallRingtoneSettings.isRingtoneEnabled] and [CallRingtoneSettings.getSavedRingtoneUri].
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
        if (!CallRingtoneSettings.isRingtoneEnabled(context)) return

        val appContext = context.applicationContext
        try {
            val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val savedUri = CallRingtoneSettings.getSavedRingtoneUri(appContext)
            val targetUri = savedUri ?: defaultUri ?: return

            val ringtone = RingtoneManager.getRingtone(appContext, targetUri)
                ?: defaultUri?.let { RingtoneManager.getRingtone(appContext, it) }
                ?: return

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
            Log.w(TAG, "Unable to start incoming call ringtone", error)
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
