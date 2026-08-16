package com.wuwa.config.manager.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** Applies persisted language/theme before the activity's content is inflated. */
object UiPreferences {
    fun applyLanguage(context: Context) {
        val prefs = context.getSharedPreferences(MainViewModel.PREFS, Context.MODE_PRIVATE)
        val language = prefs.getString(MainViewModel.KEY_LANGUAGE, "zh-CN") ?: "zh-CN"
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(if ("en" == language) "en" else "zh-CN")
        )
    }

    fun applyAppearance(context: Context) {
        when (getAppearanceMode(context)) {
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun getAppearanceMode(context: Context): String {
        val prefs = context.getSharedPreferences(MainViewModel.PREFS, Context.MODE_PRIVATE)
        val mode = prefs.getString(MainViewModel.KEY_APPEARANCE, "light") ?: "light"
        return when (mode) {
            "dark", "system", "light" -> mode
            else -> "light"
        }
    }
}
