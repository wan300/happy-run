package com.lepao.companion.core

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The ten authenticated context fields the client sends on every request. */
val COMMON_FIELDS: List<String> = listOf(
    "version", "version_name", "mobileModel", "mobileDeviceId",
    "mobileOsVersion", "ostype", "student_num", "school_id", "uid", "token",
)

data class WireRequest(
    val endpoint: String,
    val fields: LinkedHashMap<String, Any?>,
    val body: ByteArray,
)

/**
 * Wire implementation verified against the 2026-11-02 capture.
 *
 * This object is deliberately free of Android dependencies so it can be exercised by JVM unit
 * tests against golden fixtures generated from the Python reference implementation.
 */
object Protocol {

    private data class Profile(val key: String, val iv: String)

    private val PROFILES = mapOf(
        "NEW_GAME" to Profile("Wet2C8d34f62ndi3", "K6iv85jBD8jgf32D"),
        "PARAMS_NEW_GAME" to Profile("goMEzJ1pDpncNHb9", "ZUrAS2lUidjyY2gK"),
    )

    val DYNAMIC_KEYS: List<Pair<String, String>> = listOf(
        "L3xP7kQ2fD9sH5jR" to "N8wB4mG6vK2rT9cE",
        "Z5tF8gS3dA7hJ2pQ" to "Y6rU3eW9qX4sC8vB",
        "Q2mK7jP3nL5iO9uY" to "W4sE8qR2tT5yU3iO",
        "D9fG3hJ7kL2pM5rN" to "B6vC8nX3mZ5dF7gH",
        "P7bN2vF9cD4mK6jA" to "S3hG5jJ8dL7fT2wQ",
        "H5jG8fD3sA6dF9gH" to "K7lJ2pO5iU8yT3rE",
        "X8rV3tB6yG9hN4kP" to "E2sW5qA3dZ7fC8jM",
        "M3dS5fG8hJ2kL7pR" to "C4tY9rU6eW3qX5sZ",
        "F6dG2hJ5kL8pM3rN" to "Z7xC5vB3nA9sD2fH",
        "V5bC3nM8vX4cZ6dF" to "J9kL2jH5gD3fS7dA",
        "B8vN5cM2xZ9dF3gJ" to "H4kL7jP2mQ5rT8sW",
        "N3mK6lJ8pO5iU2yT" to "G5hF7dS9fA3gH4jK",
        "T6rE2yU5iO8pJ3lK" to "J7kH3gF5dS8aQ2wE",
        "W9qE3sA6dF5gH7jK" to "R5tY2eU7iO8pJ3lK",
        "S4dF8aQ3wE6rT5yU" to "I2uO5pJ8lK3jH7gF",
    )

    val RECORD_SALTS: List<String> = listOf(
        "aB3dE9fG2hJkL5mN", "PqR7sT8uV9wXyZ1", "A2bC3dE4fG5hI6j",
        "K7lM8nO9pQ0rS1t", "U2vW3xY4zA5bC6d", "E7fG8hI9jK0lM1n",
        "O2pQ3rS4tU5vW6x", "Y7zA8bC9dE0fG1h", "I2jK3lM4nO5pQ6r",
        "S7tU8vW9xY0zA1b",
    )

    fun md5Hex(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Python's base64.encodebytes: 76-column MIME Base64 with a trailing newline. */
    fun b64Mime(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val encoded = Base64.getMimeEncoder(76, byteArrayOf('\n'.code.toByte()))
            .encodeToString(bytes)
        return "$encoded\n"
    }

    fun encryptText(text: String, profile: String = "NEW_GAME"): String {
        val spec = PROFILES[profile] ?: throw ProtocolException("Unknown profile: $profile")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(spec.key.toByteArray(StandardCharsets.US_ASCII), "AES"),
            IvParameterSpec(spec.iv.toByteArray(StandardCharsets.US_ASCII)),
        )
        val encrypted = cipher.doFinal(text.toByteArray(StandardCharsets.UTF_8))
        return b64Mime(encrypted)
    }

    fun decryptText(ciphertext: String, profile: String = "NEW_GAME"): String {
        val spec = PROFILES[profile] ?: throw ProtocolException("Unknown profile: $profile")
        val compact = ciphertext.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) throw ProtocolException("Empty ciphertext")
        val raw = try {
            Base64.getDecoder().decode(compact)
        } catch (error: IllegalArgumentException) {
            throw ProtocolException("Ciphertext is not valid Base64", error)
        }
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(spec.key.toByteArray(StandardCharsets.US_ASCII), "AES"),
                IvParameterSpec(spec.iv.toByteArray(StandardCharsets.US_ASCII)),
            )
            String(cipher.doFinal(raw), StandardCharsets.UTF_8)
        } catch (error: Exception) {
            throw ProtocolException("Ciphertext failed AES/PKCS5 validation", error)
        }
    }

    /** JSON string literal exactly as Python's json.dumps(ensure_ascii=False) would emit it. */
    fun jsonQuote(text: String): String {
        val builder = StringBuilder(text.length + 2)
        builder.append('"')
        for (char in text) {
            when (char) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\b' -> builder.append("\\b")
                '\u000c' -> builder.append("\\f")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (char < ' ') {
                    builder.append(String.format(Locale.ROOT, "\\u%04x", char.code))
                } else {
                    builder.append(char)
                }
            }
        }
        return builder.append('"').toString()
    }

    fun javaString(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is Boolean -> if (value) "true" else "false"
        is String -> value
        is Number -> {
            // Python renders integral doubles as 2.0; wire fields here are strings or integers.
            if (value is Double || value is Float) value.toString() else value.toString()
        }
        is JSONArray, is JSONObject ->
            throw ProtocolException("Nested request values must be serialized at their call site")
        else -> value.toString()
    }

    private fun jsonValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is Boolean -> if (value) "true" else "false"
        is String -> jsonQuote(value)
        is Number -> value.toString()
        is JSONArray -> value.toString()
        is JSONObject -> value.toString()
        else -> jsonQuote(value.toString())
    }

    fun signFields(fields: Map<String, Any?>, newer: Boolean): String {
        val material = StringBuilder()
        fields.keys
            .filter { it != "sign" && it != "file" && !(newer && it == "mobileDeviceId") }
            .sorted()
            .forEach { key -> material.append(key).append(javaString(fields[key])) }
        val text = if (newer) "eFoqdHOdDy6ViJ0i$material" else "$material" + "rDJiNB9j7vD2"
        return md5Hex(text)
    }

    fun dynamicName(model: String, timestamp: String): String {
        if (model.length < 2 || timestamp.length != 10 || !timestamp.all { it.isDigit() }) {
            throw ProtocolException("Unsupported device model or timestamp shape")
        }
        return "lp" + model[1].lowercaseChar() + timestamp[3]
    }

    fun dynamicValue(timestamp: String, umid: String): String {
        if (umid.isEmpty()) throw ProtocolException("An observed device UMID is required")
        val index = timestamp.toLong() % DYNAMIC_KEYS.size
        val (key, iv) = DYNAMIC_KEYS[index.toInt()]
        val text = umid + md5Hex(timestamp)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(StandardCharsets.US_ASCII), "AES"),
            IvParameterSpec(iv.toByteArray(StandardCharsets.US_ASCII)),
        )
        return b64Mime(cipher.doFinal(text.toByteArray(StandardCharsets.UTF_8)))
    }

    fun recordSignature(schoolId: String, uid: String, timeStr: String): String {
        val numericUid = uid.toBigIntegerOrNull()
            ?: throw ProtocolException("Record signature requires a numeric uid")
        val narrowed = numericUid.toSigned32()
        if (narrowed < 0) throw ProtocolException("UID is outside the verified Java record-signature domain")
        val index = narrowed % RECORD_SALTS.size
        val sum = (schoolId.toBigIntegerOrNull() ?: throw ProtocolException("Invalid school id"))
            .add(numericUid)
        val material = sum.toString() + timeStr + RECORD_SALTS[index]
        return md5Hex(material)
    }

    private fun BigInteger.toSigned32(): Int {
        val modulus = BigInteger.ONE.shiftLeft(32)
        val value = mod(modulus).toLong()
        return if (value >= 0x80000000L) (value - 0x100000000L).toInt() else value.toInt()
    }

    /** Python's `urllib.parse.quote_plus`: unreserved A-Za-z0-9-_.~ only, space as '+'. */
    fun formEncode(values: Map<String, String>): String {
        val builder = StringBuilder()
        for ((key, value) in values) {
            if (builder.isNotEmpty()) builder.append('&')
            builder.append(percentEncode(key)).append('=').append(percentEncode(value))
        }
        return builder.toString()
    }

    private fun percentEncode(text: String): String {
        val builder = StringBuilder()
        for (byte in text.toByteArray(StandardCharsets.UTF_8)) {
            val value = byte.toInt() and 0xff
            val char = value.toChar()
            if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char == '-' || char == '_' || char == '.' || char == '~'
            ) {
                builder.append(char)
            } else if (char == ' ') {
                builder.append('+')
            } else {
                builder.append('%').append(String.format(Locale.ROOT, "%02X", value))
            }
        }
        return builder.toString()
    }

    fun encodeFields(endpoint: String, fields: LinkedHashMap<String, Any?>): WireRequest {
        val contract = Endpoints.contract(endpoint)
        val unsigned = LinkedHashMap<String, Any?>()
        for ((key, value) in fields) if (key != "sign") unsigned[key] = value
        val sign = signFields(unsigned, contract.newerSignature)
        val signed = LinkedHashMap<String, Any?>(unsigned).apply { put("sign", sign) }
        if (contract.encrypted) {
            val sorted = LinkedHashMap<String, Any?>()
            unsigned.keys.sorted().forEach { key -> sorted[key] = unsigned[key] }
            val builder = StringBuilder("{")
            for ((key, value) in sorted) {
                if (builder.length > 1) builder.append(',')
                builder.append(jsonQuote(key)).append(':').append(jsonValue(value))
            }
            builder.append(if (builder.length > 1) "," else "")
            builder.append("\"sign\":").append(jsonQuote(sign)).append('}')
            // Android JSONObject escapes forward slashes, including those in Base64.
            val plaintext = builder.toString().replace("/", "\\/")
            val form = mapOf("key" to encryptText(plaintext, "PARAMS_NEW_GAME"))
            return WireRequest(endpoint, signed, formEncode(form).toByteArray(StandardCharsets.UTF_8))
        }
        val form = LinkedHashMap<String, String>()
        for ((key, value) in signed) form[key] = javaString(value)
        return WireRequest(endpoint, signed, formEncode(form).toByteArray(StandardCharsets.UTF_8))
    }

    fun buildRequest(
        endpoint: String,
        identity: Identity,
        extra: Map<String, Any?> = emptyMap(),
        timestamp: Long,
        nonce: String? = null,
        timeStr: String? = null,
    ): WireRequest {
        val forbidden = COMMON_FIELDS.toSet() + setOf("timestamp", "nonce", "sign")
        val collision = extra.keys.firstOrNull { it in forbidden }
        if (collision != null) {
            throw ProtocolException("Business field cannot replace the request context: $collision")
        }
        identity.requireComplete()
        val fields = LinkedHashMap<String, Any?>()
        for (key in COMMON_FIELDS) fields[key] = identity[key]
        fields["timestamp"] = timestamp.toString()
        fields["nonce"] = nonce
            ?: (100000L + kotlin.math.abs(System.nanoTime() % 900000L) % 900000L).toString()
        extra.forEach { (key, value) -> fields[key] = value }
        val contract = Endpoints.contract(endpoint)
        if (contract.dynamic) {
            val name = dynamicName(identity["mobileModel"], timestamp.toString())
            if (endpoint == Endpoints.STOP) {
                if (timeStr.isNullOrEmpty()) {
                    throw ProtocolException("Stop requires the formal start response of this session")
                }
                fields[name] = timeStr
            } else {
                fields[name] = dynamicValue(timestamp.toString(), identity.umid)
            }
        }
        return encodeFields(endpoint, fields)
    }

    fun decodeResponse(envelope: JSONObject): JSONObject {
        val status = envelope.opt("status")
        val statusOk = (status is Number && status.toInt() == 1) ||
            (status is String && status.trim() == "1")
        if (!statusOk) {
            throw ProtocolException("Business response requires handling (status=$status)")
        }
        var data: Any? = envelope.opt("data")
        if (data is String && data.isNotEmpty()) {
            val decrypted = decryptText(data)
            data = try {
                JSONObject(decrypted)
            } catch (error: Exception) {
                throw ProtocolException("Response data failed AES/JSON validation", error)
            }
        }
        val result = JSONObject()
        val keys = envelope.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key != "data") result.put(key, envelope.opt(key))
        }
        result.put("data", data ?: JSONObject.NULL)
        return result
    }

    /** Best-effort decryption used by the capture layer on observed responses. */
    fun tryDecryptEnvelopeData(envelope: JSONObject): JSONObject? {
        val data = envelope.opt("data")
        if (data is JSONObject) return data
        if (data !is String || data.isEmpty()) return null
        return try {
            JSONObject(decryptText(data))
        } catch (_: Exception) {
            null
        }
    }
}
