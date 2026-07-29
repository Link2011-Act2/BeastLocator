package jp.linkserver.beastlocator

import android.util.Log

object AppDiagnostics {
    private const val TAG = "BeastLocator"

    fun info(event: String, detail: String = "") {
        Log.i(TAG, format(event, detail))
    }

    fun warn(event: String, detail: String = "", error: Throwable? = null) {
        Log.w(TAG, format(event, detail), error)
    }

    private fun format(event: String, detail: String): String =
        if (detail.isBlank()) event else "$event: $detail"
}

