package jp.linkserver.beastlocator

import android.content.SharedPreferences

internal fun restorePreferences(preferences: SharedPreferences, values: Map<String, *>) {
    val editor=preferences.edit().clear()
    values.forEach { (key,value) -> when(value) {
        is Boolean -> editor.putBoolean(key,value)
        is Int -> editor.putInt(key,value)
        is Long -> editor.putLong(key,value)
        is Float -> editor.putFloat(key,value)
        is String -> editor.putString(key,value)
        is Set<*> -> editor.putStringSet(key,value.map { it as String }.toSet())
        null -> Unit
        else -> error("Unsupported preference type")
    } }
    check(editor.commit())
}
