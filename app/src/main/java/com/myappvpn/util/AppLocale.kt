package com.myappvpn.util

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * In-app language ("Язык" in Settings).
 *
 * Android 13+ (API 33): the framework per-app language API ([LocaleManager]). The system stores
 * the choice, applies it to every component of the app (activities, services, application
 * context) and recreates running activities itself. The same setting is shown in system
 * Settings → Apps → MyAppVPN → Language thanks to res/xml/locales_config.xml.
 *
 * Android 8–12 (API 26–32): no framework support. The tag is kept in plain SharedPreferences
 * (read synchronously, so the first frame is already in the right language) and applied by
 * wrapping the base context in [wrap] from attachBaseContext of the activities and the
 * Application; [apply] then recreates the current activity.
 *
 * AndroidX AppCompatDelegate.setApplicationLocales is deliberately not used: it only acts through
 * AppCompatActivity delegates (on API 33+ it looks the LocaleManager up via an active
 * AppCompatDelegate), so with our ComponentActivity it would be a silent no-op.
 *
 * Tag "" = follow the system language.
 */
object AppLocale {

    /** Language tag → its own name (language names are not translated on purpose). */
    val OPTIONS: List<Pair<String, String>> = listOf(
        "ru" to "Русский",
        "en" to "English",
        "fa" to "فارسی",
        "zh-CN" to "简体中文",
        "tr" to "Türkçe",
        "kk" to "Қазақша",
        "uz" to "Oʻzbekcha",
    )

    private const val PREFS = "app_locale"
    private const val KEY_TAG = "tag"

    /** Currently selected tag (one of [OPTIONS] keys) or "" for system default. Synchronous. */
    fun current(context: Context): String {
        val raw = if (Build.VERSION.SDK_INT >= 33) {
            val lm = context.getSystemService(LocaleManager::class.java)
            lm?.applicationLocales?.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag().orEmpty()
        } else {
            storedTag(context)
        }
        return normalize(raw)
    }

    /** Selects [tag] ("" = system). On API < 33 recreates [activity] so the change is visible. */
    fun apply(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            // The system persists this and recreates our activities by itself.
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            if (tag == storedTag(activity)) return
            prefs(activity).edit().putString(KEY_TAG, tag).commit()
            activity.recreate()
        }
    }

    /**
     * attachBaseContext hook for API < 33. Returns [base] unchanged on API 33+ or when the
     * system language is selected, so the default behaviour is untouched.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = storedTag(base)
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale)) // also sets the layout direction (RTL for fa)
        return base.createConfigurationContext(config)
    }

    /**
     * One-time migration for a device upgraded from Android ≤ 12 to 13+: hand the choice stored
     * in SharedPreferences over to the system per-app language and forget it.
     */
    fun migrateToSystem(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val tag = storedTag(context)
        if (tag.isEmpty()) return
        val lm = context.getSystemService(LocaleManager::class.java) ?: return
        if (lm.applicationLocales.isEmpty) lm.applicationLocales = LocaleList.forLanguageTags(tag)
        prefs(context).edit().remove(KEY_TAG).apply()
    }

    private fun storedTag(context: Context): String =
        prefs(context).getString(KEY_TAG, "").orEmpty()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Maps whatever the system reports (e.g. "zh-Hans-CN", "en-US") onto one of [OPTIONS]. */
    private fun normalize(tag: String): String {
        if (tag.isEmpty()) return ""
        OPTIONS.firstOrNull { it.first.equals(tag, ignoreCase = true) }?.let { return it.first }
        val lang = Locale.forLanguageTag(tag).language
        return OPTIONS.firstOrNull { Locale.forLanguageTag(it.first).language == lang }?.first ?: tag
    }
}
