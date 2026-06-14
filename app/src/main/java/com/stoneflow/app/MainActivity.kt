package com.stoneflow.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import android.widget.RelativeLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONArray
import org.json.JSONObject

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
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var geolocationCallback: GeolocationPermissions.Callback? = null
    private var geolocationOrigin: String? = null
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

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

        // Initialize location client
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

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

        // Add native location bridge
        webView.addJavascriptInterface(LocationBridge(), "nativeLocation")

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
                Log.d(TAG, "onShowFileChooser called")
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                try {
                    val intent = fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }

                    startActivityForResult(
                        Intent.createChooser(intent, "Choose file"),
                        FILE_CHOOSER_REQUEST
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch file chooser", e)
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    return false
                }
                return true
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                Log.d(TAG, "Geolocation permission prompt for origin: $origin")
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback?.invoke(origin, true, true)
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

        // Request location permission upfront so it's ready when the web page needs it
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
        }
    }

    private fun injectBridgeFlags() {
        val js = """
            window.isNativeApp = true;
            window.iapBridgeReady = true;
            window.nativePlatform = 'android';

            // Override geolocation with native Android location for reliability
            if (window.nativeLocation && !window.__geoPatched) {
                window.__geoPatched = true;
                var origGetCurrentPosition = navigator.geolocation.getCurrentPosition.bind(navigator.geolocation);
                navigator.geolocation.getCurrentPosition = function(success, error, options) {
                    // Try native bridge first
                    window.nativeLocation.requestLocation();
                    var nativeTimeout = setTimeout(function() {
                        // Fallback to standard API with extended timeout
                        origGetCurrentPosition(success, error, {
                            enableHighAccuracy: true,
                            timeout: 30000,
                            maximumAge: 60000
                        });
                    }, 5000);

                    // Listen for native location result
                    window.__nativeLocationSuccess = function(lat, lng, accuracy) {
                        clearTimeout(nativeTimeout);
                        success({
                            coords: {
                                latitude: lat,
                                longitude: lng,
                                accuracy: accuracy,
                                altitude: null,
                                altitudeAccuracy: null,
                                heading: null,
                                speed: null
                            },
                            timestamp: Date.now()
                        });
                        window.__nativeLocationSuccess = null;
                        window.__nativeLocationError = null;
                    };
                    window.__nativeLocationError = function(msg) {
                        clearTimeout(nativeTimeout);
                        // Fall back to standard API
                        origGetCurrentPosition(success, error, {
                            enableHighAccuracy: true,
                            timeout: 30000,
                            maximumAge: 60000
                        });
                        window.__nativeLocationSuccess = null;
                        window.__nativeLocationError = null;
                    };
                };
            }
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

    @Suppress("unused")
    inner class LocationBridge {
        @JavascriptInterface
        fun requestLocation() {
            Log.d(TAG, "Native location requested")
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Location permission not granted")
                runOnUiThread {
                    webView.evaluateJavascript("if(window.__nativeLocationError) window.__nativeLocationError('Permission denied');", null)
                }
                return
            }

            // Try last known location first for instant response
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    Log.d(TAG, "Got last known location: ${location.latitude}, ${location.longitude}")
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "if(window.__nativeLocationSuccess) window.__nativeLocationSuccess(${location.latitude}, ${location.longitude}, ${location.accuracy});",
                            null
                        )
                    }
                } else {
                    requestFreshLocation()
                }
            }.addOnFailureListener {
                Log.e(TAG, "Last location failed", it)
                requestFreshLocation()
            }
        }

        @SuppressLint("MissingPermission")
        private fun requestFreshLocation() {
            Log.d(TAG, "Requesting fresh location")
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMaxUpdates(1)
                .setMaxUpdateDelayMillis(15000)
                .build()

            fusedLocationClient.requestLocationUpdates(request, object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    fusedLocationClient.removeLocationUpdates(this)
                    val location = result.lastLocation
                    if (location != null) {
                        Log.d(TAG, "Got fresh location: ${location.latitude}, ${location.longitude}")
                        runOnUiThread {
                            webView.evaluateJavascript(
                                "if(window.__nativeLocationSuccess) window.__nativeLocationSuccess(${location.latitude}, ${location.longitude}, ${location.accuracy});",
                                null
                            )
                        }
                    } else {
                        runOnUiThread {
                            webView.evaluateJavascript("if(window.__nativeLocationError) window.__nativeLocationError('Could not determine location');", null)
                        }
                    }
                }
            }, Looper.getMainLooper())
        }
    }

    @Deprecated("Use Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == FILE_CHOOSER_REQUEST) {
            val results = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            fileUploadCallback?.onReceiveValue(results)
            fileUploadCallback = null
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Location permission result: granted=$granted")
            geolocationCallback?.invoke(geolocationOrigin, granted, true)
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
