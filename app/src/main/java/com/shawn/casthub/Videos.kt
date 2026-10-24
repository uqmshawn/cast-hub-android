package com.shawn.casthub

import android.content.ContentUris
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore

data class Video(
    val id: Long,
    val title: String,
    val size: Long,
    val durationMs: Long,
    val mime: String?,
    val folder: String,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
}

enum class Compat { CHECKING, READY, MAYBE_NOT }

data class CompatResult(val compat: Compat, val note: String, val width: Int = 0, val height: Int = 0)

object Videos {

    /** Every video on the phone (camera, Downloads, Movies, WhatsApp…), newest first. */
    fun load(ctx: Context): List<Video> {
        val cols = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
        val out = mutableListOf<Video>()
        ctx.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, null, null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val iBucket = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val name = c.getString(iName) ?: "Video"
                out += Video(
                    id = c.getLong(iId),
                    title = prettyName(name),
                    size = c.getLong(iSize),
                    durationMs = c.getLong(iDur),
                    mime = c.getString(iMime),
                    folder = c.getString(iBucket) ?: "",
                )
            }
        }
        return out
    }

    fun prettyName(file: String): String =
        file.substringBeforeLast('.').replace(Regex("[._]+"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * What a 1st-gen Chromecast plays natively: MP4 with H.264 (8-bit, up to 1080p) + AAC/MP3,
     * or WebM with VP8. Anything else may fail or play without sound.
     */
    fun check(ctx: Context, v: Video): CompatResult {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, v.uri, null)
            var videoMime: String? = null
            var w = 0
            var h = 0
            var profile = -1
            var audioMime: String? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoMime == null) {
                    videoMime = mime
                    if (f.containsKey(MediaFormat.KEY_WIDTH)) w = f.getInteger(MediaFormat.KEY_WIDTH)
                    if (f.containsKey(MediaFormat.KEY_HEIGHT)) h = f.getInteger(MediaFormat.KEY_HEIGHT)
                    if (f.containsKey(MediaFormat.KEY_PROFILE)) profile = f.getInteger(MediaFormat.KEY_PROFILE)
                } else if (mime.startsWith("audio/") && audioMime == null) {
                    audioMime = mime
                }
            }
            val res = if (h > 0) " ${minOf(w, h)}p" else ""
            val desc = listOfNotNull(videoMime?.let { codecName(it) + res }, audioMime?.let { codecName(it) })
                .joinToString(" · ")

            val container = v.mime ?: ""
            val isMp4 = container == "video/mp4" || container == "video/quicktime" || container == "video/3gpp"
            val videoOk = videoMime == "video/avc" && maxOf(w, h) <= 1920 && minOf(w, h) <= 1088 &&
                profile != MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
            val audioOk = audioMime == null || audioMime == "audio/mp4a-latm" || audioMime == "audio/mpeg"
            val webmOk = container == "video/webm" && videoMime == "video/x-vnd.on2.vp8"

            return when {
                (isMp4 && videoOk && audioOk) || webmOk -> CompatResult(Compat.READY, desc, w, h)
                !videoOk -> CompatResult(Compat.MAYBE_NOT, "$desc — needs converting", w, h)
                !audioOk -> CompatResult(Compat.MAYBE_NOT, "$desc — sound needs converting", w, h)
                else -> CompatResult(Compat.MAYBE_NOT, "$desc — needs converting", w, h)
            }
        } catch (e: Exception) {
            return CompatResult(Compat.MAYBE_NOT, "Couldn't read this file")
        } finally {
            ex.release()
        }
    }

    private fun codecName(mime: String): String = when (mime) {
        "video/avc" -> "H.264"
        "video/hevc" -> "H.265"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/av01" -> "AV1"
        "video/mp4v-es" -> "MPEG-4"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/ac3" -> "AC3"
        "audio/eac3" -> "E-AC3"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        else -> mime.substringAfter('/').uppercase()
    }

    fun srtToVtt(text: String): String {
        val clean = text.removePrefix("﻿").replace("\r", "")
        if (clean.trimStart().startsWith("WEBVTT")) return clean
        return "WEBVTT\n\n" + clean.replace(Regex("(\\d{2}:\\d{2}:\\d{2}),(\\d{3})"), "$1.$2")
    }
}
