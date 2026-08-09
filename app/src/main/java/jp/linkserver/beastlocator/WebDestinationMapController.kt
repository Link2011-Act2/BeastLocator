package jp.linkserver.beastlocator

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import java.io.ByteArrayInputStream

internal object DestinationMapControllerFactory {
    fun create(activity: AppCompatActivity): DestinationMapController =
        WebDestinationMapController(activity)
}

private class WebDestinationMapController(
    private val activity: AppCompatActivity
) : DestinationMapController {
    private lateinit var webView: WebView
    private lateinit var container: FrameLayout
    private lateinit var listener: DestinationMapController.Listener
    private var ready = false
    private var destroyed = false
    private var loadFailureReported = false
    private var currentZoom = DEFAULT_ZOOM
    private var pendingMove: Pair<Destination, Double>? = null

    override fun attach(
        container: FrameLayout,
        savedInstanceState: Bundle?,
        initialDestination: Destination,
        initialZoom: Double,
        listener: DestinationMapController.Listener
    ) {
        this.container = container
        this.listener = listener
        currentZoom = savedInstanceState?.getDouble(STATE_ZOOM, initialZoom) ?: initialZoom

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView = WebView(activity).apply {
            setBackgroundColor(0xFFE8E3DA.toInt())
            settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                domStorageEnabled = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture = true
                safeBrowsingEnabled = true
                userAgentString = buildString {
                    append(userAgentString)
                    append(" BeastLocator/")
                    append(BuildConfig.VERSION_NAME)
                    append(" (+https://github.com/Link2011-Act2/BeastLocator)")
                }
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = LocalMapWebViewClient()
            webChromeClient = MapConsoleClient()
        }
        container.addView(
            webView,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val mapUrl = Uri.Builder()
            .scheme("https")
            .authority(APP_ASSET_HOST)
            .appendPath("assets")
            .appendPath("destination_map.html")
            .appendQueryParameter("lat", initialDestination.lat.toString())
            .appendQueryParameter("lng", initialDestination.lng.toString())
            .appendQueryParameter("zoom", currentZoom.toString())
            .build()
        webView.loadUrl(mapUrl.toString())
    }

    override fun moveTo(destination: Destination, zoom: Double) {
        if (!ready) {
            pendingMove = destination to zoom
            return
        }
        evaluateMove(destination, zoom)
    }

    private fun evaluateMove(destination: Destination, zoom: Double) {
        if (destroyed) return
        currentZoom = zoom
        webView.evaluateJavascript(
            "window.BeastLocatorMap.moveTo(${destination.lat},${destination.lng},$zoom);",
            null
        )
    }

    override fun readCenter(onResult: (Destination?) -> Unit) {
        if (!ready || destroyed) {
            onResult(null)
            return
        }
        webView.evaluateJavascript(
            "window.BeastLocatorMap.getCenter();"
        ) { rawResult ->
            val center = runCatching {
                val values = JSONArray(rawResult)
                currentZoom = values.optDouble(2, currentZoom)
                Destination(values.getDouble(0), values.getDouble(1))
                    .takeIf { it.isValidCoordinate() }
            }.getOrNull()
            onResult(center)
        }
    }

    override fun onResume() {
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putDouble(STATE_ZOOM, currentZoom)
    }

    override fun onDestroy() {
        if (!::webView.isInitialized || destroyed) return
        destroyed = true
        ready = false
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        container.removeView(webView)
        webView.destroy()
    }

    private fun reportLoadFailure() {
        if (destroyed || loadFailureReported) return
        loadFailureReported = true
        ready = false
        listener.onLoadFailed()
    }

    private inner class LocalMapWebViewClient : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val uri = request.url
            if (uri.scheme != "https" || uri.host != APP_ASSET_HOST) return null
            val assetPath = uri.path.orEmpty().removePrefix("/assets/")
            if (assetPath.isBlank() || assetPath.contains("..")) return notFoundResponse()
            return runCatching {
                WebResourceResponse(
                    mimeTypeFor(assetPath),
                    if (assetPath.endsWith(".png")) null else "UTF-8",
                    activity.assets.open(assetPath)
                ).apply {
                    setStatusCodeAndReasonPhrase(200, "OK")
                    responseHeaders = mapOf(
                        "Cache-Control" to "public, max-age=31536000, immutable",
                        "X-Content-Type-Options" to "nosniff"
                    )
                }
            }.getOrElse { notFoundResponse() }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (uri.scheme == "https" && uri.host == APP_ASSET_HOST) return false
            if (request.isForMainFrame && (uri.scheme == "https" || uri.scheme == "http")) {
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            ready = false
            super.onPageStarted(view, url, favicon)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame) reportLoadFailure()
            super.onReceivedError(view, request, error)
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse
        ) {
            if (request.isForMainFrame) reportLoadFailure()
            super.onReceivedHttpError(view, request, errorResponse)
        }
    }

    private inner class MapConsoleClient : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            val message = consoleMessage.message()
            when {
                message.startsWith(MESSAGE_READY) -> {
                    val destination = parseDestinationMessage(message) ?: return true
                    ready = true
                    loadFailureReported = false
                    listener.onReady(destination)
                    pendingMove.also { pendingMove = null }?.let { (target, zoom) ->
                        listener.onMoveStarted()
                        evaluateMove(target, zoom)
                    }
                    return true
                }

                message == MESSAGE_MOVE_START -> {
                    if (!ready) return true
                    listener.onMoveStarted()
                    return true
                }

                message.startsWith(MESSAGE_MOVE_END) -> {
                    if (!ready) return true
                    val destination = parseDestinationMessage(message) ?: return true
                    listener.onMoveFinished(destination)
                    return true
                }

                message.startsWith(MESSAGE_ERROR) -> {
                    reportLoadFailure()
                    return true
                }
            }
            return super.onConsoleMessage(consoleMessage)
        }
    }

    private fun parseDestinationMessage(message: String): Destination? {
        val parts = message.split('|')
        if (parts.size < 4) return null
        val latitude = parts[1].toDoubleOrNull() ?: return null
        val longitude = parts[2].toDoubleOrNull() ?: return null
        currentZoom = parts[3].toDoubleOrNull() ?: currentZoom
        return Destination(latitude, longitude).takeIf { it.isValidCoordinate() }
    }

    private fun notFoundResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        404,
        "Not Found",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(ByteArray(0))
    )

    private fun mimeTypeFor(assetPath: String): String = when {
        assetPath.endsWith(".html") -> "text/html"
        assetPath.endsWith(".js") -> "application/javascript"
        assetPath.endsWith(".css") -> "text/css"
        assetPath.endsWith(".png") -> "image/png"
        else -> "application/octet-stream"
    }

    companion object {
        private const val APP_ASSET_HOST = "appassets.androidplatform.net"
        private const val STATE_ZOOM = "saved_web_map_zoom"
        private const val DEFAULT_ZOOM = 15.0
        private const val MESSAGE_READY = "BEASTLOCATOR_MAP_READY|"
        private const val MESSAGE_MOVE_START = "BEASTLOCATOR_MAP_MOVE_START"
        private const val MESSAGE_MOVE_END = "BEASTLOCATOR_MAP_MOVE_END|"
        private const val MESSAGE_ERROR = "BEASTLOCATOR_MAP_ERROR"
    }
}
