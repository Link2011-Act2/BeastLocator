package jp.linkserver.beastlocator

import okhttp3.OkHttpClient
import org.maplibre.android.module.http.HttpRequestUtil

object MapHttpConfiguration {
    @Volatile
    private var configured = false
    private var client: OkHttpClient? = null

    @Synchronized
    fun configureOnce() {
        if (configured) return
        val appUserAgent =
            "BeastLocator/${BuildConfig.VERSION_NAME} " +
                "(+https://github.com/Link2011-Act2/BeastLocator)"
        val configuredClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", appUserAgent)
                    .build()
                chain.proceed(request)
            }
            .build()
        client = configuredClient
        HttpRequestUtil.setOkHttpClient(configuredClient)
        configured = true
    }
}
