package com.fireloc.fireloc.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    // We need a dummy base URL for Retrofit builder, even if we use @Url in ApiService
    // Use a placeholder, it won't actually be used for requests with @Url.
    private const val DUMMY_BASE_URL = "http://localhost/" // Placeholder

    val instance: ApiService by lazy {
        createRetrofit().create(ApiService::class.java)
    }

    private fun createRetrofit(): Retrofit {
        // Configure OkHttpClient with logging (highly recommended for debugging)
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY // Log request/response bodies
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS) // Increase timeouts if needed
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(DUMMY_BASE_URL) // Provide the placeholder base URL
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create()) // Use Gson for JSON
            .build()
    }
}