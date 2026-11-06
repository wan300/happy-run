package com.lepao.companion.run

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** The active run zone is one polygon with an optional excluded polygon, in GCJ-02. */
internal class RunFence private constructor(
    private val boundary: List<Point>,
    private val excluded: List<Point>,
) {
    private data class Point(val x: Double, val y: Double)

    fun blocker(track: List<TrackPoint>): String? {
        val points = track.map { Point(it.lng, it.lat) }
        if (points.any { !contains(boundary, it) || contains(excluded, it) }) {
            return "田径场轨迹超出东校区跑区或经过无效区域"
        }
        val crosses = points.zipWithNext().any { (a, b) ->
            crossesBoundary(boundary, a, b) || crossesBoundary(excluded, a, b)
        }
        return if (crosses) "田径场轨迹超出东校区跑区或经过无效区域" else null
    }

    private fun contains(polygon: List<Point>, point: Point): Boolean {
        var inside = false
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            if ((a.y > point.y) != (b.y > point.y) &&
                point.x < a.x + (b.x - a.x) * (point.y - a.y) / (b.y - a.y)
            ) inside = !inside
        }
        return inside
    }

    private fun crossesBoundary(polygon: List<Point>, a: Point, b: Point): Boolean =
        polygon.indices.any { i ->
            val c = polygon[i]
            val d = polygon[(i + 1) % polygon.size]
            (side(a, b, c) * side(a, b, d) < 0 && side(c, d, a) * side(c, d, b) < 0) ||
                onSegment(a, b, c) || onSegment(a, b, d) || onSegment(c, d, a) || onSegment(c, d, b)
        }

    private fun onSegment(a: Point, b: Point, p: Point): Boolean =
        abs(side(a, b, p)) < 1e-14 &&
            p.x in minOf(a.x, b.x)..maxOf(a.x, b.x) && p.y in minOf(a.y, b.y)..maxOf(a.y, b.y)

    private fun side(a: Point, b: Point, p: Point): Double =
        (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)

    companion object {
        fun from(raw: JSONObject): RunFence? {
            val boundary = parse(raw.optJSONArray("run_zone_latlng")) ?: return null
            val exclusions = raw.optJSONArray("run_zone_latlng_invalid")
            if (exclusions == null && raw.has("run_zone_latlng_invalid") && !raw.isNull("run_zone_latlng_invalid")) {
                return null
            }
            val excluded = if (exclusions == null || exclusions.length() == 0) emptyList()
                else parse(exclusions) ?: return null
            return RunFence(boundary, excluded)
        }

        private fun parse(array: JSONArray?): List<Point>? {
            if (array == null || array.length() < 3) return null
            val points = (0 until array.length()).map { index ->
                val pair = array.optString(index).split(',')
                if (pair.size != 2) return null
                // The server serialises each vertex as "lng,lat" (e.g. "116.3322770000,39.9557620000"),
                // not the "lat,lng" the field name suggests.
                val lng = pair[0].trim().toDoubleOrNull() ?: return null
                val lat = pair[1].trim().toDoubleOrNull() ?: return null
                if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
                Point(lng, lat)
            }
            return points.takeIf { it.distinct().size >= 3 }
        }
    }
}
