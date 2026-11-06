package com.lepao.companion.run

import com.lepao.companion.core.Protocol
import com.lepao.companion.core.StateException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * §6.3 of `docs/run-engine.md`: encrypted route body, plain `y9.c` sensor body and the pure-Kotlin
 * PNG screenshot, all produced without an Android runtime.
 */
class RouteArtifactsTest {

    private val plan = RouteTestSupport.plan()
    private val corridor by lazy { RouteTestSupport.corridorPoints() }

    private fun artifacts(seed: Long = RouteTestSupport.SEED, durationSec: Int = RouteTestSupport.DURATION) =
        RunArtifacts.build(
            corridor,
            RouteTestSupport.plan(seed = seed, durationSec = durationSec),
            RouteTestSupport.START_EPOCH,
        )

    @Test
    fun routeBodyDecryptsToTheWriterSchema() {
        val route = artifacts()["route"]!!
        val plaintext = Protocol.decryptText(String(route.body, Charsets.US_ASCII))
        assertTrue("轨迹体末尾必须有换行", plaintext.endsWith("\n"))
        assertFalse("轨迹体必须紧凑无空格", plaintext.contains(' '))

        val samples = RouteTestJson.parse(plaintext) as List<*>
        assertEquals("route.samples 必须等于点数", route.samples, samples.size)
        assertEquals(371, samples.size)

        var extraCount = 0
        var passCount = 0
        samples.forEachIndexed { index, item ->
            val sample = item as Map<*, *>
            val keys = sample.keys.map { it as String }.toSet()
            assertTrue("缺少必需字段：$keys", keys.containsAll(setOf("o", "a", "s", "l")))
            assertTrue("未知字段：$keys", keys.all { it in setOf("o", "a", "s", "l", "b", "c", "p") })
            assertEquals("l 必须为 1", 1L, sample["l"])

            val longitude = (sample["o"] as Number).toDouble()
            val latitude = (sample["a"] as Number).toDouble()
            val speed = (sample["s"] as Number).toDouble()
            assertTrue("经度超出范围：$longitude", longitude in -180.0..180.0)
            assertTrue("纬度超出范围：$latitude", latitude in -90.0..90.0)
            assertTrue("速度必须为正：$speed", speed > 0.0 && speed < 10.0)
            assertTrue("速度必须是有限值", speed.isFinite())
            // The corridor is a Beijing campus loop; the wobble must not move us out of it.
            assertTrue("经度偏离走廊：$longitude", longitude in 116.33..116.36)
            assertTrue("纬度偏离走廊：$latitude", latitude in 39.94..39.96)

            if ("b" in keys) {
                extraCount++
                assertTrue("b 必须与 c 同时出现", "c" in keys)
                val b = (sample["b"] as Number).toDouble()
                assertTrue("b 超出范围：$b", b in 0.99..1.02)
                assertTrue(
                    "c 不在观测集合内：${sample["c"]}",
                    (sample["c"] as Number).toInt() in setOf(338, 352, 366, 407),
                )
            }
            if ("c" in keys) assertTrue("c 必须与 b 同时出现", "b" in keys)
            if ("p" in keys) {
                passCount++
                assertEquals("p 只出现在 n-2 处", samples.size - 2, index)
                assertEquals(1L, sample["p"])
            }
        }
        assertEquals("随机加成点固定 3 个", 3, extraCount)
        assertEquals("p 标记固定 1 个", 1, passCount)
    }

    @Test
    fun routeBodyIsTheNewGameCiphertext() {
        val route = artifacts()["route"]!!
        val ciphertext = String(route.body, Charsets.US_ASCII)
        assertFalse("轨迹体不能是明文 JSON", ciphertext.trimStart().startsWith("["))
        val plaintext = Protocol.decryptText(ciphertext)
        // Same profile, same IV: re-encrypting the recovered plaintext must be byte identical.
        assertEquals(ciphertext, Protocol.encryptText(plaintext, "NEW_GAME"))
        // Base64 MIME envelope: 76 columns plus a trailing newline.
        assertTrue(ciphertext.endsWith("\n"))
        assertTrue(ciphertext.lineSequence().filter { it.isNotEmpty() }.all { it.length <= 76 })
    }

    @Test
    fun sensorMatchesTheY9Schema() {
        val sensor = artifacts()["sensor"]!!
        val text = String(sensor.body, Charsets.UTF_8)
        assertTrue("传感器体必须是明文数组", text.startsWith("["))
        assertTrue(text.endsWith("\n"))
        assertFalse("传感器体必须紧凑无空格", text.contains(' '))

        val rows = RouteTestJson.parse(text) as List<*>
        assertEquals("1 Hz 共 duration+1 条", RouteTestSupport.DURATION + 1, rows.size)
        assertEquals(rows.size, sensor.samples)
        assertTrue("客户端只写多于 50 条的文件", rows.size > 50)

        val numberPattern = Regex("^-?\\d+\\.\\d{6}$")
        val decimalPattern = Regex("^-?\\d+\\.\\d{2}$")
        rows.forEachIndexed { second, item ->
            val row = item as Map<*, *>
            assertEquals(
                "y9.c 字段集合",
                setOf("n", "a", "t", "ax", "ay", "az", "gx", "gy", "gz"),
                row.keys.map { it as String }.toSet(),
            )
            assertEquals(
                "t 必须是从 startEpoch 起的毫秒整数",
                RouteTestSupport.START_EPOCH * 1000L + second * 1000L,
                (row["t"] as Number).toLong(),
            )
            assertTrue("n 必须是 6 位小数字符串：${row["n"]}", numberPattern.matches(row["n"] as String))
            assertTrue("a 必须是 6 位小数字符串：${row["a"]}", numberPattern.matches(row["a"] as String))
            for (key in listOf("ax", "ay", "az", "gx", "gy", "gz")) {
                val value = row[key] as String
                assertTrue("$key 必须是 2 位小数字符串：$value", decimalPattern.matches(value))
                assertTrue("$key 必须是有限值", value.toDouble().isFinite())
            }
            // The accelerometer Z channel carries the ~9.46 gravity baseline.
            assertTrue("az 应接近重力加速度：${row["az"]}", (row["az"] as String).toDouble() in 8.0..11.0)
        }

        val first = rows.first() as Map<*, *>
        val track = RouteTestSupport.track()
        assertEquals(
            String.format(java.util.Locale.ROOT, "%.6f", track.first().lat),
            first["a"],
        )
        assertEquals(
            String.format(java.util.Locale.ROOT, "%.6f", track.first().lng),
            first["n"],
        )
    }

    @Test
    fun screenshotIsAValid720x1080Png() {
        val screenshot = artifacts()["screenshot"]!!
        assertEquals(0, screenshot.samples)
        val chunks = readChunks(screenshot.body)

        val header = chunks.first().data
        assertEquals(13, header.size)
        assertEquals(720, readInt(header, 0))
        assertEquals(1080, readInt(header, 4))
        assertEquals(8, header[8].toInt()) // bit depth
        assertEquals(2, header[9].toInt()) // truecolour RGB
        assertEquals(0, header[10].toInt())
        assertEquals(0, header[11].toInt())
        assertEquals(0, header[12].toInt())

        val raw = inflate(chunks)
        val stride = 1 + 720 * 3
        assertEquals(1080 * stride, raw.size)
        for (row in 0 until 1080) {
            assertEquals("每行必须使用 filter 0", 0, raw[row * stride].toInt())
        }

        val colors = countColors(raw, stride)
        assertTrue("背景必须占多数：$colors", colors.background > 720 * 1080 / 2)
        assertTrue("必须画出蓝色轨迹：$colors", colors.blue > 1000)
        assertTrue("必须画出白色底线：$colors", colors.white > 1000)
        assertTrue("必须有绿色起点：$colors", colors.green > 50)
        assertTrue("必须有红色终点：$colors", colors.red > 50)
        assertTrue("必须有网格线：$colors", colors.grid > 5000)
        assertEquals(BACKGROUND, pixelAt(raw, stride, 5, 5))

        // The corridor is an open polyline: the start and end markers must be visibly apart. A
        // wrapped-lap implementation put them on top of each other, hiding the green dot.
        val startCenter = colorCentroid(raw, stride, START_DOT) ?: error("缺少绿色起点标记")
        val endCenter = colorCentroid(raw, stride, END_DOT) ?: error("缺少红色终点标记")
        val dx = endCenter.first - startCenter.first
        val dy = endCenter.second - startCenter.second
        val separation = kotlin.math.sqrt(dx * dx + dy * dy)
        assertTrue("起终点标记必须分开，实际相距 $separation px", separation > 100.0)

        // Keep a copy in the build directory for optional visual inspection.
        val output = File("build/route-test/screenshot.png")
        output.parentFile?.mkdirs()
        output.writeBytes(screenshot.body)
    }

    @Test
    fun sameSeedProducesByteIdenticalArtifacts() {
        val first = artifacts()
        val second = artifacts()
        for (kind in listOf("screenshot", "route", "sensor")) {
            assertArrayEquals("$kind 必须逐字节一致", first[kind]!!.body, second[kind]!!.body)
            assertEquals("$kind sha256 必须一致", first[kind]!!.sha256, second[kind]!!.sha256)
        }
    }

    @Test
    fun differentSeedsProduceDifferentBodies() {
        val first = artifacts(seed = 1000L)
        val second = artifacts(seed = 1001L)
        assertFalse(first["route"]!!.body.contentEquals(second["route"]!!.body))
        assertFalse(first["sensor"]!!.body.contentEquals(second["sensor"]!!.body))
        assertFalse(first["route"]!!.sha256 == second["route"]!!.sha256)
    }

    @Test
    fun artifactMetadataMatchesTheBodies() {
        val built = artifacts()
        assertEquals(setOf("screenshot", "route", "sensor"), built.keys)
        assertEquals(
            listOf("screenshot", "route", "sensor"),
            built.keys.toList(),
        )
        assertEquals("screenshot", built["screenshot"]!!.kind.name)
        assertEquals("route", built["route"]!!.kind.name)
        assertEquals("sensor", built["sensor"]!!.kind.name)
        assertEquals(371, built["route"]!!.samples)
        assertEquals(RouteTestSupport.DURATION + 1, built["sensor"]!!.samples)

        for (blob in built.values) {
            assertTrue("body 不能为空", blob.body.isNotEmpty())
            val expected = MessageDigest.getInstance("SHA-256").digest(blob.body)
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, blob.sha256)
            assertEquals(64, blob.sha256.length)
        }
        assertTrue(built.keys.toList().containsAll(RunArtifacts.kindsFor(false).map { it.name }))
        assertEquals(3, RunArtifacts.kindsFor(true).size)
    }

    @Test
    fun shortDurationIsRejectedBeforeWritingAnInvalidSensorFile() {
        assertThrows(StateException::class.java) {
            artifacts(durationSec = 40)
        }
    }

    // --- PNG helpers -------------------------------------------------------------------------

    private data class PngChunk(val type: String, val data: ByteArray, val crc: Long)

    private data class ColorCounts(
        val background: Int,
        val grid: Int,
        val white: Int,
        val blue: Int,
        val green: Int,
        val red: Int,
    )

    private fun readChunks(png: ByteArray): List<PngChunk> {
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        assertArrayEquals("PNG 签名", signature, png.copyOfRange(0, 8))
        var offset = 8
        val chunks = ArrayList<PngChunk>()
        while (offset < png.size) {
            val length = readInt(png, offset)
            offset += 4
            val type = String(png, offset, 4, Charsets.US_ASCII)
            offset += 4
            assertTrue("chunk 越界：$type", length >= 0 && offset + length + 4 <= png.size)
            val data = png.copyOfRange(offset, offset + length)
            offset += length
            val crc = readInt(png, offset).toLong() and 0xFFFFFFFFL
            offset += 4
            val computed = CRC32()
            computed.update(type.toByteArray(Charsets.US_ASCII))
            computed.update(data)
            assertEquals("CRC 必须正确：$type", crc, computed.value)
            chunks.add(PngChunk(type, data, crc))
        }
        assertEquals("IHDR", chunks.first().type)
        assertEquals("IEND", chunks.last().type)
        return chunks
    }

    private fun inflate(chunks: List<PngChunk>): ByteArray {
        val compressed = ByteArrayOutputStream()
        for (chunk in chunks) {
            if (chunk.type == "IDAT") compressed.write(chunk.data)
        }
        val inflater = Inflater()
        try {
            inflater.setInput(compressed.toByteArray())
            val output = ByteArrayOutputStream(2_400_000)
            val buffer = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val written = inflater.inflate(buffer)
                if (written == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                    continue
                }
                output.write(buffer, 0, written)
            }
            return output.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun pixelAt(raw: ByteArray, stride: Int, x: Int, y: Int): Int {
        val offset = y * stride + 1 + x * 3
        val red = raw[offset].toInt() and 0xFF
        val green = raw[offset + 1].toInt() and 0xFF
        val blue = raw[offset + 2].toInt() and 0xFF
        return (red shl 16) or (green shl 8) or blue
    }

    private fun countColors(raw: ByteArray, stride: Int): ColorCounts {
        var background = 0
        var grid = 0
        var white = 0
        var blue = 0
        var green = 0
        var red = 0
        for (y in 0 until 1080) {
            val row = y * stride + 1
            for (x in 0 until 720) {
                val offset = row + x * 3
                val color = ((raw[offset].toInt() and 0xFF) shl 16) or
                    ((raw[offset + 1].toInt() and 0xFF) shl 8) or
                    (raw[offset + 2].toInt() and 0xFF)
                when (color) {
                    BACKGROUND -> background++
                    GRID -> grid++
                    0xFFFFFF -> white++
                    0x266CCE -> blue++
                    0x34A853 -> green++
                    0xD63E3E -> red++
                }
            }
        }
        return ColorCounts(background, grid, white, blue, green, red)
    }

    /** Mean pixel position of one exact colour, or `null` when it never appears. */
    private fun colorCentroid(raw: ByteArray, stride: Int, target: Int): Pair<Double, Double>? {
        var sumX = 0.0
        var sumY = 0.0
        var count = 0
        for (y in 0 until 1080) {
            val row = y * stride + 1
            for (x in 0 until 720) {
                val offset = row + x * 3
                val color = ((raw[offset].toInt() and 0xFF) shl 16) or
                    ((raw[offset + 1].toInt() and 0xFF) shl 8) or
                    (raw[offset + 2].toInt() and 0xFF)
                if (color == target) {
                    sumX += x
                    sumY += y
                    count++
                }
            }
        }
        return if (count == 0) null else (sumX / count) to (sumY / count)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private companion object {
        const val BACKGROUND = 0xF1EFEA
        const val GRID = 0xE8E5DF
        const val START_DOT = 0x34A853
        const val END_DOT = 0xD63E3E
    }
}
