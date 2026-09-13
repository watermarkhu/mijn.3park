package dev.watermarkhu.mijn3park

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * Hosts the payment-provider flow for a balance top-up.
 *
 * The 2Park API returns a forwarding URL plus form parameters; the website
 * submits these as a hidden auto-submit form. We do the same inside a
 * WebView. Non-http(s) schemes (e.g. banking-app deep links used by iDEAL)
 * are handed off to external apps, and a redirect back to mijn.2park.nl
 * means the payment flow finished.
 */
class TopupActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_METHOD = "method"

        /** Alternating name/value pairs. */
        const val EXTRA_PARAMS = "params"
    }

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val method = intent.getStringExtra(EXTRA_METHOD).orEmpty().ifBlank { "POST" }
        val params = intent.getStringArrayExtra(EXTRA_PARAMS) ?: emptyArray()
        if (url.isBlank()) {
            finish()
            return
        }

        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        webView.webViewClient = object : WebViewClient() {
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, loadedUrl: String?): Boolean {
                val uri = Uri.parse(loadedUrl ?: return false)
                return when (uri.scheme) {
                    "http", "https" -> {
                        if (uri.host?.endsWith("2park.nl") == true) {
                            // Back at 2park: payment flow is done.
                            finish()
                            true
                        } else {
                            false
                        }
                    }
                    else -> {
                        // Banking app deep link (iDEAL) or other external scheme.
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (e: Exception) {
                            // No app can handle it; stay in the WebView.
                        }
                        true
                    }
                }
            }
        }

        webView.loadDataWithBaseURL(url, buildFormHtml(url, method, params), "text/html", "utf-8", null)
    }

    private fun buildFormHtml(url: String, method: String, params: Array<String>): String {
        val inputs = StringBuilder()
        var i = 0
        while (i + 1 < params.size) {
            inputs.append("<input type=\"hidden\" name=\"")
                .append(escapeHtml(params[i]))
                .append("\" value=\"")
                .append(escapeHtml(params[i + 1]))
                .append("\"/>")
            i += 2
        }
        return "<!DOCTYPE html><html><body onload=\"document.forms[0].submit()\">" +
            "<form method=\"" + escapeHtml(method) + "\" action=\"" + escapeHtml(url) + "\">" +
            inputs +
            "</form></body></html>"
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (this::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }
}
