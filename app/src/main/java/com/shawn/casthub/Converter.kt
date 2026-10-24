package com.shawn.casthub

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMuxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.RandomAccessFile

/** Where converted (Chromecast-friendly) copies live. Removed automatically if the app is uninstalled. */
object Converted {
    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "converted").apply { mkdirs() }

    fun file(ctx: Context, id: Long) = File(dir(ctx), "$id.mp4")
    fun liveFile(ctx: Context, id: Long) = File(dir(ctx), "$id.live.mp4")
    fun exists(ctx: Context, id: Long) = file(ctx, id).let { it.exists() && it.length() > 0 }

    /** Leftover live streams from earlier sessions (safe to delete: open readers keep their handle). */
    fun cleanLive(ctx: Context) {
        dir(ctx).listFiles()?.forEach { if (it.name.endsWith(".live.mp4") || it.name.endsWith(".part.mp4")) it.delete() }
    }

    fun clearAll(ctx: Context): Long {
        var freed = 0L
        dir(ctx).listFiles()?.forEach { if (it.id() != LiveConvert.id) { freed += it.length(); it.delete() } }
        return freed
    }

    private fun File.id(): Long = name.substringBefore('.').toLongOrNull() ?: -1
}

/**
 * State of a "play while converting" stream. The converter writes a fragmented MP4 (header first,
 * then ~2 s chunks), and the web server streams it to the Chromecast as it grows.
 */
object LiveConvert {
    @Volatile var id: Long = -1
        private set
    @Volatile var file: File? = null
        private set
    @Volatile var done = false
        private set
    @Volatile var failed = false
        private set

    fun start(id: Long, file: File) {
        this.id = id
        this.file = file
        done = false
        failed = false
    }

    fun finish(ok: Boolean) {
        if (ok) done = true else failed = true
    }

    fun clear() {
        id = -1
        file = null
    }

    fun isLive(id: Long) = this.id == id && file?.exists() == true && !failed

    /**
     * How many bytes from the start are final. Every complete box is final, except that each chunk's
     * mdat box is written with a placeholder size that's fixed right after its data, so we stop just
     * before an mdat that is still being written.
     */
    fun safeEnd(raf: RandomAccessFile, from: Long = 0L): Long {
        val len = raf.length()
        var o = from
        val type = ByteArray(4)
        while (o + 8 <= len) {
            raf.seek(o)
            val size = raf.readInt().toLong() and 0xffffffffL
            raf.readFully(type)
            if (size < 8) break
            if (type[0] == 'm'.code.toByte() && type[1] == 'd'.code.toByte() && type[2] == 'a'.code.toByte() &&
                type[3] == 't'.code.toByte() && size == 8L && o + 8 < len
            ) break // mdat still being filled
            if (o + size > len) break
            o += size
        }
        return o
    }

    fun safeBytes(): Long {
        val f = file ?: return 0
        if (!f.exists()) return 0
        return try {
            RandomAccessFile(f, "r").use { if (done) it.length() else safeEnd(it) }
        } catch (e: Exception) {
            0
        }
    }
}

/**
 * Converts H.265 / 4K / HDR phone videos into H.264 + AAC (max 1080p, SDR) that the 1st-gen
 * Chromecast can play, using the phone's hardware encoder. Must be used from the main thread.
 */
@OptIn(UnstableApi::class)
class Converter(context: Context) {
    private val ctx = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var current: Transformer? = null
    var currentId: Long = -1
        private set

    val isBusy get() = current != null

    /**
     * @param output where to write. With [fragmented] the file can be streamed while it's being written.
     */
    fun convert(
        v: Video,
        width: Int,
        height: Int,
        output: File,
        fragmented: Boolean,
        maxShortSide: Int,
        bitrate: Int,
        onProgress: (Int) -> Unit,
        onDone: (ok: Boolean, error: String?) -> Unit,
    ) {
        if (current != null) {
            onDone(false, "Another video is converting — wait for it to finish")
            return
        }
        output.delete()

        // Scale so the short side is at most maxShortSide (1080p for saved copies, 720p for live playback).
        val effects = if (width > 0 && height > 0 && minOf(width, height) > maxShortSide) {
            val p = if (width >= height) {
                Presentation.createForHeight(maxShortSide)
            } else {
                val h = ((maxShortSide.toLong() * height / width) / 2 * 2).toInt()
                Presentation.createForWidthAndHeight(maxShortSide, h, Presentation.LAYOUT_SCALE_TO_FIT)
            }
            Effects(emptyList(), listOf(p))
        } else {
            Effects.EMPTY
        }

        val edited = EditedMediaItem.Builder(MediaItem.fromUri(v.uri)).setEffects(effects).build()
        val composition = Composition.Builder(EditedMediaItemSequence(edited))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        // Cap the bitrate: the 1st-gen Chromecast only has 2.4 GHz Wi-Fi, shared with the phone streaming it.
        val encoders = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .build()
        val builder = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoders)
        if (fragmented) {
            builder.setMuxerFactory(
                InAppMuxer.Factory.Builder()
                    .setOutputFragmentedMp4(true)
                    .setFragmentDurationMs(1_000)
                    .build()
            )
        }
        val transformer = builder
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finish()
                    onDone(true, null)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    finish()
                    onDone(false, exportException.message ?: "Conversion failed")
                }
            })
            .build()

        current = transformer
        currentId = v.id
        transformer.start(composition, output.absolutePath)

        val holder = ProgressHolder()
        handler.post(object : Runnable {
            override fun run() {
                val t = current ?: return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                handler.postDelayed(this, 500)
            }
        })
    }

    /** Re-packages a finished live stream as a normal MP4 (no re-encoding, takes seconds) so seeking works. */
    fun remux(input: File, output: File, onDone: (Boolean) -> Unit) {
        val tmp = File(output.parentFile, output.name.removeSuffix(".mp4") + ".part.mp4")
        tmp.delete()
        Transformer.Builder(ctx)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    onDone(tmp.renameTo(output))
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    tmp.delete()
                    onDone(false)
                }
            })
            .build()
            .start(MediaItem.fromUri(Uri.fromFile(input)), tmp.absolutePath)
    }

    fun cancel() {
        current?.cancel()
        finish()
    }

    private fun finish() {
        current = null
        currentId = -1
    }
}
