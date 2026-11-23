package com.biomeria.launcher.data

import android.content.Context

internal class LibraryPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("biomeria_library", Context.MODE_PRIVATE)

    fun favorites(): Set<String> = preferences.getStringSet("favorites", emptySet()).orEmpty().toSet()

    fun setFavorites(ids: Set<String>) {
        preferences.edit().putStringSet("favorites", ids).apply()
    }

    fun recent(): List<String> = preferences.getString("recent", null)
        ?.split('\n')?.filter(String::isNotEmpty).orEmpty()

    fun setRecent(ids: List<String>) {
        preferences.edit().putString("recent", ids.take(24).joinToString("\n")).apply()
    }
}
