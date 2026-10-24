package com.shawn.casthub

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import fi.iki.elonen.NanoHTTPD
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
                else -> notFound()
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "error")
        }
    }

    private fun video(id: Long, session: IHTTPSession): Response {
        // Prefer the Chromecast-friendly converted copy if one exists.
        val converted = Converted.file(ctx, id)
        val mime: String
        val pfd: ParcelFileDescriptor
        if (Converted.exists(ctx, id)) {
            mime = "video/mp4"
            pfd = ParcelFileDescriptor.open(converted, ParcelFileDescriptor.MODE_READ_ONLY)
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

    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
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
