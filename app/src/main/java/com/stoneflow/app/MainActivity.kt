package com.stoneflow.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import android.widget.RelativeLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val WEB_URL = "https://stoneflow.base44.app"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var billingManager: BillingManager

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create layout programmatically
        val layout = RelativeLayout(this).apply {
            setBackgroundColor(0xFF0F172A.toInt())
        }

        webView = WebView(this).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                8
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_TOP)
            }
            isIndeterminate = true
            visibility = View.GONE
        }

        layout.addView(webView)
        layout.addView(progressBar)
        setContentView(layout)

        // Initialize billing
        billingManager = BillingManager(this)
        billingManager.initialize()

        // Configure WebView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString StoneflowApp/Android"
        }

        // Add IAP bridge
        webView.addJavascriptInterface(IAPBridge(), "iap")

        // Set WebViewClient
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                injectBridgeFlags()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                // Keep base44.app URLs in the WebView
                if (url.contains("base44.app")) {
                    return false
                }
                // Open external links in browser
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open URL: $url", e)
                }
                return true
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    Log.e(TAG, "Page load error: ${error?.description}")
                }
            }
        }

        // Set WebChromeClient for JS alerts
        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setCancelable(false)
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> result?.cancel() }
                    .setCancelable(false)
                    .show()
                return true
            }
        }

        webView.loadUrl(WEB_URL)
    }

    private fun injectBridgeFlags() {
        val js = """
            window.isNativeApp = true;
            window.iapBridgeReady = true;
            window.nativePlatform = 'android';
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun sendEventToJS(type: String, data: JSONObject) {
        // Put type directly into the data object so JS sees { type, products, ... }
        // instead of { type, data: { products, ... } }
        data.put("type", type)
        val js = "window.dispatchEvent(new MessageEvent('message', { data: ${data.toString()} }));"
        runOnUiThread {
            webView.evaluateJavascript(js, null)
        }
    }

    @Suppress("unused")
    inner class IAPBridge {

        @JavascriptInterface
        fun postMessage(messageJson: String) {
            Log.d(TAG, "IAP bridge received: $messageJson")
            try {
                val message = JSONObject(messageJson)
                val action = message.getString("action")

                when (action) {
                    "requestProducts" -> handleRequestProducts()
                    "purchase" -> handlePurchase(message)
                    "restore" -> handleRestore()
                    "validateReceipt" -> handleValidateReceipt(message)
                    else -> Log.w(TAG, "Unknown IAP action: $action")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing IAP message", e)
            }
        }

        private fun handleRequestProducts() {
            billingManager.queryProduct { details ->
                val data = JSONObject()
                if (details != null) {
                    val productInfo = billingManager.getProductInfo()
                    if (productInfo != null) {
                        val productsArray = JSONArray()
                        productsArray.put(JSONObject(productInfo))
                        data.put("products", productsArray)
                    } else {
                        data.put("products", JSONArray())
                    }
                } else {
                    data.put("products", JSONArray())
                    data.put("error", "Product not found")
                }
                sendEventToJS("IAP_PRODUCTS_RESULT", data)
            }
        }

        private fun handlePurchase(message: JSONObject) {
            billingManager.launchPurchase { success, error, purchase ->
                val data = JSONObject().apply {
                    put("success", success)
                    if (error != null) put("error", error)
                    if (purchase != null) {
                        put("productId", BillingManager.PRODUCT_ID)
                        put("purchaseToken", purchase.purchaseToken)
                        put("orderId", purchase.orderId ?: "")
                    }
                }
                sendEventToJS("IAP_PURCHASE_RESULT", data)
            }
        }

        private fun handleRestore() {
            billingManager.restorePurchases { success, error, purchase ->
                val data = JSONObject().apply {
                    put("hasActiveSubscription", success)
                    if (error != null) put("error", error)
                    if (purchase != null) {
                        put("productId", BillingManager.PRODUCT_ID)
                        put("purchaseToken", purchase.purchaseToken)
                    }
                }
                sendEventToJS("IAP_RESTORE_RESULT", data)
            }
        }

        private fun handleValidateReceipt(message: JSONObject) {
            // Google Play handles validation server-side via purchaseToken
            // For client-side, we just confirm the purchase exists
            billingManager.restorePurchases { success, _, purchase ->
                val data = JSONObject().apply {
                    put("valid", success)
                    if (purchase != null) {
                        put("purchaseToken", purchase.purchaseToken)
                        put("productId", BillingManager.PRODUCT_ID)
                    }
                }
                sendEventToJS("IAP_VALIDATE_RESULT", data)
            }
        }
    }

    @Deprecated("Use onBackPressedDispatcher")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        billingManager.destroy()
        webView.destroy()
        super.onDestroy()
    }
}
