package com.lepao.companion.run

import com.lepao.companion.core.RunRules

object EastCampusZone {
    private val names = setOf("东校区", "东区", "北京交通大学东校区")

    fun supports(rules: RunRules, schoolId: String): Boolean =
        schoolId.isNotBlank() && rules.schoolId == schoolId && rules.schoolName.trim() == "北京交通大学"

    fun isSelected(rules: RunRules): Boolean =
        rules.runZoneName.trim() in names && validId(rules.runZoneId)

    fun targetId(rules: RunRules): String? {
        val zones = rules.raw.optJSONArray("run_zone_list") ?: return null
        val ids = (0 until zones.length()).mapNotNull { index ->
            val zone = zones.optJSONObject(index) ?: return@mapNotNull null
            zone.optString("id").takeIf { zone.optString("name").trim() in names && validId(it) }
        }
        return ids.distinct().singleOrNull()
    }

    private fun validId(id: String): Boolean = id.toLongOrNull()?.let { it > 0 } == true
}
