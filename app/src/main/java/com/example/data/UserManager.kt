package com.example.data

import android.content.Context
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.util.Locale

class UserManager(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    fun emailToHash(email: String): String {
        val normalized = normalizeEmail(email)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            "%02x".format(Locale.ROOT, byte)
        }
    }

    /**
     * Registers or updates the current signed-in user in the privacy-preserving
     * `/userDirectory/{sha256(email)}` lookup collection so other signed-in users
     * can find them by exact Gmail address without exposing emails or allowing enumeration.
     */
    suspend fun saveUserToDatabase(): Result<Unit> {
        val currentUser = auth.currentUser
            ?: return Result.failure(
                IllegalStateException("User must be signed in before registering in directory.")
            )
        val rawEmail = currentUser.email
            ?.let(::normalizeEmail)
            ?.takeIf(::isValidEmailFormat)
            ?: return Result.failure(IllegalArgumentException("Signed-in user has no valid email."))

        if (!currentUser.isEmailVerified) {
            return Result.failure(
                IllegalStateException("Verified Google email is required for directory registration.")
            )
        }

        val emailHash = emailToHash(rawEmail)
        val docPath = "userDirectory/$emailHash"
        val safeDisplayName = currentUser.displayName
            ?.trim()
            ?.take(60)
            ?.ifBlank { null }
            ?: rawEmail.substringBefore("@").take(60).ifBlank { "Just Chat User" }

        return try {
            val docRef = db.collection(COLLECTION_USER_DIRECTORY).document(emailHash)
            val existing = docRef.get().await()
            if (!existing.exists()) {
                val payload = mapOf(
                    "uid" to currentUser.uid,
                    "displayName" to safeDisplayName,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            } else if (existing.getString("uid") == currentUser.uid) {
                docRef.update(
                    mapOf(
                        "displayName" to safeDisplayName,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    /**
     * Looks up a registered Just Chat user by exact Gmail address using a single-document
     * get on `/userDirectory/{sha256(email)}`.
     */
    suspend fun searchUserByEmail(searchEmail: String): Result<DirectoryUser?> {
        if (auth.currentUser == null) {
            return Result.failure(
                IllegalStateException("Sign in with Google before searching users.")
            )
        }
        val normalizedEmail = normalizeEmail(searchEmail)
        if (!isValidEmailFormat(normalizedEmail)) {
            return Result.failure(
                IllegalArgumentException("Please enter a valid Gmail address (e.g. friend@gmail.com)")
            )
        }

        val emailHash = emailToHash(normalizedEmail)
        val docPath = "userDirectory/$emailHash"
        return try {
            val snapshot = db.collection(COLLECTION_USER_DIRECTORY)
                .document(emailHash)
                .get()
                .await()

            if (!snapshot.exists()) {
                return Result.success(null)
            }

            val entry = snapshot.toObject(
                UserDirectoryDoc::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            val resolvedUid = entry?.uid?.takeIf { it.isNotBlank() }
                ?: return Result.success(null)
            val resolvedName = entry.displayName.ifBlank {
                normalizedEmail.substringBefore("@").ifBlank { "Just Chat User" }
            }

            Result.success(
                DirectoryUser(
                    uid = resolvedUid,
                    email = normalizedEmail,
                    displayName = resolvedName
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docPath)
            Result.failure(e)
        }
    }

    /**
     * Callback convenience wrapper for exact-match email lookup returning `(uid, email)`
     * or `(null, null)` if not found.
     */
    fun searchUserByEmail(searchEmail: String, onResult: (String?, String?) -> Unit) {
        if (auth.currentUser == null) {
            onResult(null, null)
            return
        }
        val normalizedEmail = normalizeEmail(searchEmail)
        if (!isValidEmailFormat(normalizedEmail)) {
            onResult(null, null)
            return
        }

        val emailHash = emailToHash(normalizedEmail)
        val docPath = "userDirectory/$emailHash"
        db.collection(COLLECTION_USER_DIRECTORY)
            .document(emailHash)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    val uid = snapshot.getString("uid")
                    if (!uid.isNullOrBlank()) {
                        onResult(uid, normalizedEmail)
                    } else {
                        onResult(null, null)
                    }
                } else {
                    onResult(null, null)
                }
            }
            .addOnFailureListener { error ->
                handleFirestoreError(error, OperationType.GET, docPath)
                onResult(null, null)
            }
    }

    private fun normalizeEmail(email: String): String = email.trim().lowercase(Locale.ROOT)

    private fun isValidEmailFormat(email: String): Boolean {
        if (email.length !in 3..120) return false
        val parts = email.split("@")
        return parts.size == 2 && parts[0].isNotBlank() && parts[1].contains(".") && !parts[1].startsWith(".") && !parts[1].endsWith(".")
    }

    companion object {
        const val COLLECTION_USER_DIRECTORY = "userDirectory"
    }
}
