package com.lepao.companion.run

import android.content.Context
import com.lepao.companion.core.RunOptions

/** Persists the 高级选项 overrides in the app's private preferences. */
object RunOptionStore {

    private const val PREFS = "run_options"
    private const val KEY_DISTANCE = "distance_km"
    private const val KEY_PACE = "pace_sec_per_km"

    fun load(context: Context): RunOptions {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val distance = prefs.getString(KEY_DISTANCE, null)?.toDoubleOrNull()
        val pace = prefs.getString(KEY_PACE, null)?.toDoubleOrNull()
        return RunOptions(
            distanceKm = distance?.takeIf { it.isFinite() && it > RunOptions.MIN_DISTANCE_KM },
            paceSecPerKm = pace?.takeIf {
                it.isFinite() &&
                    it >= RunOptions.MIN_PACE_SEC_PER_KM && it <= RunOptions.MAX_PACE_SEC_PER_KM
            },
        )
    }

    fun save(context: Context, options: RunOptions) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (options.distanceKm == null) editor.remove(KEY_DISTANCE) else editor.putString(KEY_DISTANCE, options.distanceKm.toString())
        if (options.paceSecPerKm == null) editor.remove(KEY_PACE) else editor.putString(KEY_PACE, options.paceSecPerKm.toString())
        editor.apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
