package com.example.viora

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** true: VIP is bought through Google Play Billing (required for apps distributed on Google Play).
 *  false: VIP is bought through Stripe in the browser (for APKs you share yourself). */
const val USE_PLAY_BILLING = true

/** Subscription product id created in Play Console (base plans inside it: "monthly", "yearly"). */
const val PLAY_PRODUCT_ID = "viora_vip"

class PlayPlan(val id: String, val name: String, val price: String, val details: ProductDetails, val offerToken: String)

class PlayBilling(
    context: Context,
    private val onPurchases: (List<Purchase>) -> Unit,
    private val onError: (String) -> Unit
) : PurchasesUpdatedListener {

    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private suspend fun ready(): Boolean = suspendCancellableCoroutine { cont ->
        if (client.isReady) cont.resume(true)
        else client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    suspend fun plans(): List<PlayPlan> {
        if (!ready()) throw IllegalStateException("Google Play isn't available. Install the app from Google Play.")
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PLAY_PRODUCT_ID).setProductType(BillingClient.ProductType.SUBS).build()
            )
        ).build()
        val details = client.queryProductDetails(params).productDetailsList?.firstOrNull() ?: return emptyList()
        return details.subscriptionOfferDetails.orEmpty()
            .groupBy { it.basePlanId }
            .map { (basePlan, offers) ->
                val offer = offers.firstOrNull { it.offerId == null } ?: offers.first()
                val phase = offer.pricingPhases.pricingPhaseList.last()
                val period = when (phase.billingPeriod) { "P1M" -> "Monthly"; "P1Y" -> "Yearly"; else -> basePlan }
                val per = when (phase.billingPeriod) { "P1M" -> " / month"; "P1Y" -> " / year"; else -> "" }
                PlayPlan(basePlan, period, phase.formattedPrice + per, details, offer.offerToken)
            }
    }

    fun buy(activity: Activity, plan: PlayPlan, accountId: String) {
        val product = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(plan.details).setOfferToken(plan.offerToken).build()
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(product))
            .setObfuscatedAccountId(accountId)
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) onError(result.debugMessage.ifBlank { "Couldn't start the purchase" })
    }

    suspend fun owned(): List<Purchase> {
        if (!ready()) return emptyList()
        return client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ).purchasesList
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.let { onPurchases(it) }
            BillingClient.BillingResponseCode.USER_CANCELED -> {}
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> onError("You already have this subscription. Pull down to refresh.")
            else -> onError(result.debugMessage.ifBlank { "The purchase didn't go through" })
        }
    }
}
