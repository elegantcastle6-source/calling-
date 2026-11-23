package com.example.data

import com.example.BuildConfig
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

private interface AgoraTokenApi {
    @GET
    suspend fun fetchRtcToken(
        @Url endpointUrl: String,
        @Query("channelName") channelName: String,
        @Query("channel") channel: String,
        @Query("uid") uid: Int,
        @Query("role") role: String,
        @Header("Authorization") authorization: String?
    ): Response<ResponseBody>
}

/**
 * Fetches short-lived Agora RTC tokens from an external backend token server
 * (such as the companion Go `rtctokenbuilder2` service) so `AGORA_APP_CERTIFICATE`
 * never needs to be bundled into the Android APK.
 *
 * Configure `AGORA_TOKEN_SERVER_URL` in the AI Studio Secrets panel
 * (for example `https://your-token-server.example.com/rtc-token`).
 */
class AgoraTokenProvider(
    private val serverUrl: String = BuildConfig.AGORA_TOKEN_SERVER_URL.trim()
) {
    val isConfigured: Boolean
        get() = isValidServerUrl(serverUrl)

    private val api: AgoraTokenApi by lazy {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl("https://localhost/")
            .client(okHttpClient)
            .build()
            .create(AgoraTokenApi::class.java)
    }

    suspend fun fetchToken(
        channelName: String,
        uid: Int = 0,
        role: String = ROLE_PUBLISHER
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            return@withContext Result.failure(
                IllegalStateException("Agora token server URL is not configured.")
            )
        }
        val cleanChannel = channelName.trim()
        if (cleanChannel.isEmpty()) {
            return@withContext Result.failure(
                IllegalArgumentException("Channel name cannot be blank.")
            )
        }

        try {
            val idToken = runCatching {
                Firebase.auth.currentUser?.getIdToken(false)?.await()?.token
            }.getOrNull()
            val authHeader = idToken?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }

            val response = api.fetchRtcToken(
                endpointUrl = serverUrl,
                channelName = cleanChannel,
                channel = cleanChannel,
                uid = uid,
                role = role,
                authorization = authHeader
            )
            val bodyString = response.body()?.string().orEmpty()
            if (!response.isSuccessful) {
                val errBody = response.errorBody()?.string().orEmpty().take(160)
                return@withContext Result.failure(
                    IllegalStateException(
                        "Token server returned HTTP ${response.code()}" +
                            if (errBody.isNotBlank()) ": $errBody" else ""
                    )
                )
            }

            val parsedToken = parseTokenFromBody(bodyString)
                ?: return@withContext Result.failure(
                    IllegalStateException("Token server response did not contain an rtcToken.")
                )

            Result.success(parsedToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        const val ROLE_PUBLISHER = "publisher"
        const val ROLE_SUBSCRIBER = "subscriber"

        fun isValidServerUrl(rawUrl: String): Boolean {
            val cleaned = rawUrl.trim()
            if (cleaned.isEmpty()) return false
            if (cleaned.equals("OPTIONAL_AGORA_TOKEN_SERVER_URL", ignoreCase = true) ||
                cleaned.equals("NONE", ignoreCase = true) ||
                cleaned.startsWith("OPTIONAL_", ignoreCase = true) ||
                cleaned.startsWith("YOUR_", ignoreCase = true) ||
                (cleaned.startsWith("<") && cleaned.endsWith(">"))
            ) {
                return false
            }
            val lower = cleaned.lowercase()
            return (lower.startsWith("https://") || lower.startsWith("http://")) &&
                cleaned.length > 10 &&
                !cleaned.contains(' ')
        }

        /**
         * Parses an Agora RTC token from standard JSON payloads (`{"rtcToken":"..."}` or
         * `{"token":"..."}`) or a raw plain-text token body.
         */
        fun parseTokenFromBody(rawBody: String): String? {
            val trimmed = rawBody.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("{")) {
                return runCatching {
                    val json = JSONObject(trimmed)
                    val candidate = json.optString("rtcToken").ifBlank {
                        json.optString("token")
                    }.trim()
                    candidate.takeIf { it.isNotEmpty() }
                }.getOrNull()
            }
            return trimmed.takeUnless { it.contains('\n') || it.contains('<') }
        }
    }
}
