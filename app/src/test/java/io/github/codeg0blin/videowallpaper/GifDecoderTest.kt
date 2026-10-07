package io.github.codeg0blin.videowallpaper

import java.io.ByteArrayOutputStream
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Plain-JVM tests for [GifDecoder]. GIF files are built in code by the small
 * encoder at the bottom of this file, so the repository contains no binary
 * test assets.
 */
class GifDecoderTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val palette = List(256) { i -> Triple(i, (i * 7) and 0xFF, (i * 13) and 0xFF) }
    private fun colorOf(idx: Int) = palette[idx].let { argb(it.first, it.second, it.third) }

    // --- Signature / rejection ------------------------------------------

    @Test
    fun signatureDetection() {
        assertTrue(GifDecoder.hasGifSignature("GIF89a....".toByteArray()))
        assertTrue(GifDecoder.hasGifSignature("GIF87a....".toByteArray()))
        assertFalse(GifDecoder.hasGifSignature("GIF90a....".toByteArray()))
        assertFalse(GifDecoder.hasGifSignature("GIF".toByteArray()))
        assertFalse(GifDecoder.hasGifSignature(ByteArray(0)))
    }

    @Test
    fun rejectsNonGifAndEmpty() {
        expectFormatError { GifDecoder.parse(ByteArray(0)) }
        expectFormatError { GifDecoder.parse("not a gif at all".toByteArray()) }
    }

    @Test
    fun rejectsHeaderOnlyFile() {
        val full = TestGif.build(4, 4, palette, listOf(TestGif.Frame(0, 0, 4, 4, IntArray(16))))
        expectFormatError { GifDecoder.parse(full.copyOf(20)) }
    }

    @Test
    fun rejectsOversizedCanvas() {
        val f = TestGif.build(100, 100, palette, listOf(TestGif.Frame(0, 0, 100, 100, IntArray(10000))))
        expectFormatError { GifDecoder.parse(f, maxPixels = 5_000) }
    }

    // --- Decoding correctness -------------------------------------------

    @Test
    fun singleFrameDecodesExactly() {
        val idx = IntArray(12) { it }
        val gif = TestGif.build(4, 3, palette, listOf(TestGif.Frame(0, 0, 4, 3, idx)))
        val d = GifDecoder.parse(gif)
        assertEquals(1, d.frameCount)
        d.advance()
        assertArrayEquals(IntArray(12) { colorOf(it) }, d.pixels)
    }

    @Test
    fun largeNoisyFrameExercisesCodeSizeGrowthAndTableReset() {
        val rnd = Random(1)
        val w = 300
        val h = 200
        val idx = IntArray(w * h) { rnd.nextInt(256) }
        val d = GifDecoder.parse(TestGif.build(w, h, palette, listOf(TestGif.Frame(0, 0, w, h, idx))))
        d.advance()
        assertArrayEquals(IntArray(w * h) { colorOf(idx[it]) }, d.pixels)
    }

    @Test
    fun highlyCompressibleFrameDecodes() {
        val w = 200
        val h = 100
        val idx = IntArray(w * h) { 5 }
        val d = GifDecoder.parse(TestGif.build(w, h, palette, listOf(TestGif.Frame(0, 0, w, h, idx))))
        d.advance()
        assertTrue(d.pixels.all { it == colorOf(5) })
    }

    @Test
    fun interlacedRowsLandInTheRightPlaces() {
        for (h in intArrayOf(1, 2, 3, 5, 8, 9, 17, 33)) {
            val w = 3
            val idx = IntArray(w * h) { (it / w) * 3 + (it % w) } // unique value per row
            val frame = TestGif.Frame(0, 0, w, h, idx, interlace = true)
            val d = GifDecoder.parse(TestGif.build(w, h, palette, listOf(frame)))
            d.advance()
            assertArrayEquals("height $h", IntArray(w * h) { colorOf(idx[it]) }, d.pixels)
        }
    }

    @Test
    fun localPaletteOverridesGlobal() {
        val local = List(4) { Triple(200 + it, 0, 0) }
        val frame = TestGif.Frame(0, 0, 2, 2, intArrayOf(0, 1, 2, 3), localPalette = local)
        val d = GifDecoder.parse(TestGif.build(2, 2, palette, listOf(frame)))
        d.advance()
        assertArrayEquals(intArrayOf(argb(200, 0, 0), argb(201, 0, 0), argb(202, 0, 0), argb(203, 0, 0)), d.pixels)
    }

    @Test
    fun framesLargerThanCanvasAreClipped() {
        val frame = TestGif.Frame(2, 1, 6, 6, IntArray(36) { 7 })
        val d = GifDecoder.parse(TestGif.build(4, 3, palette, listOf(frame)))
        d.advance()
        val expected = IntArray(12) { i -> if (i % 4 >= 2 && i / 4 >= 1) colorOf(7) else 0 }
        assertArrayEquals(expected, d.pixels)
    }

    // --- Compositing / disposal -----------------------------------------

    @Test
    fun transparentPixelsKeepWhatWasUnderneath() {
        val base = TestGif.Frame(0, 0, 2, 1, intArrayOf(1, 1), disposal = 1)
        val over = TestGif.Frame(0, 0, 2, 1, intArrayOf(0, 2), disposal = 1, transparent = 0)
        val d = GifDecoder.parse(TestGif.build(2, 1, palette, listOf(base, over)))
        d.advance()
        d.advance()
        assertArrayEquals(intArrayOf(colorOf(1), colorOf(2)), d.pixels)
    }

    @Test
    fun disposalBackgroundClearsOnlyThatFramesRectangle() {
        val a = TestGif.Frame(0, 0, 4, 1, intArrayOf(1, 1, 1, 1), disposal = 1)
        val b = TestGif.Frame(1, 0, 2, 1, intArrayOf(2, 2), disposal = 2)
        val c = TestGif.Frame(3, 0, 1, 1, intArrayOf(3), disposal = 1)
        val d = GifDecoder.parse(TestGif.build(4, 1, palette, listOf(a, b, c)))
        d.advance()
        d.advance()
        assertArrayEquals(intArrayOf(colorOf(1), colorOf(2), colorOf(2), colorOf(1)), d.pixels)
        d.advance() // frame b is disposed to background, then c is drawn
        assertArrayEquals(intArrayOf(colorOf(1), 0, 0, colorOf(3)), d.pixels)
    }

    @Test
    fun disposalPreviousRestoresCanvasBeforeThatFrame() {
        val a = TestGif.Frame(0, 0, 4, 1, intArrayOf(1, 1, 1, 1), disposal = 1)
        val b = TestGif.Frame(1, 0, 2, 1, intArrayOf(2, 2), disposal = 3)
        val c = TestGif.Frame(3, 0, 1, 1, intArrayOf(3), disposal = 1)
        val d = GifDecoder.parse(TestGif.build(4, 1, palette, listOf(a, b, c)))
        d.advance()
        d.advance()
        assertArrayEquals(intArrayOf(colorOf(1), colorOf(2), colorOf(2), colorOf(1)), d.pixels)
        d.advance() // b is rolled back to a's result, then c is drawn
        assertArrayEquals(intArrayOf(colorOf(1), colorOf(1), colorOf(1), colorOf(3)), d.pixels)
    }

    // --- Timing / looping -----------------------------------------------

    @Test
    fun shortDelaysAreBumpedLikeBrowsersDo() {
        val frames = listOf(
            TestGif.Frame(0, 0, 1, 1, intArrayOf(0), delayCs = 0),
            TestGif.Frame(0, 0, 1, 1, intArrayOf(0), delayCs = 1),
            TestGif.Frame(0, 0, 1, 1, intArrayOf(0), delayCs = 2),
            TestGif.Frame(0, 0, 1, 1, intArrayOf(0), delayCs = 30)
        )
        val d = GifDecoder.parse(TestGif.build(1, 1, palette, frames))
        assertEquals(100, d.advance())
        assertEquals(100, d.advance())
        assertEquals(20, d.advance())
        assertEquals(300, d.advance())
    }

    @Test
    fun playbackWrapsAndRewindRestarts() {
        val frames = listOf(
            TestGif.Frame(0, 0, 1, 1, intArrayOf(1), disposal = 1),
            TestGif.Frame(0, 0, 1, 1, intArrayOf(2), disposal = 1)
        )
        val d = GifDecoder.parse(TestGif.build(1, 1, palette, frames))
        d.advance(); assertEquals(colorOf(1), d.pixels[0])
        d.advance(); assertEquals(colorOf(2), d.pixels[0])
        d.advance(); assertEquals(colorOf(1), d.pixels[0]) // wrapped
        d.advance(); assertEquals(colorOf(2), d.pixels[0])
        d.rewind()
        d.advance(); assertEquals(colorOf(1), d.pixels[0])
    }

    // --- Damaged files --------------------------------------------------

    @Test
    fun truncationAfterAFirstCompleteFrameKeepsThatFrame() {
        val frames = listOf(
            TestGif.Frame(0, 0, 4, 4, IntArray(16) { 3 }),
            TestGif.Frame(0, 0, 4, 4, IntArray(16) { 4 })
        )
        val full = TestGif.build(4, 4, palette, frames)
        val firstFrameEnd = TestGif.build(4, 4, palette, frames.take(1)).size - 1 // minus trailer
        val d = GifDecoder.parse(full.copyOf(firstFrameEnd + 6))
        assertEquals(1, d.frameCount)
        d.advance()
        assertTrue(d.pixels.all { it == colorOf(3) })
    }

    @Test
    fun corruptCompressedDataNeverThrowsOnlyDegrades() {
        val rnd = Random(9)
        val idx = IntArray(60 * 40) { rnd.nextInt(256) }
        val good = TestGif.build(60, 40, palette, listOf(TestGif.Frame(0, 0, 60, 40, idx)))
        repeat(200) {
            val bad = good.copyOf()
            repeat(5) { bad[800 + rnd.nextInt(bad.size - 800)] = rnd.nextInt(256).toByte() }
            try {
                GifDecoder.parse(bad).advance()
            } catch (e: GifFormatException) {
                // acceptable: structure was damaged
            }
        }
    }

    private fun expectFormatError(block: () -> Unit) {
        try {
            block()
            fail("expected GifFormatException")
        } catch (e: GifFormatException) {
            // expected
        }
    }
}

/** Minimal GIF89a writer used only by the tests above. */
private object TestGif {

    class Frame(
        val left: Int,
        val top: Int,
        val w: Int,
        val h: Int,
        val indices: IntArray,
        val disposal: Int = 0,
        val transparent: Int = -1,
        val delayCs: Int = 10,
        val interlace: Boolean = false,
        val localPalette: List<Triple<Int, Int, Int>>? = null
    )

    private fun bitsFor(count: Int): Int {
        var bits = 1
        while ((1 shl bits) < count) bits++
        return bits
    }

    private fun ByteArrayOutputStream.u16(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.palette(p: List<Triple<Int, Int, Int>>, bits: Int) {
        for (i in 0 until (1 shl bits)) {
            val c = p.getOrNull(i) ?: Triple(0, 0, 0)
            write(c.first); write(c.second); write(c.third)
        }
    }

    fun build(
        width: Int,
        height: Int,
        globalPalette: List<Triple<Int, Int, Int>>,
        frames: List<Frame>
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray())
        out.u16(width); out.u16(height)
        val gBits = bitsFor(globalPalette.size)
        out.write(0x80 or (gBits - 1)); out.write(0); out.write(0)
        out.palette(globalPalette, gBits)

        for (f in frames) {
            out.write(0x21); out.write(0xF9); out.write(4)
            out.write((f.disposal shl 2) or (if (f.transparent >= 0) 1 else 0))
            out.u16(f.delayCs)
            out.write(maxOf(f.transparent, 0))
            out.write(0)

            out.write(0x2C)
            out.u16(f.left); out.u16(f.top); out.u16(f.w); out.u16(f.h)
            val lp = f.localPalette
            val lBits = if (lp != null) bitsFor(lp.size) else gBits
            out.write((if (lp != null) 0x80 or (lBits - 1) else 0) or (if (f.interlace) 0x40 else 0))
            if (lp != null) out.palette(lp, lBits)

            var indices = f.indices
            if (f.interlace) {
                val order = (0 until f.h step 8) + (4 until f.h step 8) + (2 until f.h step 4) + (1 until f.h step 2)
                indices = order.flatMap { r -> (0 until f.w).map { c -> f.indices[r * f.w + c] } }.toIntArray()
            }
            out.write(lzw(indices, maxOf(2, lBits)))
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    private class BitWriter {
        val out = ByteArrayOutputStream()
        private var cur = 0
        private var n = 0
        fun write(code: Int, size: Int) {
            cur = cur or (code shl n)
            n += size
            while (n >= 8) {
                out.write(cur and 0xFF); cur = cur ushr 8; n -= 8
            }
        }
        fun finish(): ByteArray {
            if (n > 0) { out.write(cur and 0xFF); cur = 0; n = 0 }
            return out.toByteArray()
        }
    }

    /** Standard GIF LZW compression, returned as min-code-size byte + sub-blocks + terminator. */
    fun lzw(indices: IntArray, minCode: Int): ByteArray {
        val clear = 1 shl minCode
        val eoi = clear + 1
        val bw = BitWriter()
        var size = minCode + 1
        var next = eoi + 1
        val dict = HashMap<Int, Int>()
        bw.write(clear, size)
        var prefix = indices[0]
        for (i in 1 until indices.size) {
            val c = indices[i]
            val key = (prefix shl 8) or c
            val hit = dict[key]
            if (hit != null) { prefix = hit; continue }
            bw.write(prefix, size)
            if (next >= (1 shl size) && size < 12) size++
            if (next < 4096) {
                dict[key] = next++
            } else {
                bw.write(clear, size)
                dict.clear()
                next = eoi + 1
                size = minCode + 1
            }
            prefix = c
        }
        bw.write(prefix, size)
        if (next >= (1 shl size) && size < 12) size++
        bw.write(eoi, size)
        val data = bw.finish()

        val out = ByteArrayOutputStream()
        out.write(minCode)
        var pos = 0
        while (pos < data.size) {
            val len = minOf(255, data.size - pos)
            out.write(len)
            out.write(data, pos, len)
            pos += len
        }
        out.write(0)
        return out.toByteArray()
    }
}
