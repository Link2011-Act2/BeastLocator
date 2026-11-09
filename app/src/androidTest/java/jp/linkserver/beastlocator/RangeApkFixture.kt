package jp.linkserver.beastlocator

import android.app.Application
import jp.linkserver.goldy.*
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

/** Test-only loopback HTTP adapter. All Gold URL, digest, APK and signer checks remain real. */
internal fun rangeApkDownloader(app: Application): ApkDownloader {
    val base = Class.forName("jp.linkserver.goldy.AssetTransport")
    val ranged = Class.forName("jp.linkserver.goldy.RangeAssetTransport")
    val connection = Class.forName("jp.linkserver.goldy.AssetConnection")
    val transport = Proxy.newProxyInstance(ranged.classLoader, arrayOf(ranged)) { _, method, args ->
        check(method.name == "openRange" || method.name == "open")
        val offset = if(method.name == "openRange") args!![2] as Long else 0L
        val validator = if(method.name == "openRange") args!![3] as String? else null
        val socket = Socket().apply { connect(InetSocketAddress("127.0.0.1", 37571), 5000); soTimeout = 15000 }
        try {
            val headers = buildString {
                append("GET /apk HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\nAccept-Encoding: identity\r\n")
                if(offset > 0) {
                    append("Range: bytes=$offset-\r\n")
                    validator?.let { require(it.none { c -> c == '\r' || c == '\n' }); append("If-Range: $it\r\n") }
                }
                append("\r\n")
            }
            socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII))
            val input = BufferedInputStream(socket.getInputStream())
            fun line(): String {
                val bytes = java.io.ByteArrayOutputStream()
                while(true) {
                    val b = input.read(); require(b >= 0)
                    if(b == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
                    require(bytes.size() < 8192); bytes.write(b)
                }
            }
            val status = line().split(' ')[1].toInt()
            val response = mutableMapOf<String,String>()
            var count = 0
            while(true) {
                require(count++ < 64)
                val row = line()
                if(row.isEmpty()) break
                val colon = row.indexOf(':'); require(colon > 0)
                response[row.substring(0,colon).lowercase(Locale.ROOT)] = row.substring(colon+1).trim()
            }
            Proxy.newProxyInstance(connection.classLoader, arrayOf(connection)) { _, call, values -> when(call.name) {
                "getStatus" -> status
                "header" -> response[(values!![0] as String).lowercase(Locale.ROOT)]
                "input" -> input
                "close" -> { socket.close(); Unit }
                else -> error(call.name)
            } }
        } catch(error: Throwable) { socket.close(); throw error }
    }
    return ApkDownloader::class.java.getConstructor(ApkDownloadConfig::class.java, File::class.java,
        ApkInspector::class.java, base).newInstance(ApkDownloadConfig(ApkUpdateProfile.repository, ApkUpdateProfile.selection,
            ApkVersionBindings.Published, maxAttempts=1), File(app.filesDir,"goldy-range-test").apply { mkdirs() }, AndroidApkInspector(app), transport).also { downloader ->
                ApkDownloader::class.java.getDeclaredField("completionDirectory").apply { isAccessible = true }
                    .set(downloader, File(app.cacheDir, "goldy-apks"))
            }
}
