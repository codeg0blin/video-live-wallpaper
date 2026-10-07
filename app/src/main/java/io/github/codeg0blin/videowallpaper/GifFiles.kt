package io.github.codeg0blin.videowallpaper

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Helpers for recognising and reading GIF files picked through the system file picker. */
object GifFiles {

    /** Largest GIF file we will load into memory. */
    const val MAX_BYTES = 50 * 1024 * 1024

    /**
     * True if the first bytes of [uri]'s content are a GIF signature.
     * Sniffing the content (rather than trusting a MIME type) means files
     * with a wrong or missing type still work. Any failure to read counts as
     * "not a GIF" so the normal video path reports the problem.
     */
    fun looksLikeGif(resolver: ContentResolver, uri: Uri): Boolean {
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(6)
                var read = 0
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n < 0) break
                    read += n
                }
                read == header.size && GifDecoder.hasGifSignature(header)
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Reads the whole file into memory.
     *
     * @throws IOException if the file can't be opened or read
     * @throws GifFormatException if the file is larger than [MAX_BYTES]
     */
    @Throws(IOException::class)
    fun readBytes(resolver: ContentResolver, uri: Uri): ByteArray {
        val input = resolver.openInputStream(uri) ?: throw IOException("Could not open $uri")
        input.use {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw GifFormatException("GIF file is too large")
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }
}
