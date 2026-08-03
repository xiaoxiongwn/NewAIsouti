package com.scanqa.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import java.net.URLEncoder

class WebSearchActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_search)

        val web = findViewById<WebView>(R.id.webView)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()

        val query = intent.getStringExtra("query").orEmpty()
        val url = "https://www.bing.com/search?q=" +
            URLEncoder.encode(query, "UTF-8")
        web.loadUrl(url)
    }
}
