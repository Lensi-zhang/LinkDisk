package com.linkdisk.app.data

import android.content.Context
import com.linkdisk.app.server.TunnelConfig
import java.security.SecureRandom

class LinkDiskPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secrets = SecretStore(appContext)

    var sharedTreeUri: String?
        get() = preferences.getString(KEY_TREE_URI, null)
        set(value) = preferences.edit().putString(KEY_TREE_URI, value).apply()

    var port: Int
        get() = preferences.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = preferences.edit().putInt(KEY_PORT, value).apply()

    var username: String
        get() = preferences.getString(KEY_USERNAME, DEFAULT_USERNAME).orEmpty()
        set(value) = preferences.edit().putString(KEY_USERNAME, value).apply()

    var password: String
        get() = readPassword()
        set(value) {
            secrets.putString(KEY_PASSWORD, value)
            removeLegacyPassword()
        }

    var publicAccessEnabled: Boolean
        get() = preferences.getBoolean(KEY_PUBLIC_ACCESS_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_PUBLIC_ACCESS_ENABLED, value).apply()

    var frpcConfig: String
        get() = secrets.getString(KEY_FRPC_CONFIG).orEmpty()
        set(value) = secrets.putString(KEY_FRPC_CONFIG, value)

    var publicUrl: String
        get() = preferences.getString(KEY_PUBLIC_URL, "").orEmpty()
        set(value) = preferences.edit().putString(KEY_PUBLIC_URL, value).apply()

    var tunnelConfig: TunnelConfig
        get() = TunnelConfig(
            localPort = port,
            publicUrl = publicUrl,
            rawConfig = frpcConfig,
        )
        set(value) {
            check(
                preferences.edit()
                    .putInt(KEY_PORT, value.localPort)
                    .putString(KEY_PUBLIC_URL, value.publicUrl)
                    .commit(),
            ) { "无法保存公网配置" }
            frpcConfig = value.rawConfig
        }

    private fun readPassword(): String {
        val legacyPassword = preferences.getString(KEY_PASSWORD, null)
        secrets.getString(KEY_PASSWORD)?.let {
            if (legacyPassword != null) removeLegacyPassword()
            return it
        }

        if (legacyPassword != null) {
            secrets.putString(KEY_PASSWORD, legacyPassword)
            removeLegacyPassword()
            return legacyPassword
        }

        return generatePassword().also { secrets.putString(KEY_PASSWORD, it) }
    }

    private fun removeLegacyPassword() {
        check(preferences.edit().remove(KEY_PASSWORD).commit()) { "无法迁移现有 WebDAV 密码" }
    }

    private fun generatePassword(): String = buildString(DEFAULT_PASSWORD_LENGTH) {
        repeat(DEFAULT_PASSWORD_LENGTH) {
            append(PASSWORD_ALPHABET[secureRandom.nextInt(PASSWORD_ALPHABET.length)])
        }
    }

    private companion object {
        const val PREFS_NAME = "linkdisk_settings"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_PORT = "port"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_PUBLIC_ACCESS_ENABLED = "public_access_enabled"
        const val KEY_FRPC_CONFIG = "frpc_config"
        const val KEY_PUBLIC_URL = "public_url"
        const val DEFAULT_PORT = 8080
        const val DEFAULT_USERNAME = "admin"
        const val DEFAULT_PASSWORD_LENGTH = 20
        const val PASSWORD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val secureRandom = SecureRandom()
    }
}
