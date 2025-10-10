package com.example.myapplication.data.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit API Client for Kafka Server
 * Handles network configuration, authentication, and API service creation
 */
object ApiClient {

    // Server configuration
    // TODO: Update with actual server URL
    private const val BASE_URL = "http://192.168.1.100:18085/"  // Adjust to your server IP

    // Timeouts
    private const val CONNECT_TIMEOUT = 30L
    private const val READ_TIMEOUT = 60L  // Longer for large image uploads
    private const val WRITE_TIMEOUT = 60L

    // Moshi for JSON serialization
    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    // Logging interceptor for debugging
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    // Auth interceptor to add JWT token to requests
    class AuthInterceptor(private val tokenProvider: () -> String?) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val originalRequest = chain.request()

            // Skip adding token for login endpoint
            if (originalRequest.url.encodedPath.contains("/auth/login")) {
                return chain.proceed(originalRequest)
            }

            // Add Authorization header with Bearer token
            val token = tokenProvider()
            val newRequest = if (token != null) {
                originalRequest.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            } else {
                originalRequest
            }

            return chain.proceed(newRequest)
        }
    }

    // OkHttp client with interceptors
    private fun createOkHttpClient(tokenProvider: () -> String?): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenProvider))
            .addInterceptor(loggingInterceptor)
            .build()
    }

    // Retrofit instance
    private fun createRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    // Create API service
    fun createApi(tokenProvider: () -> String?): KafkaServerApi {
        val okHttpClient = createOkHttpClient(tokenProvider)
        val retrofit = createRetrofit(okHttpClient)
        return retrofit.create(KafkaServerApi::class.java)
    }

    // Convenience method for creating API with no token (for login)
    fun createApiWithoutAuth(): KafkaServerApi {
        return createApi { null }
    }
}
