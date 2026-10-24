package com.shawn.casthub

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

/**
 * Tiny web server inside the app. The Chromecast downloads the movie from here while it plays,
 * with byte-range support so seeking works. URLs carry a random token so other devices on the
 * network can't browse your videos.
 */
class MediaServer(private val ctx: Context, port: Int, private val token: String) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val parts = session.uri.trim('/').split('/')
        if (parts.size != 3 || parts[1] != token) return notFound()
        val id = parts[2].substringBefore('.').toLongOrNull() ?: return notFound()
        return try {
            when (parts[0]) {
                "v" -> video(id, session)
                "s" -> subtitles(id)
                "t" -> image("t$id") { videoThumb(id) }
                "q" -> image("q$id") { photo(id, 320) }
                "p" -> image("p$id") { photo(id, 1280) }
                else -> notFound()
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "error")
        }
    }

    private fun video(id: Long, session: IHTTPSession): Response {
        // Prefer the Chromecast-friendly converted copy if one exists, then a live conversion in progress.
        val converted = Converted.file(ctx, id)
        val live = LiveConvert.file
        val mime: String
        val pfd: ParcelFileDescriptor
        if (Converted.exists(ctx, id)) {
            mime = "video/mp4"
            pfd = ParcelFileDescriptor.open(converted, ParcelFileDescriptor.MODE_READ_ONLY)
        } else if (LiveConvert.isLive(id) && live != null) {
            if (!LiveConvert.done) {
                // Still converting: stream the growing file. No byte ranges, so the TV plays it straight through.
                return newChunkedResponse(Response.Status.OK, "video/mp4", GrowingInputStream(live))
                    .apply { addHeader("Access-Control-Allow-Origin", "*") }
            }
            mime = "video/mp4"
            pfd = ParcelFileDescriptor.open(live, ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            mime = ctx.contentResolver.getType(uri) ?: "video/mp4"
            pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return notFound()
        }
        val size = pfd.statSize

        var start = 0L
        var end = size - 1
        var partial = false
        val range = session.headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").split(",")[0].trim()
            val a = spec.substringBefore('-')
            val b = spec.substringAfter('-', "")
            if (a.isEmpty()) {
                start = maxOf(0L, size - (b.toLongOrNull() ?: 0L))
            } else {
                start = a.toLongOrNull() ?: 0L
                if (b.isNotEmpty()) end = minOf(b.toLongOrNull() ?: end, size - 1)
            }
            partial = true
            if (start >= size || start > end) {
                pfd.close()
                return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                    .apply { addHeader("Content-Range", "bytes */$size") }
            }
        }

        val input = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        input.channel.position(start)
        val length = end - start + 1
        val res = newFixedLengthResponse(
            if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK, mime, input, length
        )
        res.addHeader("Accept-Ranges", "bytes")
        if (partial) res.addHeader("Content-Range", "bytes $start-$end/$size")
        res.addHeader("Access-Control-Allow-Origin", "*")
        return res
    }

    private fun subtitles(id: Long): Response {
        val uri = Subtitles.get(ctx, id) ?: return notFound()
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return notFound()
        return newFixedLengthResponse(Response.Status.OK, "text/vtt; charset=utf-8", Videos.srtToVtt(text))
            .apply { addHeader("Access-Control-Allow-Origin", "*") }
    }

    // ---------- images for the TV home screen ----------

    private fun image(key: String, make: () -> Bitmap?): Response {
        val bytes = cache.get(key) ?: run {
            val bmp = make() ?: return notFound()
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray().also { cache.put(key, it) }
        }
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong())
            .apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Cache-Control", "max-age=3600")
            }
    }

    private fun videoThumb(id: Long): Bitmap? {
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                ctx.contentResolver.loadThumbnail(uri, Size(512, 288), null)
            } else {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, uri)
                    r.getFrameAtTime(1_000_000)?.let { scaleDown(it, 512) }
                } finally {
                    r.release()
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun photo(id: Long, max: Int): Bitmap? {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                // ImageDecoder also applies the photo's rotation (EXIF)
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val scale = minOf(1.0, max.toDouble() / maxOf(w, h))
                    dec.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    ?.let { scaleDown(it, max) }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun scaleDown(b: Bitmap, max: Int): Bitmap {
        val big = maxOf(b.width, b.height)
        if (big <= max) return b
        val s = max.toFloat() / big
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }

    private val cache = object : LruCache<String, ByteArray>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
}

/** Reads a fragmented MP4 that is still being written, waiting for more data until conversion ends. */
class GrowingInputStream(file: File) : InputStream() {
    private val raf = RandomAccessFile(file, "r")
    private var pos = 0L
    private var safe = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        var waited = 0
        while (true) {
            safe = if (LiveConvert.done) raf.length() else maxOf(safe, LiveConvert.safeEnd(raf, safe))
            if (pos < safe) {
                raf.seek(pos)
                val n = raf.read(b, off, minOf(len.toLong(), safe - pos).toInt())
                if (n > 0) {
                    pos += n
                    return n
                }
            }
            if (LiveConvert.done && pos >= raf.length()) return -1
            if (LiveConvert.failed || waited > 120_000) return -1 // give up after 2 min with no new data
            Thread.sleep(250)
            waited += 250
        }
    }

    override fun close() = raf.close()
}

/** Keeps one server running for the whole app. */
object ServerHolder {
    val token: String = UUID.randomUUID().toString().replace("-", "").take(16)
    private var server: MediaServer? = null
    var port = 0
        private set

    @Synchronized
    fun ensure(ctx: Context): Int {
        server?.let { if (it.isAlive) return port }
        for (p in 8090..8110) {
            try {
                val s = MediaServer(ctx.applicationContext, p, token)
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = s
                port = p
                return p
            } catch (_: Exception) {
                // port busy, try the next one
            }
        }
        throw IllegalStateException("Couldn't start the streaming server")
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        port = 0
    }
}

/** Remembers which subtitle file goes with which video. */
object Subtitles {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("subtitles", Context.MODE_PRIVATE)
    fun get(ctx: Context, id: Long): Uri? = prefs(ctx).getString(id.toString(), null)?.let { Uri.parse(it) }
    fun set(ctx: Context, id: Long, uri: Uri?) {
        prefs(ctx).edit().apply { if (uri == null) remove(id.toString()) else putString(id.toString(), uri.toString()) }.apply()
    }
}

object Net {
    /** The phone's address on the home Wi-Fi (what the Chromecast connects back to). */
    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .filterNot { n -> listOf("rmnet", "ccmni", "dummy", "tun", "p2p").any { n.name.startsWith(it) } }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
