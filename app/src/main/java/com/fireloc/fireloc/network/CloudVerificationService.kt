package com.fireloc.fireloc.network

import android.util.Log
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import retrofit2.http.Url

/**
 * Service responsible for fetching Firebase security tokens and executing the
 * authenticated API call to the Cloud Function for fire detection and localization.
 */
class CloudVerificationService(private val apiService: ApiService) {

    private val TAG = "CloudVerification"

    private val detectUrl = "https://detect-pppkiwepma-uc.a.run.app"

    /**
     * Fetches the necessary security tokens (User ID Token and App Check Token)
     * required for authenticated calls to Firebase Cloud Functions.
     * * @return A Pair containing <Authorization Header Value, App Check Header Value> or null on failure.
     */
    private suspend fun getSecurityTokens(): Pair<String, String>? {
        val appCheck = FirebaseAppCheck.getInstance()
        val auth = FirebaseAuth.getInstance()

        try {
            // 1. Get Firebase User ID Token (Authorization Header: Bearer <token>)
            val authResult = auth.currentUser?.getIdToken(false)?.await()
            val authToken = authResult?.token ?: run {
                Log.e(TAG, "User not authenticated or ID token is null.")
                return null
            }

            // 2. Get App Check Token (X-Firebase-AppCheck Header)
            // Firebase App Check helps protect your backend resources from abuse.
            val appCheckResult = appCheck.getAppCheckToken(false).await()
            val appCheckToken = appCheckResult.token ?: run {
                Log.e(TAG, "App Check token is null.")
                return null
            }

            // Return tokens in the format required by the HTTP headers
            return Pair("Bearer $authToken", appCheckToken)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch security tokens: ${e.message}", e)
            return null
        }
    }

    /**
     * Sends the detection request to the Cloud Function using Retrofit and the necessary security tokens.
     * * @param requestData The payload containing the image, GPS, orientation, and FOV data.
     * @return DetectResponse object if the API call is successful, or null otherwise.
     */
    suspend fun sendDetectionRequest(requestData: DetectRequest): DetectResponse? {
        val tokens = getSecurityTokens() ?: return null
        val (authToken, appCheckToken) = tokens

        return try {
            // Call the 'detect' function defined in ApiService.kt
            val response = apiService.detect(detectUrl, authToken, appCheckToken, requestData)

            if (response.isSuccessful && response.body() != null) {
                // Cloud verification successful. The backend is now processing localization.
                Log.i(TAG, "Cloud verification request successful. Status: ${response.body()!!.status}")
                response.body()
            } else {
                Log.e(TAG, "Cloud detection failed. Code: ${response.code()}, Error: ${response.errorBody()?.string()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error during cloud detection: ${e.message}", e)
            null
        }
    }
}