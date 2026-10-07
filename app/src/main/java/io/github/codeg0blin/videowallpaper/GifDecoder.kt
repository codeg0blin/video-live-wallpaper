package io.github.codeg0blin.videowallpaper

/** Thrown when a file is not a usable GIF (bad signature, truncated before any frame, too large, ...). */
class GifFormatException(message: String) : Exception(message)

/**
 * Minimal GIF87a/GIF89a decoder written from the public GIF89a specification.
 *
 * Deliberately has no android.* imports so it can be unit-tested on a plain JVM.
 *
 * Usage:
 *  1. [parse] the whole file's bytes. This walks the block structure and
 *     records where each frame's compressed data lives, but does not
 *     decompress anything yet, so it is cheap enough to use as a pick-time
 *     validity check.
 *  2. Call [advance] once per displayed frame. Each call composites the next
 *     frame onto [pixels] (applying the previous frame's disposal method
 *     first) and returns how long that frame should stay on screen in
 *     milliseconds. After the last frame it wraps back to the first, so
 *     looping is automatic. [rewind] restarts from frame 0.
 *
 * [pixels] is a row-major array of ARGB ints, [width] * [height] long, owned
 * and reused by the decoder, so copy it if you need to keep a frame.
 * Not thread-safe: use one instance from one thread.
 */
class GifDecoder private constructor(
    private val data: ByteArray,
    val width: Int,
    val height: Int,
    private val globalPalette: IntArray?,
    private val frames: List<Frame>
) {

    private class Frame(
        val left: Int,
        val top: Int,
        val w: Int,
        val h: Int,
        val interlaced: Boolean,
        val localPalette: IntArray?,
        /** Offset of the LZW minimum-code-size byte for this frame's data. */
        val dataOffset: Int,
        val disposal: Int,
        val transparentIndex: Int,
        val delayMs: Int
    )

    val frameCount: Int get() = frames.size

    /** Composited canvas for the most recently advanced frame (ARGB, row-major). */
    val pixels = IntArray(width * height)

    private var nextIndex = 0
    private var snapshot: IntArray? = null

    // LZW scratch space, reused across frames.
    private val prefix = ShortArray(MAX_CODES)
    private val suffix = ByteArray(MAX_CODES)
    private val stack = ByteArray(MAX_CODES + 1)
    private val indexBuffer = ByteArray(frames.maxOf { it.w * it.h })

    /** Restarts playback so the next [advance] call produces frame 0. */
    fun rewind() {
        nextIndex = 0
    }

    /**
     * Composites the next frame into [pixels] and returns its display time in
     * milliseconds (always at least [MIN_DELAY_MS]). Wraps to the first frame
     * after the last one.
     */
    fun advance(): Int {
        val index = nextIndex
        if (index == 0) {
            pixels.fill(0)
        } else {
            disposePrevious(frames[index - 1])
        }
        val frame = frames[index]
        if (frame.disposal == DISPOSE_PREVIOUS) {
            val snap = snapshot ?: IntArray(pixels.size).also { snapshot = it }
            System.arraycopy(pixels, 0, snap, 0, pixels.size)
        }
        drawFrame(frame)
        nextIndex = if (index + 1 >= frames.size) 0 else index + 1
        return frame.delayMs
    }

    private fun disposePrevious(prev: Frame) {
        when (prev.disposal) {
            DISPOSE_BACKGROUND -> {
                val x0 = prev.left.coerceAtLeast(0)
                val x1 = (prev.left + prev.w).coerceAtMost(width)
                val y0 = prev.top.coerceAtLeast(0)
                val y1 = (prev.top + prev.h).coerceAtMost(height)
                if (x1 > x0) {
                    for (y in y0 until y1) {
                        java.util.Arrays.fill(pixels, y * width + x0, y * width + x1, 0)
                    }
                }
            }
            DISPOSE_PREVIOUS -> {
                snapshot?.let { System.arraycopy(it, 0, pixels, 0, pixels.size) }
            }
            // 0 (unspecified) and 1 (do not dispose): leave the canvas as is.
        }
    }

    private fun drawFrame(f: Frame) {
        val palette = f.localPalette ?: globalPalette ?: return
        val decoded = decodeLzw(f.dataOffset, f.w * f.h)
        if (decoded <= 0) return

        val fw = f.w
        val rows = (decoded + fw - 1) / fw
        for (r in 0 until rows) {
            val destY = f.top + if (f.interlaced) interlacedRowToY(r, f.h) else r
            if (destY < 0 || destY >= height) continue
            val rowPixels = minOf(fw, decoded - r * fw)
            val srcBase = r * fw
            for (x in 0 until rowPixels) {
                val destX = f.left + x
                if (destX < 0 || destX >= width) continue
                val idx = indexBuffer[srcBase + x].toInt() and 0xFF
                if (idx == f.transparentIndex || idx >= palette.size) continue
                pixels[destY * width + destX] = palette[idx]
            }
        }
    }

    /**
     * Decompresses one frame's LZW data into [indexBuffer] and returns how
     * many pixels were produced. A truncated or corrupt stream returns
     * whatever decoded cleanly before the problem, which matches how
     * browsers show damaged GIFs (partial frame rather than nothing).
     */
    private fun decodeLzw(startPos: Int, total: Int): Int {
        val d = data
        var pos = startPos
        val minCode = d[pos++].toInt() and 0xFF
        if (minCode < 2 || minCode > 8) return 0

        val clear = 1 shl minCode
        val eoi = clear + 1
        var codeSize = minCode + 1
        var codeMask = (1 shl codeSize) - 1
        var avail = clear + 2
        var oldCode = -1
        var first = 0
        for (i in 0 until clear) {
            prefix[i] = 0
            suffix[i] = i.toByte()
        }

        var datum = 0
        var bits = 0
        var blockLeft = 0
        var n = 0
        var top = 0

        outer@ while (n < total) {
            while (bits < codeSize) {
                if (blockLeft == 0) {
                    if (pos >= d.size) break@outer
                    blockLeft = d[pos++].toInt() and 0xFF
                    if (blockLeft == 0) break@outer
                }
                if (pos >= d.size) break@outer
                datum = datum or ((d[pos++].toInt() and 0xFF) shl bits)
                bits += 8
                blockLeft--
            }
            val code = datum and codeMask
            datum = datum ushr codeSize
            bits -= codeSize

            if (code == clear) {
                codeSize = minCode + 1
                codeMask = (1 shl codeSize) - 1
                avail = clear + 2
                oldCode = -1
                continue
            }
            if (code == eoi) break

            if (oldCode == -1) {
                if (code >= clear) break
                indexBuffer[n++] = suffix[code]
                oldCode = code
                first = code
                continue
            }

            var cur = code
            if (cur > avail) break // corrupt stream
            if (cur == avail) {
                stack[top++] = first.toByte()
                cur = oldCode
            }
            while (cur >= clear) {
                stack[top++] = suffix[cur]
                cur = prefix[cur].toInt()
            }
            first = suffix[cur].toInt() and 0xFF
            stack[top++] = first.toByte()

            if (avail < MAX_CODES) {
                prefix[avail] = oldCode.toShort()
                suffix[avail] = first.toByte()
                avail++
                if ((avail and codeMask) == 0 && avail < MAX_CODES) {
                    codeSize++
                    codeMask = (1 shl codeSize) - 1
                }
            }
            oldCode = code

            while (top > 0 && n < total) {
                indexBuffer[n++] = stack[--top]
            }
            top = 0
        }
        return n
    }

    /** Maps the r-th stored row of an interlaced image to its real row. */
    private fun interlacedRowToY(r: Int, h: Int): Int {
        val pass1 = (h + 7) / 8
        if (r < pass1) return r * 8
        var rr = r - pass1
        val pass2 = (h + 3) / 8
        if (rr < pass2) return 4 + rr * 8
        rr -= pass2
        val pass3 = (h + 1) / 4
        if (rr < pass3) return 2 + rr * 4
        rr -= pass3
        return 1 + rr * 2
    }

    private class Reader(val d: ByteArray) {
        var pos = 0

        fun u8(): Int {
            if (pos >= d.size) throw GifFormatException("Unexpected end of file")
            return d[pos++].toInt() and 0xFF
        }

        fun u16(): Int {
            val lo = u8()
            return lo or (u8() shl 8)
        }

        fun skip(n: Int) {
            if (n < 0 || pos + n > d.size) throw GifFormatException("Unexpected end of file")
            pos += n
        }

        fun skipSubBlocks() {
            while (true) {
                val n = u8()
                if (n == 0) return
                skip(n)
            }
        }

        fun palette(entries: Int): IntArray {
            if (pos + entries * 3 > d.size) throw GifFormatException("Unexpected end of file")
            val p = IntArray(entries)
            for (i in 0 until entries) {
                val r = d[pos++].toInt() and 0xFF
                val g = d[pos++].toInt() and 0xFF
                val b = d[pos++].toInt() and 0xFF
                p[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            return p
        }
    }

    companion object {
        /** Frames shorter than this are bumped up, matching browser behaviour for 0/10ms delays. */
        const val MIN_DELAY_MS = 100

        /** Default cap on canvas size (width * height) to keep memory use sane. */
        const val DEFAULT_MAX_PIXELS = 16_000_000

        private const val MAX_CODES = 4096
        private const val DISPOSE_BACKGROUND = 2
        private const val DISPOSE_PREVIOUS = 3

        /** True if [bytes] starts with the GIF87a or GIF89a signature. */
        fun hasGifSignature(bytes: ByteArray, length: Int = bytes.size): Boolean {
            if (length < 6 || bytes.size < 6) return false
            val sig = String(bytes, 0, 6, Charsets.ISO_8859_1)
            return sig == "GIF87a" || sig == "GIF89a"
        }

        /**
         * Parses the structure of a complete GIF file held in [data].
         *
         * @throws GifFormatException if it is not a GIF, is too large, or
         * contains no complete frame. A file truncated after at least one
         * complete frame is accepted with the frames that survived.
         */
        fun parse(data: ByteArray, maxPixels: Int = DEFAULT_MAX_PIXELS): GifDecoder {
            if (!hasGifSignature(data)) throw GifFormatException("Not a GIF file")
            val r = Reader(data)
            r.pos = 6

            val width = r.u16()
            val height = r.u16()
            if (width <= 0 || height <= 0) throw GifFormatException("GIF has zero size")
            if (width.toLong() * height > maxPixels) throw GifFormatException("GIF is too large")
            val screenPacked = r.u8()
            r.u8() // background colour index (unused: we composite onto transparent)
            r.u8() // pixel aspect ratio (unused)
            val globalPalette = if (screenPacked and 0x80 != 0) {
                r.palette(2 shl (screenPacked and 7))
            } else null

            val frames = ArrayList<Frame>()
            var gceDisposal = 0
            var gceTransparent = -1
            var gceDelayMs = 0

            try {
                loop@ while (true) {
                    when (r.u8()) {
                        0x3B -> break@loop // trailer
                        0x00 -> Unit // stray padding byte
                        0x21 -> {
                            val label = r.u8()
                            if (label == 0xF9) {
                                val size = r.u8()
                                if (size >= 4) {
                                    val packed = r.u8()
                                    gceDelayMs = r.u16() * 10
                                    val transIdx = r.u8()
                                    gceDisposal = (packed shr 2) and 7
                                    gceTransparent = if (packed and 1 != 0) transIdx else -1
                                    r.skip(size - 4)
                                } else {
                                    r.skip(size)
                                }
                            }
                            r.skipSubBlocks()
                        }
                        0x2C -> {
                            val left = r.u16()
                            val top = r.u16()
                            val w = r.u16()
                            val h = r.u16()
                            val packed = r.u8()
                            val local = if (packed and 0x80 != 0) {
                                r.palette(2 shl (packed and 7))
                            } else null
                            val dataOffset = r.pos
                            r.u8() // LZW minimum code size
                            r.skipSubBlocks()
                            if (w > 0 && h > 0 && w.toLong() * h <= maxPixels) {
                                frames.add(
                                    Frame(
                                        left, top, w, h,
                                        interlaced = packed and 0x40 != 0,
                                        localPalette = local,
                                        dataOffset = dataOffset,
                                        disposal = gceDisposal,
                                        transparentIndex = gceTransparent,
                                        delayMs = if (gceDelayMs <= 10) MIN_DELAY_MS else gceDelayMs
                                    )
                                )
                            }
                            gceDisposal = 0
                            gceTransparent = -1
                            gceDelayMs = 0
                        }
                        else -> throw GifFormatException("Unexpected block in GIF")
                    }
                }
            } catch (e: GifFormatException) {
                // Keep frames read before the damage; fail only if there are none.
                if (frames.isEmpty()) throw e
            }

            if (frames.isEmpty()) throw GifFormatException("GIF has no frames")
            return GifDecoder(data, width, height, globalPalette, frames)
        }
    }
}
