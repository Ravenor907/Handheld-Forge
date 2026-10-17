package io.github.ravenor907.handheldforge

import android.content.Context
import io.github.ravenor907.handheldforge.core.Catalog
import io.github.ravenor907.handheldforge.core.Distro

/**
 * Experimental systems, kept apart from the main catalog: they live in the app's own
 * labs.json (not the shared distros.json), only show after tapping the version number
 * 10 times, and a problem with them never stops the regular systems from loading.
 */
object Labs {
    private const val ASSET = "labs.json"
    private const val PREFS = "forge"
    private const val PREF_ON = "labs"
    const val TAPS = 10

    /** Every labs system, marked hidden. Empty if labs.json is missing or can't be read. */
    fun catalog(context: Context, taken: Set<String>): List<Distro> = try {
        parse(context.assets.open(ASSET).bufferedReader().use { it.readText() }, taken)
    } catch (e: Exception) {
        emptyList()
    }

    /** Never lets a labs entry replace a regular system with the same id. */
    fun parse(text: String, taken: Set<String>): List<Distro> =
        Catalog.parse(text).filter { it.id !in taken }.map { it.copy(hidden = true) }

    fun isOn(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ON, false)

    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_ON, on).apply()
    }
}
