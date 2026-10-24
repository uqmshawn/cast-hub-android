package com.shawn.casthub

import android.content.Context

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Cast receiver App ID from the Google Cast Developer Console. Blank = Google's standard player. */
    fun appId(ctx: Context): String = p(ctx).getString("appId", "") ?: ""
    fun setAppId(ctx: Context, id: String) = p(ctx).edit().putString("appId", id.trim().uppercase()).apply()

    // ---- continue watching: last position (seconds) per video ----
    private fun r(ctx: Context) = ctx.getSharedPreferences("resume", Context.MODE_PRIVATE)
    fun resume(ctx: Context, id: Long): Long = r(ctx).getLong(id.toString(), 0L)
    fun setResume(ctx: Context, id: Long, seconds: Long) {
        if (seconds <= 0) r(ctx).edit().remove(id.toString()).apply()
        else r(ctx).edit().putLong(id.toString(), seconds).apply()
    }
    fun allResume(ctx: Context): Map<Long, Long> =
        r(ctx).all.mapNotNull { (k, v) -> k.toLongOrNull()?.let { id -> (v as? Long)?.let { id to it } } }.toMap()

    // ---- conversions that haven't finished yet (resumed on next launch) ----
    private fun q(ctx: Context) = ctx.getSharedPreferences("pending", Context.MODE_PRIVATE)
    fun addPending(ctx: Context, id: Long) = q(ctx).edit().putLong(id.toString(), System.currentTimeMillis()).apply()
    fun removePending(ctx: Context, id: Long) = q(ctx).edit().remove(id.toString()).apply()
    fun pending(ctx: Context): List<Long> =
        q(ctx).all.entries.sortedBy { (it.value as? Long) ?: 0L }.mapNotNull { it.key.toLongOrNull() }

    fun get(ctx: Context, key: String, def: String = ""): String = p(ctx).getString(key, def) ?: def
    fun put(ctx: Context, key: String, value: String) = p(ctx).edit().putString(key, value).apply()
}
