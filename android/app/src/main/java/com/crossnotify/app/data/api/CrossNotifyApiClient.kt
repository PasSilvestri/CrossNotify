package com.crossnotify.app.data.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-server API client that creates and caches Retrofit/OkHttp instances per server configuration.
 * By default, TLS is enforced (`ConnectionSpec.MODERN_TLS`) and cleartext HTTP is blocked,
 * unless the user explicitly confirms insecure HTTP usage for local network testing.
 */
@Singleton
class CrossNotifyApiClient @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private data class ServiceCacheKey(
        val baseUrl: String,
        val allowCleartext: Boolean
    )

    private val serviceCache = ConcurrentHashMap<ServiceCacheKey, CrossNotifyApiService>()

    fun serviceFor(serverUrl: String, insecureHttpAccepted: Boolean): CrossNotifyApiService {
        val normalized = serverUrl.trim().trimEnd('/') + "/"
        val isHttp = normalized.lowercase().startsWith("http://")
        if (isHttp && !insecureHttpAccepted) {
            throw CrossNotifyApiException(
                httpCode = 0,
                errorCode = "insecure_http_blocked",
                message = "Cleartext HTTP is blocked for $serverUrl unless explicitly accepted."
            )
        }

        val key = ServiceCacheKey(normalized, insecureHttpAccepted)
        return serviceCache.getOrPut(key) {
            val specs = if (insecureHttpAccepted) {
                listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT)
            } else {
                listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS)
            }

            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .connectionSpecs(specs)
                .followRedirects(false) // Never silently downgrade HTTPS -> HTTP on redirect
                .followSslRedirects(true)
                .build()

            Retrofit.Builder()
                .baseUrl(normalized)
                .client(okHttpClient)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(CrossNotifyApiService::class.java)
        }
    }

    fun bearerHeader(apiKey: String): String = "Bearer ${apiKey.trim()}"

    fun <T> unwrapResponse(response: Response<T>): T {
        if (response.isSuccessful) {
            val body = response.body()
            if (body != null) return body
        }
        val errorBody = response.errorBody()?.string()
        val parsedError = errorBody?.let {
            runCatching { json.decodeFromString<ApiErrorDto>(it) }.getOrNull()
        }
        val errorCode = parsedError?.error ?: "http_${response.code()}"
        val detail = parsedError?.detail ?: parsedError?.message ?: response.message().ifBlank { "Request failed" }
        throw CrossNotifyApiException(
            httpCode = response.code(),
            errorCode = errorCode,
            message = detail
        )
    }
}
