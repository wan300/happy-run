package com.lepao.companion.run

import com.lepao.companion.core.StateException
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Route screenshot rasteriser and PNG encoder written in plain Kotlin.
 *
 * The PNG byte stream is produced by hand (signature + IHDR + IDAT + IEND, DEFLATE payload,
 * CRC-32 per chunk) so the artefact is byte-stable and verifiable from JVM unit tests. Layout and
 * colours mirror `candidate.screenshot_png` in the Python reference (`docs/run-engine.md` §6.3):
 * 720x1080, `#F1EFEA` background, 60 px `#E8E5DF` grid, an 11 px white halo under a 6 px `#266CCE`
 * line, and 9 px start (`#34A853`) / end (`#D63E3E`) dots.
 */
object PngCanvas {

    const val WIDTH = 720
    const val HEIGHT = 1080

    private const val BACKGROUND = 0xF1EFEA
    private const val GRID = 0xE8E5DF
    private const val HALO = 0xFFFFFF
    private const val LINE = 0x266CCE
    private const val START = 0x34A853
    private const val END = 0xD63E3E

    private const val GRID_STEP = 60
    private const val MARGIN = 70.0
    private const val HALO_WIDTH = 11.0
    private const val LINE_WIDTH = 6.0
    private const val DOT_RADIUS = 9.0

    /** Render [track] to a 720x1080 PNG (RGB, 8 bit, non-interlaced). */
    fun screenshot(
        track: List<RouteEngine.Sample>,
        width: Int = WIDTH,
        height: Int = HEIGHT,
    ): ByteArray {
        if (track.isEmpty()) throw StateException("轨迹为空，无法生成截图")
        if (width <= 0 || height <= 0) throw StateException("截图尺寸必须为正")

        val canvas = Canvas(width, height)
        canvas.fill(BACKGROUND)
        canvas.grid(GRID_STEP, GRID)

        var latMin = Double.MAX_VALUE
        var latMax = -Double.MAX_VALUE
        var lngMin = Double.MAX_VALUE
        var lngMax = -Double.MAX_VALUE
        for (sample in track) {
            if (sample.lat < latMin) latMin = sample.lat
            if (sample.lat > latMax) latMax = sample.lat
            if (sample.lng < lngMin) lngMin = sample.lng
            if (sample.lng > lngMax) lngMax = sample.lng
        }
        val latSpan = max(latMax - latMin, 1e-5)
        val lngSpan = max(lngMax - lngMin, 1e-5)
        val ratio = min((width - 2 * MARGIN) / lngSpan, (height - 2 * MARGIN) / latSpan)
        val xOffset = (width - lngSpan * ratio) / 2.0
        val yOffset = (height - latSpan * ratio) / 2.0

        val count = track.size
        val xs = DoubleArray(count)
        val ys = DoubleArray(count)
        for (index in 0 until count) {
            xs[index] = xOffset + (track[index].lng - lngMin) * ratio
            ys[index] = yOffset + (latMax - track[index].lat) * ratio
        }

        if (count > 1) {
            for (index in 0 until count - 1) {
                canvas.thickSegment(xs[index], ys[index], xs[index + 1], ys[index + 1], HALO_WIDTH / 2.0, HALO)
            }
            for (index in 0 until count - 1) {
                canvas.thickSegment(xs[index], ys[index], xs[index + 1], ys[index + 1], LINE_WIDTH / 2.0, LINE)
            }
        }
        canvas.disk(xs[0], ys[0], DOT_RADIUS, START)
        canvas.disk(xs[count - 1], ys[count - 1], DOT_RADIUS, END)
        return canvas.png()
    }

    private class Canvas(private val width: Int, private val height: Int) {

        /** Packed 0xRRGGBB pixels, row-major. */
        private val pixels = IntArray(width * height)

        fun fill(color: Int) {
            java.util.Arrays.fill(pixels, color)
        }

        /** Single-pixel grid lines every [step] pixels, matching PIL `draw.line(width=1)`. */
        fun grid(step: Int, color: Int) {
            if (step <= 0) return
            var x = 0
            while (x < width) {
                for (y in 0 until height) pixels[y * width + x] = color
                x += step
            }
            var y = 0
            while (y < height) {
                val row = y * width
                for (column in 0 until width) pixels[row + column] = color
                y += step
            }
        }

        /** Thick line segment with round caps, approximated by a distance-to-segment test. */
        fun thickSegment(x0: Double, y0: Double, x1: Double, y1: Double, halfWidth: Double, color: Int) {
            if (halfWidth <= 0.0) return
            val dx = x1 - x0
            val dy = y1 - y0
            val lengthSquared = dx * dx + dy * dy
            val minX = max(0, floor(min(x0, x1) - halfWidth).toInt())
            val maxX = min(width - 1, ceil(max(x0, x1) + halfWidth).toInt())
            val minY = max(0, floor(min(y0, y1) - halfWidth).toInt())
            val maxY = min(height - 1, ceil(max(y0, y1) + halfWidth).toInt())
            if (minX > maxX || minY > maxY) return
            val limit = halfWidth * halfWidth
            for (y in minY..maxY) {
                val py = y + 0.5
                val row = y * width
                for (x in minX..maxX) {
                    val px = x + 0.5
                    val distanceSquared = if (lengthSquared <= 1e-12) {
                        val ax = px - x0
                        val ay = py - y0
                        ax * ax + ay * ay
                    } else {
                        var ratio = ((px - x0) * dx + (py - y0) * dy) / lengthSquared
                        ratio = ratio.coerceIn(0.0, 1.0)
                        val cx = x0 + dx * ratio
                        val cy = y0 + dy * ratio
                        val ax = px - cx
                        val ay = py - cy
                        ax * ax + ay * ay
                    }
                    if (distanceSquared <= limit) pixels[row + x] = color
                }
            }
        }

        /** Filled dot; [radius] in pixels. */
        fun disk(cx: Double, cy: Double, radius: Double, color: Int) {
            if (radius <= 0.0) return
            val minX = max(0, floor(cx - radius).toInt())
            val maxX = min(width - 1, ceil(cx + radius).toInt())
            val minY = max(0, floor(cy - radius).toInt())
            val maxY = min(height - 1, ceil(cy + radius).toInt())
            if (minX > maxX || minY > maxY) return
            val limit = radius * radius
            for (y in minY..maxY) {
                val py = y + 0.5
                val row = y * width
                for (x in minX..maxX) {
                    val px = x + 0.5
                    val ax = px - cx
                    val ay = py - cy
                    if (ax * ax + ay * ay <= limit) pixels[row + x] = color
                }
            }
        }

        /** Encode the buffer as an RGB PNG (filter type 0 on every scanline). */
        fun png(): ByteArray {
            val raw = ByteArray(height * (1 + width * 3))
            var position = 0
            for (y in 0 until height) {
                raw[position++] = 0
                val row = y * width
                for (x in 0 until width) {
                    val color = pixels[row + x]
                    raw[position++] = ((color shr 16) and 0xFF).toByte()
                    raw[position++] = ((color shr 8) and 0xFF).toByte()
                    raw[position++] = (color and 0xFF).toByte()
                }
            }

            val header = ByteArray(13)
            writeInt(header, 0, width)
            writeInt(header, 4, height)
            header[8] = 8 // bit depth
            header[9] = 2 // colour type: truecolour
            header[10] = 0 // compression: DEFLATE
            header[11] = 0 // filter method
            header[12] = 0 // interlace: none

            val output = ByteArrayOutputStream(raw.size / 2 + 1024)
            output.write(PNG_SIGNATURE)
            writeChunk(output, "IHDR", header)
            writeChunk(output, "IDAT", deflate(raw))
            writeChunk(output, "IEND", ByteArray(0))
            return output.toByteArray()
        }
    }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val output = ByteArrayOutputStream(raw.size / 4 + 1024)
            val buffer = ByteArray(64 * 1024)
            while (!deflater.finished()) {
                val written = deflater.deflate(buffer)
                if (written <= 0) break
                output.write(buffer, 0, written)
            }
            return output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun writeChunk(output: ByteArrayOutputStream, type: String, data: ByteArray) {
        writeInt(output, data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        output.write(typeBytes)
        output.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        writeInt(output, crc.value.toInt())
    }

    private fun writeInt(output: ByteArrayOutputStream, value: Int) {
        output.write((value ushr 24) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write(value and 0xFF)
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }
}
