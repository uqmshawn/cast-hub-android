package com.shawn.casthub

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File

/** Where converted (Chromecast-friendly) copies live. Removed automatically if the app is uninstalled. */
object Converted {
    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "converted").apply { mkdirs() }

    fun file(ctx: Context, id: Long) = File(dir(ctx), "$id.mp4")
    fun exists(ctx: Context, id: Long) = file(ctx, id).let { it.exists() && it.length() > 0 }

    fun clearAll(ctx: Context): Long {
        var freed = 0L
        dir(ctx).listFiles()?.forEach { freed += it.length(); it.delete() }
        return freed
    }
}

/**
 * Converts H.265 / 4K / HDR phone videos into H.264 + AAC MP4 (max 1080p, SDR) that the
 * 1st-gen Chromecast can play. Uses the phone's hardware encoder, so it's quick.
 * Must be used from the main thread.
 */
@OptIn(UnstableApi::class)
class Converter(context: Context) {
    private val ctx = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var current: Transformer? = null
    var currentId: Long = -1
        private set

    val isBusy get() = current != null

    fun convert(
        v: Video,
        width: Int,
        height: Int,
        onProgress: (Int) -> Unit,
        onDone: (ok: Boolean, error: String?) -> Unit,
    ) {
        if (current != null) {
            onDone(false, "Another video is converting — wait for it to finish")
            return
        }
        val out = Converted.file(ctx, v.id)
        val tmp = File(out.parentFile, "${v.id}.part.mp4")
        tmp.delete()

        val effects = if (width > 0 && height > 0 && minOf(width, height) > 1080) {
            val p = if (width >= height) {
                Presentation.createForHeight(1080)
            } else {
                val h = ((1080L * height / width) / 2 * 2).toInt()
                Presentation.createForWidthAndHeight(1080, h, Presentation.LAYOUT_SCALE_TO_FIT)
            }
            Effects(emptyList(), listOf(p))
        } else {
            Effects.EMPTY
        }

        val edited = EditedMediaItem.Builder(MediaItem.fromUri(v.uri)).setEffects(effects).build()
        val composition = Composition.Builder(EditedMediaItemSequence(edited))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        val transformer = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finish()
                    val ok = tmp.renameTo(out)
                    onDone(ok, if (ok) null else "Couldn't save the converted file")
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    finish()
                    tmp.delete()
                    onDone(false, exportException.message ?: "Conversion failed")
                }
            })
            .build()

        current = transformer
        currentId = v.id
        transformer.start(composition, tmp.absolutePath)

        val holder = ProgressHolder()
        handler.post(object : Runnable {
            override fun run() {
                val t = current ?: return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                handler.postDelayed(this, 500)
            }
        })
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
