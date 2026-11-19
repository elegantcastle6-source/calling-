package com.example.utils

private val INVALID_ROOM_ID_CHARS = Regex("[^a-zA-Z0-9_]")

/**
 * Generates a deterministic, symmetric chat room ID for two users (by Gmail or UID).
 * Sorting ensures both participants always compute the same room ID regardless of order,
 * and sanitizing special characters ensures compatibility with Firebase Realtime Database
 * paths and Firestore document IDs.
 */
fun buildChatRoomId(userA: String, userB: String): String {
    return listOf(userA.trim().lowercase(), userB.trim().lowercase())
        .sorted()
        .joinToString("_")
        .replace(INVALID_ROOM_ID_CHARS, "_")
}
