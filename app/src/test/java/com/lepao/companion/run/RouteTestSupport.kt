package com.lepao.companion.run

import com.lepao.companion.core.PlannedRun
import com.lepao.companion.core.TimeRule
import java.io.File

/**
 * Shared fixtures for the route-slice unit tests.
 *
 * The tests run on the JVM without Robolectric, so they never touch `Context`; the corridor is read
 * from the very asset the app ships and handed to the pure `List<DoubleArray>` overloads.
 */
internal object RouteTestSupport {

    const val DURATION = 741
    const val SEED = 20261104L

    /** 2025-11-05T08:00:00+08:00, any fixed positive epoch. */
    const val START_EPOCH = 1762300800L

    val rule: TimeRule = TimeRule(
        startTime = 6 * 3600,
        endTime = 22 * 3600,
        minLogNum = 2,
        minDistanceKm = 2.0,
        maxDistanceKm = 3.0,
        minPaceSecPerKm = 300.0,
        maxPaceSecPerKm = 500.0,
    )

    fun plan(seed: Long = SEED, durationSec: Int = DURATION): PlannedRun = PlannedRun(
        seed = seed,
        durationSec = durationSec,
        distanceKm = "2.03",
        stepNum = 1689,
        passTotal = 366,
        points = emptyList(),
        rule = rule,
        createdAt = START_EPOCH,
    )

    /** Raw corridor vertices from the shipped asset (`[lat, lng]` pairs, 374 of them). */
    fun corridorPoints(): List<DoubleArray> =
        RouteEngine.parseCorridor(corridorFile().readText(Charsets.UTF_8))

    /** Resampled corridor used by the pure overloads. */
    fun corridor(): RouteEngine.Corridor = RouteEngine.resample(corridorPoints())

    fun track(durationSec: Int = DURATION, seed: Long = SEED): List<RouteEngine.Sample> =
        RouteEngine.buildTrack(corridor(), durationSec, seed)

    /**
     * Gradle runs unit tests with the module directory as the working directory; the extra entries
     * keep the lookup working when the IDE or a root build launches the test task.
     */
    private fun corridorFile(): File {
        val candidates = listOf(
            "src/main/assets/corridor.json",
            "app/src/main/assets/corridor.json",
            "../app/src/main/assets/corridor.json",
        )
        return candidates.map(::File).firstOrNull { it.isFile }
            ?: throw IllegalStateException(
                "找不到 app 的 corridor.json asset（工作目录 ${File(".").absolutePath}）",
            )
    }
}

/**
 * Small JSON reader used to validate generated artifact bodies.
 *
 * `org.json` lives in the mocked android.jar for JVM unit tests, so the suite parses the wire
 * payload itself instead of depending on stub behaviour.
 */
internal object RouteTestJson {

    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value()
        reader.skipWhitespace()
        if (reader.index != text.length) throw IllegalArgumentException("JSON 尾部有多余内容")
        return value
    }

    private class Reader(private val text: String) {

        var index = 0

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun value(): Any? {
            skipWhitespace()
            if (index >= text.length) throw IllegalArgumentException("JSON 意外结束")
            return when (val char = text[index]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (char == '-' || char.isDigit()) numberValue() else {
                    throw IllegalArgumentException("非法 JSON 字符：$char")
                }
            }
        }

        private fun objectValue(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = stringValue()
                skipWhitespace()
                expect(':')
                result[key] = value()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("对象缺少 , 或 }")
                }
            }
        }

        private fun arrayValue(): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return result
            }
            while (true) {
                result.add(value())
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("数组缺少 , 或 ]")
                }
            }
        }

        private fun stringValue(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (index >= text.length) throw IllegalArgumentException("字符串未闭合")
                val char = text[index++]
                when (char) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        if (index >= text.length) throw IllegalArgumentException("转义未闭合")
                        when (val escaped = text[index++]) {
                            '"', '\\', '/' -> builder.append(escaped)
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000c')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) throw IllegalArgumentException("\\u 转义不完整")
                                builder.append(text.substring(index, index + 4).toInt(16).toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("非法转义：\\$escaped")
                        }
                    }
                    else -> builder.append(char)
                }
            }
        }

        private fun numberValue(): Any {
            val start = index
            while (index < text.length) {
                val char = text[index]
                if (char.isDigit() || char == '-' || char == '+' || char == '.' || char == 'e' || char == 'E') {
                    index++
                } else {
                    break
                }
            }
            val token = text.substring(start, index)
            if (token.isEmpty()) throw IllegalArgumentException("数字为空")
            return if (token.any { it == '.' || it == 'e' || it == 'E' }) {
                token.toDoubleOrNull() ?: throw IllegalArgumentException("非法数字：$token")
            } else {
                token.toLongOrNull() ?: throw IllegalArgumentException("非法整数：$token")
            }
        }

        private fun literal(token: String, value: Any?): Any? {
            if (!text.startsWith(token, index)) throw IllegalArgumentException("非法字面量")
            index += token.length
            return value
        }

        private fun peek(): Char {
            if (index >= text.length) throw IllegalArgumentException("JSON 意外结束")
            return text[index]
        }

        private fun expect(char: Char) {
            if (peek() != char) throw IllegalArgumentException("期望 $char，实际 ${text[index]}")
            index++
        }
    }
}
