package com.syncedwallpapers.app.delivery

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted files in app-internal storage only (never MediaStore / gallery). The AES-GCM
 * keyset is itself encrypted by a non-exportable Android Keystore key, so the files are
 * useless if copied off the device. Backups are disabled for the whole app.
 */
@Singleton
class SecureStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir = File(context.filesDir, "secure").apply { mkdirs() }

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "sw_keyset", "sw_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://sw_master_key")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    /** Encrypts and writes; returns the file name (not a path) to store in Room/DataStore. */
    fun put(bytes: ByteArray, prefix: String): String {
        val name = "$prefix-${UUID.randomUUID()}.bin"
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(aead.encrypt(bytes, name.toByteArray()))
        check(tmp.renameTo(File(dir, name))) { "could not save $name" }
        return name
    }

    /** Decrypts into memory only. */
    fun get(name: String): ByteArray? {
        val f = File(dir, name)
        if (!f.exists()) return null
        return runCatching { aead.decrypt(f.readBytes(), name.toByteArray()) }.getOrNull()
    }

    fun delete(name: String?) {
        if (name != null) File(dir, name).delete()
    }

    fun deleteAllExcept(keep: Set<String>) {
        dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }
}
