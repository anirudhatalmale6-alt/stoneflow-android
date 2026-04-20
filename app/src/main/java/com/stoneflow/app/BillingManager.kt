package com.stoneflow.app

import android.app.Activity
import android.util.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.*

class BillingManager(private val activity: Activity) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingManager"
        const val PRODUCT_ID = "stoneflow_pro_monthly"
    }

    private var billingClient: BillingClient? = null
    private var productDetails: ProductDetails? = null
    private var purchaseCallback: ((Boolean, String?, Purchase?) -> Unit)? = null
    private var isConnected = false

    fun initialize() {
        billingClient = BillingClient.newBuilder(activity)
            .setListener(this)
            .enablePendingPurchases()
            .build()

        startConnection()
    }

    private fun startConnection() {
        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    isConnected = true
                    Log.d(TAG, "Billing client connected")
                } else {
                    Log.e(TAG, "Billing setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                isConnected = false
                Log.d(TAG, "Billing client disconnected")
            }
        })
    }

    private fun ensureConnected(onReady: () -> Unit) {
        if (isConnected) {
            Log.d(TAG, "Already connected, proceeding")
            onReady()
            return
        }
        Log.d(TAG, "Not connected, attempting connection...")
        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                Log.d(TAG, "Connection attempt result: code=${billingResult.responseCode}, msg=${billingResult.debugMessage}")
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    isConnected = true
                    onReady()
                } else {
                    Log.e(TAG, "Billing connection failed: ${billingResult.responseCode}")
                }
            }
            override fun onBillingServiceDisconnected() {
                isConnected = false
                Log.d(TAG, "Billing service disconnected")
            }
        })
    }

    fun queryProduct(callback: (ProductDetails?) -> Unit) {
        ensureConnected {
            val productList = listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            )
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(productList)
                .build()

            billingClient?.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
                Log.d(TAG, "Product query response: code=${billingResult.responseCode}, message=${billingResult.debugMessage}, products=${productDetailsList.size}")
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && productDetailsList.isNotEmpty()) {
                    productDetails = productDetailsList[0]
                    Log.d(TAG, "Product found: ${productDetails?.title}")
                    callback(productDetails)
                } else {
                    Log.e(TAG, "Product query failed: code=${billingResult.responseCode}, debug=${billingResult.debugMessage}, listSize=${productDetailsList.size}")
                    callback(null)
                }
            }
        }
    }

    fun launchPurchase(callback: (Boolean, String?, Purchase?) -> Unit) {
        this.purchaseCallback = callback

        val details = productDetails
        if (details == null) {
            queryProduct { pd ->
                if (pd != null) {
                    productDetails = pd
                    doLaunchPurchase(pd, callback)
                } else {
                    callback(false, "Product not found. Please try again.", null)
                }
            }
            return
        }
        doLaunchPurchase(details, callback)
    }

    private fun doLaunchPurchase(details: ProductDetails, callback: (Boolean, String?, Purchase?) -> Unit) {
        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
        if (offerToken == null) {
            callback(false, "No subscription offer available.", null)
            return
        }

        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offerToken)
            .build()

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()

        activity.runOnUiThread {
            val result = billingClient?.launchBillingFlow(activity, billingFlowParams)
            if (result?.responseCode != BillingClient.BillingResponseCode.OK) {
                callback(false, "Failed to launch purchase flow: ${result?.debugMessage}", null)
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        Log.d(TAG, "onPurchasesUpdated: code=${billingResult.responseCode}, purchases=${purchases?.size ?: 0}")
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases.isNullOrEmpty()) {
                    Log.w(TAG, "Purchase OK but no purchases returned")
                    purchaseCallback?.invoke(false, "Purchase completed but no receipt received.", null)
                    return
                }
                purchases.forEach { purchase ->
                    Log.d(TAG, "Purchase state: ${purchase.purchaseState}, token: ${purchase.purchaseToken.take(20)}...")
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        acknowledgePurchase(purchase)
                        purchaseCallback?.invoke(true, null, purchase)
                    } else if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
                        purchaseCallback?.invoke(false, "Purchase is pending. Please complete payment.", purchase)
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d(TAG, "Purchase cancelled by user")
                purchaseCallback?.invoke(false, "Purchase cancelled.", null)
            }
            else -> {
                Log.e(TAG, "Purchase failed: code=${billingResult.responseCode}, msg=${billingResult.debugMessage}")
                purchaseCallback?.invoke(false, "Purchase failed: ${billingResult.debugMessage}", null)
            }
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        if (purchase.isAcknowledged) return

        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        billingClient?.acknowledgePurchase(params) { billingResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.d(TAG, "Purchase acknowledged")
            } else {
                Log.e(TAG, "Acknowledge failed: ${billingResult.debugMessage}")
            }
        }
    }

    fun restorePurchases(callback: (Boolean, String?, Purchase?) -> Unit) {
        ensureConnected {
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()

            billingClient?.queryPurchasesAsync(params) { billingResult, purchases ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    val activePurchase = purchases.firstOrNull {
                        it.purchaseState == Purchase.PurchaseState.PURCHASED
                    }
                    if (activePurchase != null) {
                        callback(true, null, activePurchase)
                    } else {
                        callback(false, "No active subscription found.", null)
                    }
                } else {
                    callback(false, "Failed to query purchases: ${billingResult.debugMessage}", null)
                }
            }
        }
    }

    fun getProductInfo(): Map<String, Any?>? {
        val details = productDetails ?: return null
        val offer = details.subscriptionOfferDetails?.firstOrNull()
        // Get the paid phase (last phase), not the free trial phase (first phase)
        val phases = offer?.pricingPhases?.pricingPhaseList
        val pricingPhase = phases?.lastOrNull { it.priceAmountMicros > 0 }
            ?: phases?.lastOrNull()

        return mapOf(
            "productId" to details.productId,
            "title" to details.title,
            "description" to details.description,
            "price" to (pricingPhase?.formattedPrice ?: ""),
            "localizedPrice" to (pricingPhase?.formattedPrice ?: ""),
            "priceAmount" to (pricingPhase?.priceAmountMicros?.let { it / 1_000_000.0 } ?: 0.0),
            "currencyCode" to (pricingPhase?.priceCurrencyCode ?: "USD")
        )
    }

    fun isReady(): Boolean = isConnected

    fun destroy() {
        billingClient?.endConnection()
        billingClient = null
        isConnected = false
    }
}
