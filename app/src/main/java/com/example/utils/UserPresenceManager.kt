package com.example.utils

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Tracks online/offline user presence in Firebase Realtime Database at `presence/{uid}/status`.
 *
 * Uses `.info/connected` and `onDisconnect()` so users automatically flip to `"offline"`
 * if the app closes or network connectivity drops.
 */
object UserPresenceManager {

    private const val TAG = "UserPresenceManager"
    const val DEFAULT_DATABASE_URL =
        "https://just-chat-dfc03-default-rtdb.asia-southeast1.firebasedatabase.app/"
    private val lock = Any()

    private var activeUid: String? = null
    private var connectedRef: DatabaseReference? = null
    private var connectedListener: ValueEventListener? = null
    private var activeStatusRef: DatabaseReference? = null
    private var activeLastChangedRef: DatabaseReference? = null

    fun setupUserPresence(databaseUrl: String = DEFAULT_DATABASE_URL) {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val uid = currentUser.uid

        synchronized(lock) {
            if (activeUid == uid && connectedListener != null) {
                return
            }
            detachLocked(markOffline = false)

            val database = resolveDatabase(databaseUrl) ?: return
            val statusRef = database.getReference("presence/$uid/status")
            val lastChangedRef = database.getReference("presence/$uid/lastChanged")
            val infoConnectedRef = database.getReference(".info/connected")

            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val connected = snapshot.getValue(Boolean::class.java) ?: false
                    if (!connected) return

                    // Register onDisconnect first so sudden network drops always transition to offline
                    statusRef.onDisconnect().setValue("offline")
                    lastChangedRef.onDisconnect().setValue(ServerValue.TIMESTAMP)

                    statusRef.setValue("online").addOnFailureListener { error ->
                        Log.e(
                            TAG,
                            "Failed to write online status to Realtime Database: ${error.localizedMessage}",
                            error
                        )
                    }
                    lastChangedRef.setValue(ServerValue.TIMESTAMP)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "Presence listener cancelled: ${error.message}")
                }
            }

            try {
                infoConnectedRef.addValueEventListener(listener)
                activeUid = uid
                connectedRef = infoConnectedRef
                connectedListener = listener
                activeStatusRef = statusRef
                activeLastChangedRef = lastChangedRef
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to attach Firebase Realtime Database presence listener", t)
            }
        }
    }

    /**
     * Marks the current user offline and removes the `.info/connected` listener.
     * Call before signing out while the user's Firebase Auth token is still active.
     */
    fun setOfflineAndDetach() {
        synchronized(lock) {
            detachLocked(markOffline = true)
        }
    }

    /**
     * Observes another user's live `"online"` / `"offline"` presence status.
     */
    fun observeUserOnlineStatus(
        uid: String,
        databaseUrl: String = DEFAULT_DATABASE_URL
    ): Flow<Boolean> = callbackFlow {
        val database = resolveDatabase(databaseUrl)
        if (database == null || uid.isBlank()) {
            trySend(false)
            close()
            return@callbackFlow
        }

        val statusRef = database.getReference("presence/$uid/status")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.getValue(String::class.java)
                trySend(status == "online")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Presence observation cancelled for $uid: ${error.message}")
                trySend(false)
            }
        }

        try {
            statusRef.addValueEventListener(listener)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to observe presence for $uid", t)
            trySend(false)
            close()
            return@callbackFlow
        }

        awaitClose {
            try {
                statusRef.removeEventListener(listener)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed removing presence observer for $uid", t)
            }
        }
    }

    /**
     * Callback-based helper to observe a friend's live online/offline presence at
     * `presence/{friendUid}/status`. Returns the attached [ValueEventListener] so callers
     * can remove it via [removePresenceObserver].
     */
    fun observeUserPresence(
        friendUid: String,
        onStatusChanged: (Boolean) -> Unit,
        databaseUrl: String = DEFAULT_DATABASE_URL
    ): ValueEventListener? {
        val trimmedUid = friendUid.trim()
        if (trimmedUid.isEmpty()) return null
        val database = resolveDatabase(databaseUrl) ?: return null
        val statusRef = database.getReference("presence/$trimmedUid/status")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.getValue(String::class.java)
                onStatusChanged(status == "online")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Presence listener cancelled for $trimmedUid: ${error.message}")
            }
        }
        return try {
            statusRef.addValueEventListener(listener)
            listener
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to attach presence listener for $trimmedUid", t)
            null
        }
    }

    fun removePresenceObserver(
        friendUid: String,
        listener: ValueEventListener,
        databaseUrl: String = DEFAULT_DATABASE_URL
    ) {
        val trimmedUid = friendUid.trim()
        if (trimmedUid.isEmpty()) return
        val database = resolveDatabase(databaseUrl) ?: return
        try {
            database.getReference("presence/$trimmedUid/status").removeEventListener(listener)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to detach presence listener for $trimmedUid", t)
        }
    }

    private fun detachLocked(markOffline: Boolean) {
        val listener = connectedListener
        val ref = connectedRef
        if (listener != null && ref != null) {
            try {
                ref.removeEventListener(listener)
            } catch (t: Throwable) {
                Log.w(TAG, "Error detaching .info/connected listener", t)
            }
        }
        if (markOffline) {
            try {
                activeStatusRef?.setValue("offline")
                activeLastChangedRef?.setValue(ServerValue.TIMESTAMP)
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to mark user offline during detach", t)
            }
        }
        activeUid = null
        connectedRef = null
        connectedListener = null
        activeStatusRef = null
        activeLastChangedRef = null
    }

    private fun resolveDatabase(databaseUrl: String): FirebaseDatabase? {
        return try {
            if (databaseUrl.isNotBlank()) {
                FirebaseDatabase.getInstance(databaseUrl)
            } else {
                FirebaseDatabase.getInstance()
            }
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "Firebase Realtime Database could not be initialized for $databaseUrl: ${t.localizedMessage}"
            )
            null
        }
    }
}
