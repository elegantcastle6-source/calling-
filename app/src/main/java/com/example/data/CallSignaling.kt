package com.example.data

import android.content.Context
import androidx.annotation.Keep
import com.example.R
import com.example.model.CallType
import com.example.utils.buildChatRoomId
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Firestore document model stored at `/calls/{chatRoomId}` for real-time two-user call signaling.
 * Uses `@DocumentId` (never written in document maps) and `@ServerTimestamp` for server-side ordering.
 */
@Keep
@IgnoreExtraProperties
data class CallInvite(
    @DocumentId val id: String = "",
    val chatRoomId: String = "",
    val channelName: String = "",
    val callerUid: String = "",
    val caller: String = "",
    val callerName: String = "",
    val callee: String = "",
    val participantEmails: List<String> = emptyList(),
    val callType: String = CallType.AUDIO.name,
    val status: String = STATUS_CALLING,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val updatedAtMillis: Long
        get() = (updatedAt ?: createdAt ?: Timestamp.now()).toDate().time

    fun parsedCallType(): CallType =
        enumValues<CallType>().firstOrNull { it.name == callType } ?: CallType.AUDIO

    companion object {
        const val STATUS_CALLING = "calling"
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_ENDED = "ended"
    }
}

/**
 * Coordinates real-time peer-to-peer call invitations (`calling` -> `accepted` -> `ended`)
 * in the provisioned named Cloud Firestore database under `/calls/{chatRoomId}`.
 */
class CallSignaling(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(expectedUserId: String? = null): String {
        val uid = auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before signaling calls.")
        if (expectedUserId != null) {
            check(uid == expectedUserId) { "Signed-in user changed; refusing cross-user call signaling." }
        }
        return uid
    }

    /**
     * Initiates a call invite at `/calls/{chatRoomId}` and returns the active [CallInvite]
     * containing the Agora [CallInvite.channelName] both participants will join.
     */
    suspend fun startCall(
        currentUserId: String,
        caller: String,
        callerName: String,
        callee: String,
        callType: CallType = CallType.AUDIO
    ): Result<CallInvite> {
        val normalizedCaller = (auth.currentUser?.email ?: caller).trim().lowercase()
        val normalizedCallee = callee.trim().lowercase()
        if (!isValidEmailFormat(normalizedCaller) || !isValidEmailFormat(normalizedCallee)) {
            return Result.failure(
                IllegalArgumentException("Valid caller and callee Gmail addresses are required.")
            )
        }

        val chatRoomId = buildChatRoomId(normalizedCaller, normalizedCallee)
        val channelName = buildCallChannelName(chatRoomId, normalizedCaller, normalizedCallee)
        val safeCallerName = callerName.trim()
            .take(60)
            .ifBlank {
                normalizedCaller.substringBefore("@")
                    .replace(".", " ")
                    .replaceFirstChar { it.uppercase() }
                    .take(60)
                    .ifBlank { "Just Chat User" }
            }
        val participantEmails = listOf(normalizedCaller, normalizedCallee).sorted()
        val docPath = "calls/$chatRoomId"

        return try {
            val verifiedUid = requireUserId(currentUserId)
            val docRef = db.collection(COLLECTION_CALLS).document(chatRoomId)
            val existing = docRef.get().await()

            if (!existing.exists()) {
                val createPayload = mapOf(
                    "chatRoomId" to chatRoomId,
                    "channelName" to channelName,
                    "callerUid" to verifiedUid,
                    "caller" to normalizedCaller,
                    "callerName" to safeCallerName,
                    "callee" to normalizedCallee,
                    "participantEmails" to participantEmails,
                    "callType" to callType.name,
                    "status" to CallInvite.STATUS_CALLING,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(createPayload).await()
            } else {
                // Close any stale non-ended session cleanly before starting a fresh ring
                if (existing.getString("status") != CallInvite.STATUS_ENDED) {
                    docRef.update(
                        mapOf(
                            "status" to CallInvite.STATUS_ENDED,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
                val restartPayload = mapOf(
                    "channelName" to channelName,
                    "callerUid" to verifiedUid,
                    "caller" to normalizedCaller,
                    "callerName" to safeCallerName,
                    "callee" to normalizedCallee,
                    "callType" to callType.name,
                    "status" to CallInvite.STATUS_CALLING,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(restartPayload).await()
            }

            Result.success(
                CallInvite(
                    id = chatRoomId,
                    chatRoomId = chatRoomId,
                    channelName = channelName,
                    callerUid = verifiedUid,
                    caller = normalizedCaller,
                    callerName = safeCallerName,
                    callee = normalizedCallee,
                    participantEmails = participantEmails,
                    callType = callType.name,
                    status = CallInvite.STATUS_CALLING,
                    createdAt = Timestamp.now(),
                    updatedAt = Timestamp.now()
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    /**
     * Marks a ringing call as `accepted` by the callee.
     */
    suspend fun acceptCall(currentUserId: String, chatRoomId: String): Result<Unit> {
        val safeRoomId = chatRoomId.trim()
        if (safeRoomId.isEmpty()) {
            return Result.failure(IllegalArgumentException("Chat room ID is required."))
        }
        val docPath = "calls/$safeRoomId"
        return try {
            requireUserId(currentUserId)
            db.collection(COLLECTION_CALLS)
                .document(safeRoomId)
                .update(
                    mapOf(
                        "status" to CallInvite.STATUS_ACCEPTED,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    /**
     * Marks an active or ringing call as `ended`. Succeeds silently if the call is already ended
     * or does not exist so duplicate hang-up actions never surface spurious errors.
     */
    suspend fun endCall(currentUserId: String, chatRoomId: String): Result<Unit> {
        val safeRoomId = chatRoomId.trim()
        if (safeRoomId.isEmpty()) return Result.success(Unit)
        val docPath = "calls/$safeRoomId"
        return try {
            requireUserId(currentUserId)
            val docRef = db.collection(COLLECTION_CALLS).document(safeRoomId)
            val existing = docRef.get().await()
            if (existing.exists() && existing.getString("status") != CallInvite.STATUS_ENDED) {
                docRef.update(
                    mapOf(
                        "status" to CallInvite.STATUS_ENDED,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    /**
     * Observes real-time signaling state transitions for a single call room `/calls/{chatRoomId}`.
     */
    fun observeCall(currentUserId: String, chatRoomId: String): Flow<CallInvite?> {
        val safeRoomId = chatRoomId.trim()
        if (safeRoomId.isEmpty()) return flowOf(null)
        val path = "calls/$safeRoomId"
        return db.collection(COLLECTION_CALLS)
            .document(safeRoomId)
            .snapshots()
            .map { snapshot ->
                if (snapshot.exists()) {
                    snapshot.toObject(
                        CallInvite::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                } else {
                    null
                }
            }
            .catch { error ->
                val firestoreError = unwrapFirestoreError(error)
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == currentUserId) {
                    handleFirestoreError(firestoreError ?: error, OperationType.GET, path)
                }
                throw error
            }
    }

    /**
     * Observes incoming ringing calls directed to the signed-in user across all peers,
     * filtered by `participantEmails` to match zero-trust Firestore list rules.
     */
    fun observeIncomingCalls(
        currentUserId: String,
        currentUserEmail: String
    ): Flow<List<CallInvite>> {
        val normalizedMyEmail = (auth.currentUser?.email ?: currentUserEmail).trim().lowercase()
        if (!isValidEmailFormat(normalizedMyEmail)) {
            return flowOf(emptyList())
        }
        return db.collection(COLLECTION_CALLS)
            .whereArrayContains("participantEmails", normalizedMyEmail)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    CallInvite::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                    .filter { invite ->
                        invite.status == CallInvite.STATUS_CALLING &&
                            invite.callee.equals(normalizedMyEmail, ignoreCase = true) &&
                            invite.callerUid != currentUserId
                    }
                    .sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                val firestoreError = unwrapFirestoreError(error)
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == currentUserId) {
                    handleFirestoreError(firestoreError ?: error, OperationType.LIST, COLLECTION_CALLS)
                }
                throw error
            }
    }

    companion object {
        private const val COLLECTION_CALLS = "calls"
        private const val MAX_AGORA_CHANNEL_LENGTH = 64
        private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        private val CHANNEL_REGEX = Regex("^[a-zA-Z0-9_]+$")

        fun isValidEmailFormat(email: String): Boolean {
            return email.length in 3..120 && EMAIL_REGEX.matches(email)
        }

        /**
         * Produces `"call_$chatRoomId"` when within Agora's 64-byte channel limit,
         * falling back to a deterministic 27-character SHA-256 channel name for very long emails.
         */
        fun buildCallChannelName(
            chatRoomId: String,
            callerEmail: String,
            calleeEmail: String
        ): String {
            val candidate = "call_$chatRoomId"
            return if (candidate.length in 3..MAX_AGORA_CHANNEL_LENGTH && CHANNEL_REGEX.matches(candidate)) {
                candidate
            } else {
                AgoraCallController.buildChannelName(callerEmail, calleeEmail)
            }
        }
    }
}
