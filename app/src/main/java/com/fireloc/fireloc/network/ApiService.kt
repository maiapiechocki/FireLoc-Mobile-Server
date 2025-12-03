package com.fireloc.fireloc.network // Ensure this package name is correct

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

interface ApiService {

    // Device Registration
    @POST
    suspend fun registerDevice(
        @Url url: String, // Full URL for registration
        @Header("Authorization") authToken: String, // User ID Token
        @Body registrationData: DeviceRegistrationRequest
    ): Response<DeviceRegistrationResponse> // **** CORRECT RESPONSE TYPE ****

    // Detection Endpoint
    @POST
    suspend fun detect(
        @Url url: String, // Full URL for detection
        @Header("Authorization") authToken: String, // User ID Token
        @Header("X-Firebase-AppCheck") appCheckToken: String, // App Check Token
        @Body detectRequestData: DetectRequest
    ): Response<DetectResponse>

}