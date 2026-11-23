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
 * Tracks online/offline user presence in Firebase Realtime Database at `presence/{userId}`.
 *
 * Uses `.info/connected` and `onDisconnect().setValue(...)` so the server automatically
 * marks the user offline (`online = false`, `lastSeen = ServerValue.TIMESTAMP`) on app
 * crash, process kill, or network disconnect.
 */
object UserPresenceManager {

    private const val TAG = "UserPresenceManager"
    const val DEFAULT_DATABASE_URL =
        "https://just-chat-dfc03-default-rtdb.asia-southeast1.firebasedatabase.app/"
    private val lock = Any()

    private var activeUid: String? = null
    private var connectedRef: DatabaseReference? = null
    private var connectedListener: ValueEventListener? = null
    private var activePresenceRef: DatabaseReference? = null

    fun setupUserPresence(databaseUrl: String = DEFAULT_DATABASE_URL) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        goOnline(userId = uid, databaseUrl = databaseUrl)
    }

    fun goOnline(userId: String, databaseUrl: String = DEFAULT_DATABASE_URL) {
        val cleanUid = userId.trim()
        if (cleanUid.isBlank()) return

        synchronized(lock) {
            if (activeUid == cleanUid && connectedListener != null) {
                return
            }
            detachLocked(markOffline = false)

            val database = resolveDatabase(databaseUrl) ?: return
            val presenceRef = database.getReference("presence/$cleanUid")
            val infoConnectedRef = database.getReference(".info/connected")

            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val connected = snapshot.getValue(Boolean::class.java) ?: false
                    if (!connected) return

                    // Server automatically sets offline on crash, close, or disconnect
                    presenceRef.onDisconnect().setValue(offlinePayload())
                    presenceRef.setValue(onlinePayload()).addOnFailureListener { error ->
                        Log.e(
                            TAG,
                            "Failed to write online status to Realtime Database: ${error.localizedMessage}",
                            error
                        )
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "Presence listener cancelled: ${error.message}")
                }
            }

            try {
                infoConnectedRef.addValueEventListener(listener)
                activeUid = cleanUid
                connectedRef = infoConnectedRef
                connectedListener = listener
                activePresenceRef = presenceRef
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to attach Firebase Realtime Database presence listener", t)
            }
        }
    }

    fun goOffline(userId: String, databaseUrl: String = DEFAULT_DATABASE_URL) {
        val cleanUid = userId.trim()
        if (cleanUid.isBlank()) return

        synchronized(lock) {
            if (activeUid == cleanUid) {
                detachLocked(markOffline = false)
            }
            val database = resolveDatabase(databaseUrl) ?: return
            try {
                database.getReference("presence/$cleanUid").setValue(offlinePayload())
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to mark user $cleanUid offline", t)
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
     * Observes another user's live presence at `presence/{uid}`.
     */
    fun observeUserOnlineStatus(
        uid: String,
        databaseUrl: String = DEFAULT_DATABASE_URL
    ): Flow<Boolean> = callbackFlow {
        val cleanUid = uid.trim()
        val database = resolveDatabase(databaseUrl)
        if (database == null || cleanUid.isBlank()) {
            trySend(false)
            close()
            return@callbackFlow
        }

        val presenceRef = database.getReference("presence/$cleanUid")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(snapshot.isPresenceOnline())
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Presence observation cancelled for $cleanUid: ${error.message}")
                trySend(false)
            }
        }

        try {
            presenceRef.addValueEventListener(listener)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to observe presence for $cleanUid", t)
            trySend(false)
            close()
            return@callbackFlow
        }

        awaitClose {
            try {
                presenceRef.removeEventListener(listener)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed removing presence observer for $cleanUid", t)
            }
        }
    }

    /**
     * Callback-based helper to observe a friend's live online/offline presence at
     * `presence/{friendUid}`. Returns the attached [ValueEventListener] so callers
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
        val presenceRef = database.getReference("presence/$trimmedUid")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onStatusChanged(snapshot.isPresenceOnline())
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Presence listener cancelled for $trimmedUid: ${error.message}")
            }
        }
        return try {
            presenceRef.addValueEventListener(listener)
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
            database.getReference("presence/$trimmedUid").removeEventListener(listener)
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
                activePresenceRef?.setValue(offlinePayload())
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to mark user offline during detach", t)
            }
        }
        activeUid = null
        connectedRef = null
        connectedListener = null
        activePresenceRef = null
    }

    private fun onlinePayload(): Map<String, Any> = mapOf(
        "online" to true,
        "status" to "online",
        "lastSeen" to ServerValue.TIMESTAMP,
        "lastChanged" to ServerValue.TIMESTAMP
    )

    private fun offlinePayload(): Map<String, Any> = mapOf(
        "online" to false,
        "status" to "offline",
        "lastSeen" to ServerValue.TIMESTAMP,
        "lastChanged" to ServerValue.TIMESTAMP
    )

    private fun DataSnapshot.isPresenceOnline(): Boolean {
        val boolFlag = child("online").getValue(Boolean::class.java)
        if (boolFlag != null) return boolFlag
        return child("status").getValue(String::class.java) == "online"
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
