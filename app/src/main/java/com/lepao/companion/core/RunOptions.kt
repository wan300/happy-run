package com.lepao.companion.core

import java.util.Locale

/**
 * Optional user overrides for the fixed run profile (高级选项).
 *
 * `null` means "use the built-in default" (2.20 km at 6:05/km on the east-campus track). The
 * values only reach the planner after the UI validates them: the distance must stay above 1 km and
 * the pace inside 3-9 min/km. The school's own rule interval is still enforced by the planner.
 */
data class RunOptions(
    val distanceKm: Double? = null,
    val paceSecPerKm: Double? = null,
) {

    val custom: Boolean get() = distanceKm != null || paceSecPerKm != null

    companion object {

        /** The 高级选项 distance must be strictly greater than this. */
        const val MIN_DISTANCE_KM = 1.0

        /** 3:00 /km. */
        const val MIN_PACE_SEC_PER_KM = 180.0

        /** 9:00 /km. */
        const val MAX_PACE_SEC_PER_KM = 540.0

        /** "3.5" → 3.5; null when unparsable or not greater than 1 km. */
        fun parseDistance(text: String): Double? {
            val value = text.trim().toDoubleOrNull() ?: return null
            return value.takeIf { it.isFinite() && it > MIN_DISTANCE_KM }
        }

        /**
         * Accepts either "5:30" (mm:ss) or decimal minutes ("5.5") and returns seconds per km.
         * Returns null when unparsable or outside the 3-9 min/km band.
         */
        fun parsePace(text: String): Double? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val seconds = if (trimmed.contains(':')) {
                val parts = trimmed.split(':')
                if (parts.size != 2) return null
                val minutes = parts[0].trim().toIntOrNull() ?: return null
                val rest = parts[1].trim().toIntOrNull() ?: return null
                if (rest !in 0..59 || minutes < 0) return null
                minutes * 60.0 + rest
            } else {
                val minutes = trimmed.toDoubleOrNull() ?: return null
                minutes * 60.0
            }
            return seconds.takeIf {
                it.isFinite() && it >= MIN_PACE_SEC_PER_KM && it <= MAX_PACE_SEC_PER_KM
            }
        }

        /** 330.0 → "5:30". */
        fun formatPace(seconds: Double): String {
            val total = Math.round(seconds).toInt()
            return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
        }
    }
}
