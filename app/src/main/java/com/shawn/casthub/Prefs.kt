package com.shawn.casthub

import android.content.Context

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Cast receiver App ID from the Google Cast Developer Console. Blank = Google's standard player. */
    fun appId(ctx: Context): String = p(ctx).getString("appId", "") ?: ""
    fun setAppId(ctx: Context, id: String) = p(ctx).edit().putString("appId", id.trim().uppercase()).apply()

    fun get(ctx: Context, key: String, def: String = ""): String = p(ctx).getString(key, def) ?: def
    fun put(ctx: Context, key: String, value: String) = p(ctx).edit().putString(key, value).apply()
}
