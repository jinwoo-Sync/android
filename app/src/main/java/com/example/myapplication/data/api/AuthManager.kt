package com.example.myapplication.data.api

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Authentication Manager for JWT Token storage and retrieval
 * Uses EncryptedSharedPreferences for secure token storage
 */
class AuthManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "auth_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_JWT_TOKEN = "jwt_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_EMAIL = "email"
    }

    // Save JWT token
    fun saveToken(token: String) {
        sharedPreferences.edit().putString(KEY_JWT_TOKEN, token).apply()
    }

    // Get JWT token
    fun getToken(): String? {
        return sharedPreferences.getString(KEY_JWT_TOKEN, null)
    }

    // Check if user is logged in
    fun isLoggedIn(): Boolean {
        return getToken() != null
    }

    // Save user info
    fun saveUserInfo(userInfo: UserInfo) {
        sharedPreferences.edit().apply {
            putLong(KEY_USER_ID, userInfo.id)
            putString(KEY_USERNAME, userInfo.username)
            putString(KEY_EMAIL, userInfo.email)
            apply()
        }
    }

    // Get user ID
    fun getUserId(): Long {
        return sharedPreferences.getLong(KEY_USER_ID, -1)
    }

    // Get username
    fun getUsername(): String? {
        return sharedPreferences.getString(KEY_USERNAME, null)
    }

    // Clear all auth data (logout)
    fun clearAuth() {
        sharedPreferences.edit().clear().apply()
    }
}
