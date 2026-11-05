package com.ronaldlazaga.prehistoricAR

import android.content.Context
import org.json.JSONObject

/** Reads assets/app_settings.json (same config file the original APK shipped with). */
object AppConfig {
    private var json: JSONObject? = null

    private fun load(context: Context): JSONObject {
        json?.let { return it }
        val text = context.assets.open("app_settings.json").bufferedReader().use { it.readText() }
        return JSONObject(text).also { json = it }
    }

    fun websiteUrl(context: Context): String =
        load(context).optString("websiteUrl", "https://asblr.com/kJxCRzO")

    fun pullRefresh(context: Context): Boolean = load(context).optBoolean("pullRefresh", true)

    fun pageLoaderEnabled(context: Context): Boolean =
        load(context).optJSONObject("pageLoader")?.optBoolean("enabled", true) ?: true
}
