package com.lepao.companion.core

import org.json.JSONArray
import org.json.JSONObject

/** Small, allocation-friendly JSON helpers shared by every layer. */
object JsonUtil {

    fun parseObject(text: String): JSONObject = JSONObject(text)

    fun parseObjectOrNull(text: String?): JSONObject? = try {
        if (text.isNullOrBlank()) null else JSONObject(text)
    } catch (_: Exception) {
        null
    }

    fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val result = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val value = array.opt(index)
            if (value != null && value != JSONObject.NULL) result.add(value.toString())
        }
        return result
    }

    fun objectList(array: JSONArray?): List<JSONObject> {
        if (array == null) return emptyList()
        val result = ArrayList<JSONObject>(array.length())
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let(result::add)
        }
        return result
    }

    fun arrayOfStrings(values: Collection<String>): JSONArray {
        val array = JSONArray()
        values.forEach(array::put)
        return array
    }

    fun optStringOrNull(source: JSONObject, key: String): String? {
        if (!source.has(key)) return null
        val value = source.opt(key) ?: return null
        if (value == JSONObject.NULL) return null
        return value.toString()
    }

    fun optLongOrNull(source: JSONObject, key: String): Long? {
        val value = source.opt(key) ?: return null
        if (value == JSONObject.NULL) return null
        return when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }

    fun optDoubleOrNull(source: JSONObject, key: String): Double? {
        val value = source.opt(key) ?: return null
        if (value == JSONObject.NULL) return null
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
    }

    fun optBoolFlag(source: JSONObject, key: String, default: Boolean = false): Boolean {
        val value = optStringOrNull(source, key) ?: return default
        return value == "1" || value.equals("true", ignoreCase = true)
    }

    /** Stable textual form used in verification checks (no locale surprises). */
    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is Number -> {
            val asDouble = value.toDouble()
            if (asDouble.isFinite() && asDouble == Math.floor(asDouble) && kotlin.math.abs(asDouble) < 1e15) {
                asDouble.toLong().toString()
            } else {
                value.toString()
            }
        }
        else -> value.toString()
    }

    fun numericEqual(left: Any?, right: Any?): Boolean {
        val a = toDecimal(left) ?: return false
        val b = toDecimal(right) ?: return false
        return a.compareTo(b) == 0
    }

    private fun toDecimal(value: Any?): java.math.BigDecimal? {
        val text = when (value) {
            null, JSONObject.NULL -> return null
            is Number -> value.toString()
            else -> value.toString().trim()
        }
        return try {
            java.math.BigDecimal(text)
        } catch (_: NumberFormatException) {
            null
        }
    }
}
