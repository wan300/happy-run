package com.lepao.companion.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local state protection: AES-256-GCM with a non-exportable Android Keystore key.
 *
 * Files contain `{"v":1,"iv":"...","ct":"..."}` and are replaced atomically. The keystore key is
 * generated on first use and never leaves the secure hardware/keystore daemon.
 */
class SecureStore(context: Context) {

    private val appContext = context.applicationContext

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun seal(bytes: ByteArray): JSONObject {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(bytes)
        return JSONObject().apply {
            put("v", 1)
            put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            put("ct", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }
    }

    private fun open(envelope: JSONObject): ByteArray {
        val iv = Base64.decode(envelope.optString("iv"), Base64.NO_WRAP)
        val ciphertext = Base64.decode(envelope.optString("ct"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    fun encryptText(text: String): String = seal(text.toByteArray(StandardCharsets.UTF_8)).toString()

    fun decryptText(envelope: String): String = String(open(JSONObject(envelope)), StandardCharsets.UTF_8)

    fun writeTextAtomic(file: File, text: String) {
        writeBytesAtomic(file, seal(text.toByteArray(StandardCharsets.UTF_8)).toString().toByteArray(StandardCharsets.UTF_8))
    }

    fun writeJson(file: File, value: JSONObject) {
        writeTextAtomic(file, value.toString())
    }

    fun readText(file: File): String? {
        if (!file.isFile) return null
        return try {
            val envelope = JSONObject(file.readText(StandardCharsets.UTF_8))
            String(open(envelope), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun readJson(file: File): JSONObject? = JsonUtil.parseObjectOrNull(readText(file))

    fun appendLine(file: File, line: String) {
        file.parentFile?.mkdirs()
        val envelope = seal(line.toByteArray(StandardCharsets.UTF_8)).toString() + "\n"
        FileOutputStream(file, true).use { stream ->
            stream.write(envelope.toByteArray(StandardCharsets.UTF_8))
            stream.flush()
            try {
                stream.fd.sync()
            } catch (_: Exception) {
                // Best effort durability; the audit is diagnostic, not the state machine.
            }
        }
    }

    fun readLines(file: File): List<String> {
        if (!file.isFile) return emptyList()
        val result = ArrayList<String>()
        file.forEachLine(StandardCharsets.UTF_8) { raw ->
            if (raw.isNotBlank()) {
                try {
                    result.add(String(open(JSONObject(raw)), StandardCharsets.UTF_8))
                } catch (_: Exception) {
                    // Ignore a torn trailing line from a crash.
                }
            }
        }
        return result
    }

    private fun writeBytesAtomic(file: File, bytes: ByteArray) {
        val parent = file.parentFile ?: throw java.io.IOException("No parent directory for $file")
        parent.mkdirs()
        val temporary = File(parent, file.name + ".tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "lepao.companion.state.v1"
    }
}
