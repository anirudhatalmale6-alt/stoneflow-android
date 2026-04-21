package com.stoneflow.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import android.widget.RelativeLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val WEB_URL = "https://stoneflow.base44.app"
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val FILE_CHOOSER_REQUEST = 1002
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var billingManager: BillingManager
    private var geolocationCallback: GeolocationPermissions.Callback? = null
    private var geolocationOrigin: String? = null
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

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
            allowFileAccess = true
            allowContentAccess = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setGeolocationEnabled(true)
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

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val acceptTypes = fileChooserParams?.acceptTypes ?: arrayOf()
                val isImage = acceptTypes.any { it.startsWith("image/") || it == "image/*" }

                val intents = mutableListOf<Intent>()

                // Camera intent for photos
                if (isImage || acceptTypes.isEmpty()) {
                    try {
                        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        val imageFile = File.createTempFile("IMG_${timeStamp}_", ".jpg", cacheDir)
                        cameraImageUri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", imageFile)
                        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                            putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                        }
                        if (cameraIntent.resolveActivity(packageManager) != null) {
                            intents.add(cameraIntent)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create camera intent", e)
                    }
                }

                // File picker intent
                val fileIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (acceptTypes.isNotEmpty() && acceptTypes[0].isNotEmpty()) acceptTypes[0] else "*/*"
                    if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }

                val chooserIntent = Intent.createChooser(fileIntent, "Choose file")
                if (intents.isNotEmpty()) {
                    chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.toTypedArray())
                }

                try {
                    startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch file chooser", e)
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    return false
                }
                return true
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback?.invoke(origin, true, false)
                } else {
                    geolocationCallback = callback
                    geolocationOrigin = origin
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        LOCATION_PERMISSION_REQUEST
                    )
                }
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

    @Deprecated("Use Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (resultCode == Activity.RESULT_OK) {
                val results = mutableListOf<Uri>()

                // Check for multiple files
                val clipData = data?.clipData
                if (clipData != null) {
                    for (i in 0 until clipData.itemCount) {
                        clipData.getItemAt(i).uri?.let { results.add(it) }
                    }
                } else if (data?.data != null) {
                    // Single file from picker
                    results.add(data.data!!)
                } else if (cameraImageUri != null) {
                    // Photo from camera
                    results.add(cameraImageUri!!)
                }

                fileUploadCallback?.onReceiveValue(results.toTypedArray())
            } else {
                fileUploadCallback?.onReceiveValue(null)
            }
            fileUploadCallback = null
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            geolocationCallback?.invoke(geolocationOrigin, granted, false)
            geolocationCallback = null
            geolocationOrigin = null
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
