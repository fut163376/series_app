package com.gab.tvnavegador

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Marcador guardado por el usuario. */
data class Bookmark(val title: String, val url: String)

/**
 * Ajustes persistentes del navegador. Todo se guarda en SharedPreferences para
 * que la app arranque siempre donde el usuario la dejo.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("tvnavegador", Context.MODE_PRIVATE)

    var homePage: String
        get() = sp.getString(KEY_HOME, DEFAULT_HOME) ?: DEFAULT_HOME
        set(value) = sp.edit().putString(KEY_HOME, value).apply()

    var lastUrl: String
        get() = sp.getString(KEY_LAST, "") ?: ""
        set(value) = sp.edit().putString(KEY_LAST, value).apply()

    /** true = el D-pad mueve un puntero; false = el D-pad desplaza la pagina. */
    var cursorMode: Boolean
        get() = sp.getBoolean(KEY_CURSOR, true)
        set(value) = sp.edit().putBoolean(KEY_CURSOR, value).apply()

    /** true = user-agent de escritorio (mejor aprovechamiento de la pantalla grande). */
    var desktopUa: Boolean
        get() = sp.getBoolean(KEY_DESKTOP_UA, true)
        set(value) = sp.edit().putBoolean(KEY_DESKTOP_UA, value).apply()

    /** Zoom de la pagina en porcentaje. */
    var zoomPercent: Int
        get() = sp.getInt(KEY_ZOOM, 100)
        set(value) = sp.edit().putInt(KEY_ZOOM, value.coerceIn(MIN_ZOOM, MAX_ZOOM)).apply()

    /** true = restaurar la ultima pagina al abrir; false = ir siempre al inicio. */
    var restoreLast: Boolean
        get() = sp.getBoolean(KEY_RESTORE, true)
        set(value) = sp.edit().putBoolean(KEY_RESTORE, value).apply()

    fun bookmarks(): MutableList<Bookmark> {
        val raw = sp.getString(KEY_BOOKMARKS, null) ?: return defaultBookmarks()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Bookmark>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Bookmark(o.optString("title"), o.optString("url")))
            }
            out
        } catch (e: Exception) {
            defaultBookmarks()
        }
    }

    fun saveBookmarks(list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(JSONObject().put("title", b.title).put("url", b.url))
        }
        sp.edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
    }

    fun addBookmark(title: String, url: String) {
        val list = bookmarks()
        if (list.none { it.url == url }) {
            list.add(Bookmark(title.ifBlank { url }, url))
            saveBookmarks(list)
        }
    }

    fun removeBookmark(url: String) {
        saveBookmarks(bookmarks().filterNot { it.url == url })
    }

    private fun defaultBookmarks(): MutableList<Bookmark> =
        mutableListOf(Bookmark("Pagina de inicio", DEFAULT_HOME))

    companion object {
        /** Editable por el usuario desde el menu; solo es el valor inicial. */
        const val DEFAULT_HOME = "https://enlaces.ly/"

        const val MIN_ZOOM = 50
        const val MAX_ZOOM = 220

        private const val KEY_HOME = "home"
        private const val KEY_LAST = "last_url"
        private const val KEY_CURSOR = "cursor_mode"
        private const val KEY_DESKTOP_UA = "desktop_ua"
        private const val KEY_ZOOM = "zoom"
        private const val KEY_RESTORE = "restore_last"
        private const val KEY_BOOKMARKS = "bookmarks"
    }
}
