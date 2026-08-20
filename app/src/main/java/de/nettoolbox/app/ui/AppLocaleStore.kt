package de.nettoolbox.app.ui

import android.content.Context
import android.content.res.Configuration
import de.nettoolbox.core.datastore.model.AppLanguage
import java.util.Locale

/**
 * The selected language, readable synchronously.
 *
 * DataStore is the source of truth, but `attachBaseContext` runs before Hilt has
 * injected anything and cannot suspend, so the choice is mirrored into
 * SharedPreferences. Three bytes of duplication in exchange for a locale that is
 * applied before the first resource is resolved.
 */
object AppLocaleStore {

    private const val PREFS_NAME = "nettoolbox_locale"
    private const val KEY_TAG = "language_tag"

    fun readTag(context: Context): String? = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_TAG, null)

    fun writeTag(context: Context, tag: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .apply { if (tag == null) remove(KEY_TAG) else putString(KEY_TAG, tag) }
            .apply()
    }

    fun matches(context: Context, language: AppLanguage): Boolean =
        readTag(context) == language.tag

    /**
     * Wraps a context in one that resolves resources in [tag].
     *
     * Used from `attachBaseContext`, so everything the activity creates later -
     * including the Compose tree and every Hilt-provided ViewModel - already sees
     * the right locale. Nothing further down has to know about languages.
     */
    fun applyLocale(base: Context, tag: String?): Context {
        if (tag.isNullOrEmpty()) return base

        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)

        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(configuration)
    }
}
