/**
 * Copyright 2014 AnjLab
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.anjlab.android.iab.v3;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.AcknowledgePurchaseResponseListener;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.SkuDetailsResponseListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BillingProcessor extends BillingBase implements PurchasesUpdatedListener {

    private void startServiceConnection(final Runnable executeOnSuccess) {
        mBillingClient.startConnection(new BillingClientStateListener() {

            @Override
            public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
                Log.d(LOG_TAG, "Setup finished. Response code: " + billingResult.getResponseCode());
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    mIsServiceConnected = true;
                    if (executeOnSuccess != null) {
                        executeOnSuccess.run();
                    }
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                Log.d("Billing", "onBillingServiceDisconnected");
                mIsServiceConnected = false;
            }
        });
    }

    private static final String LOG_TAG = "iabv3";
    private boolean mIsServiceConnected;
    private static final String SETTINGS_VERSION = ".v2_6";
    private static final String MANAGED_PRODUCTS_CACHE_KEY = ".products.cache" + SETTINGS_VERSION;
    private static final String SUBSCRIPTIONS_CACHE_KEY = ".subscriptions.cache" + SETTINGS_VERSION;
    private static final String PURCHASE_PAYLOAD_CACHE_KEY = ".purchase.last" + SETTINGS_VERSION;
    private BillingClient mBillingClient;
    private final String signatureBase64;
    private final BillingCache cachedProducts;
    private final BillingCache cachedSubscriptions;
    private final Map<String, SkuDetails> mSkuDetailsCache;
    private final IBillingHandler mEventHandler;
    private boolean isOneTimePurchasesSupported;
    private boolean isSubsUpdateSupported;
    private boolean isSubscriptionOnVRSupported;
    private boolean isOneTimePurchaseOnVRSupported;

    /**
     * Returns a new {@link BillingProcessor}, without immediately binding to Play Services. If you use
     * this factory, then you must call {@link #initialize()} afterwards.
     */
    public static BillingProcessor newBillingProcessor(Context context, String licenseKey,
                                                       IBillingHandler handler) {
        return new BillingProcessor(context, licenseKey, handler, false);
    }


    public BillingProcessor(Context context, String licenseKey, IBillingHandler handler) {
        this(context, licenseKey, handler, true);
    }

    private BillingProcessor(Context context, String licenseKey, IBillingHandler handler,
                             boolean bindImmediately) {
        super(context.getApplicationContext());
        signatureBase64 = licenseKey;
        mEventHandler = handler;
        cachedProducts = new BillingCache(getContext(), MANAGED_PRODUCTS_CACHE_KEY);
        cachedSubscriptions = new BillingCache(getContext(), SUBSCRIPTIONS_CACHE_KEY);
        mSkuDetailsCache = new HashMap<>();
        mBillingClient = BillingClient.newBuilder(context.getApplicationContext())
                .setListener(this)
                .enablePendingPurchases()
                .build();
        if (bindImmediately) {
            bindPlayServices();
        }
    }

    @Override
    public void onPurchasesUpdated(BillingResult result, @Nullable List<Purchase> purchases) {
        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
            if (purchases == null) {
                return;
            }
            for (Purchase purchase : purchases) {
                if (verifyPurchaseSignature(purchase.getSku(), purchase.getOriginalJson(),
                        purchase.getSignature())) {
                    BillingCache cache;
                    if (TextUtils.equals(detectPurchaseTypeFromPurchaseResponseData(), BillingClient.SkuType.INAPP)) {
                        cache = cachedProducts;
                    } else {
                        cache = cachedSubscriptions;
                    }
                    cache.put(purchase.getSku(), purchase);
                    if (mEventHandler != null) {
                        mEventHandler.onProductPurchased(purchase);
                    }
                } else {
                    Log.e(LOG_TAG, "Public key signature doesn't match!");
                    reportBillingError(BillingResult.newBuilder()
                            .setResponseCode(BillingClient.BillingResponseCode.ERROR)
                            .setDebugMessage("Public key signature doesn't match!")
                            .build());
                }
            }
        } else {
            reportBillingError(result);
        }
    }


    /**
     * Binds to Play Services. When complete, caller will be notified via
     * {@link IBillingHandler#onBillingInitialized()}.
     */
    public void initialize() {
        bindPlayServices();
    }

    private static Intent getBindServiceIntent() {
        Intent intent = new Intent("com.android.vending.billing.InAppBillingService.BIND");
        intent.setPackage("com.android.vending");
        return intent;
    }

    private void bindPlayServices() {
        startServiceConnection(new Runnable() {
            @Override
            public void run() {
                if (mEventHandler != null) {
                    mEventHandler.onBillingInitialized();
                }
                Log.d(LOG_TAG, "Setup successful. Querying inventory.");
                queryPurchasesFromGoogle();

            }
        });
    }

    public void queryPurchasesFromGoogle() {
        if (loadOwnedPurchasesFromGoogle() && mEventHandler != null) {
            if (cachedProducts.getContents().size() > 0) {
                mEventHandler.onPurchaseHistoryRestored(cachedProducts.getContents());
            }
        }
    }

    /**
     * @param activity         the activity calling this method
     * @param oldProductId     passing null will act the same as
     *                         {@link #subscribe(Activity, String, String)}
     * @param productId        the new subscription id
     * @param developerPayload the developer payload
     * @return {@code false} if {@code oldProductIds} is not {@code null} AND change subscription
     * is not supported.
     * @see <a href="https://developer.android.com/google/play/billing/billing_reference.html#getBuyIntentExtraParams">extra
     * params documentation on developer.android.com</a>
     */
    public boolean updateSubscriptionOnVR(Activity activity, String oldProductId,
                                          String productId, String developerPayload) {
        if (!TextUtils.isEmpty(oldProductId) && !isSubscriptionUpdateSupported()) {
            return false;
        }

        // if API v7 is not supported, let's fallback to the previous method
        if (!isSubscriptionOnVRSupported()) {
            return updateSubscription(activity, oldProductId, productId, developerPayload);
        }

        return purchase(activity,
                oldProductId,
                productId,
                BillingClient.SkuType.SUBS,
                developerPayload,
                true);
    }


    public static boolean isIabServiceAvailable(Context context) {
        final PackageManager packageManager = context.getPackageManager();
        List<ResolveInfo> list = packageManager.queryIntentServices(getBindServiceIntent(), 0);
        return list != null && list.size() > 0;
    }

    public void release() {
        if (isInitialized()) {
            mBillingClient.endConnection();
            mBillingClient = null;
        }
    }

    public boolean isInitialized() {
        return mIsServiceConnected && mBillingClient != null;
    }

    public boolean isPurchased(String productId) {
        return cachedProducts.includesProduct(productId);
    }

    public boolean isSubscribed(String productId) {
        return cachedSubscriptions.includesProduct(productId);
    }

    public List<String> listOwnedProducts() {
        return cachedProducts.getContents();
    }

    public List<String> listOwnedSubscriptions() {
        return cachedSubscriptions.getContents();
    }

    private boolean loadPurchasesByType(String type, BillingCache cacheStorage) {
        if (!isInitialized()) {
            return false;
        }

        Purchase.PurchasesResult purchasesResult = mBillingClient.queryPurchases(type);
        if (purchasesResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
            cacheStorage.clear();
            List<Purchase> purchaseList = purchasesResult.getPurchasesList();
            if (purchaseList != null) {
                for (Purchase purchase : purchaseList) {
                    if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED || purchase.getPurchaseState() == Purchase.PurchaseState.UNSPECIFIED_STATE) {
                        cacheStorage.put(purchase.getSku(), purchase);
                    }
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Attempt to fetch purchases from the server and update our cache if successful
     *
     * @return {@code true} if all retrievals are successful, {@code false} otherwise
     */
    public boolean loadOwnedPurchasesFromGoogle() {
        return loadPurchasesByType(BillingClient.SkuType.INAPP, cachedProducts) &&
                loadPurchasesByType(BillingClient.SkuType.SUBS, cachedSubscriptions);
    }

    public boolean purchase(Activity activity, String productId) {
        return purchase(activity, null, productId, BillingClient.SkuType.INAPP, null);
    }

    public boolean subscribe(Activity activity, String productId) {
        return purchase(activity, null, productId, BillingClient.SkuType.SUBS, null);
    }

    public boolean purchase(Activity activity, String productId, String developerPayload) {
        return purchase(activity, productId, BillingClient.SkuType.INAPP, developerPayload);
    }

    public boolean subscribe(Activity activity, String productId, String developerPayload) {
        return purchase(activity, productId, BillingClient.SkuType.SUBS, developerPayload);
    }

    /***
     * Purchase a product
     *
     * @param activity the activity calling this method
     * @param productId the product id to purchase
     * @see <a href="https://developer.android.com/google/play/billing/billing_reference.html#getBuyIntentExtraParams">extra
     * params documentation on developer.android.com</a>
     * @return {@code false} if the billing system is not initialized, {@code productId} is empty
     * or if an exception occurs. Will return {@code true} otherwise.
     */
    public boolean purchaseOnVR(Activity activity, String productId, String developerPayload) {
        if (!isOneTimePurchaseWithVRSupported()) {
            return purchase(activity, productId, developerPayload);
        } else {
            return purchase(activity, null, productId, BillingClient.SkuType.INAPP, developerPayload, true);
        }
    }

    /**
     * Subscribe to a product
     *
     * @param activity  the activity calling this method
     * @param productId the product id to purchase
     * @return {@code false} if the billing system is not initialized, {@code productId} is empty or if an exception occurs.
     * Will return {@code true} otherwise.
     * @see <a href="https://developer.android.com/google/play/billing/billing_reference.html#getBuyIntentExtraParams">extra
     * params documentation on developer.android.com</a>
     */
    public boolean subscribe(Activity activity, String productId, String developerPayload,
                             boolean isVR) {
        return purchase(activity,
                null,
                productId,
                BillingClient.SkuType.SUBS,
                developerPayload,
                isSubscriptionOnVRSupported() && isVR);
    }

    public boolean isOneTimePurchaseSupported() {
        if (!isInitialized()) {
            Log.e(LOG_TAG, "Make sure BillingProcessor was initialized before calling isOneTimePurchaseSupported()");
            return false;
        }
        if (isOneTimePurchasesSupported) {
            return true;
        }
        isOneTimePurchasesSupported = mBillingClient.isReady();
        return isOneTimePurchasesSupported;
    }

    public boolean isSubscriptionUpdateSupported() {
        // Avoid calling the service again if this value is true
        if (isSubsUpdateSupported) {
            return true;
        }


        int response = mBillingClient.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS_UPDATE).getResponseCode();

        isSubsUpdateSupported = response == BillingClient.BillingResponseCode.OK;

        return isSubsUpdateSupported;
    }

    /**
     * Check VR support for subscriptions
     *
     * @return {@code true} if the current API supports calling getBuyIntentExtraParams() for
     * subscriptions, {@code false} otherwise.
     */
    public boolean isSubscriptionOnVRSupported() {
        if (isSubscriptionOnVRSupported) {
            return true;
        }


        int response =
                mBillingClient.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS_ON_VR).getResponseCode();
        isSubscriptionOnVRSupported = response == BillingClient.BillingResponseCode.OK;
        return isSubscriptionOnVRSupported;
    }

    /**
     * Check VR support for one-time purchases
     *
     * @return {@code true} if the current API supports calling getBuyIntentExtraParams() for
     * one-time purchases, {@code false} otherwise.
     */
    public boolean isOneTimePurchaseWithVRSupported() {
        if (isOneTimePurchaseOnVRSupported) {
            return true;
        }


        int response =
                mBillingClient.isFeatureSupported(BillingClient.FeatureType.IN_APP_ITEMS_ON_VR).getResponseCode();
        isOneTimePurchaseOnVRSupported = response == BillingClient.BillingResponseCode.OK;
        return isOneTimePurchaseOnVRSupported;
    }

    /**
     * Change subscription i.e. upgrade or downgrade
     *
     * @param activity     the activity calling this method
     * @param oldProductId passing null will act the same as {@link #subscribe(Activity, String)}
     * @param productId    the new subscription id
     * @return {@code false} if {@code oldProductIds} is not {@code null} AND change subscription
     * is not supported.
     */
    public boolean updateSubscription(Activity activity, String oldProductId,
                                      String productId) {
        return updateSubscription(activity, oldProductId, productId, null);
    }

    /**
     * @param activity         the activity calling this method
     * @param productId        the new subscription id
     * @param developerPayload the developer payload
     * @return {@code false} if {@code oldProductIds} is not {@code null} AND change subscription
     * is not supported.
     */
    public boolean updateSubscription(Activity activity, String oldProductId,
                                      String productId, String developerPayload) {
        if (!TextUtils.isEmpty(oldProductId) && !isSubscriptionUpdateSupported()) {
            return false;
        }
        return purchase(activity, oldProductId, productId, BillingClient.SkuType.SUBS,
                developerPayload, false);
    }

    public void consumePurchase(final String productId) {
        if (!isInitialized()) {
            return;
        }

        final Purchase transaction = getPurchaseTransactionDetails(productId);
        if (transaction != null && !TextUtils.isEmpty(transaction.getPurchaseToken())) {
            ConsumeParams params = ConsumeParams.newBuilder()
                    .setPurchaseToken(transaction.getPurchaseToken())
                    .build();
            mBillingClient.consumeAsync(params, new ConsumeResponseListener() {
                @Override
                public void onConsumeResponse(BillingResult result, String purchaseToken) {
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        cachedProducts.remove(productId);
                        savePurchasePayload(null);
                        Log.d(LOG_TAG, "Successfully consumed " + productId + " purchase.");
                        if (mEventHandler != null) {
                            mEventHandler.onConsumeSuccess(transaction);
                        }
                    } else {
                        if (result.getResponseCode() == BillingClient.BillingResponseCode.ITEM_NOT_OWNED) {
                            cachedProducts.remove(productId);
                            savePurchasePayload(null);
                        }
                        reportBillingError(result);
                        Log.e(LOG_TAG, String.format("Failed to consume %s: %d", productId, result.getResponseCode()));
                    }
                }
            });

        }
    }

    private boolean purchase(Activity activity, String productId, String purchaseType,
                             String developerPayload) {
        return purchase(activity, null, productId, purchaseType, developerPayload);
    }

    private boolean purchase(Activity activity, String oldProductId, String productId,
                             String purchaseType, String developerPayload) {
        return purchase(activity, oldProductId, productId, purchaseType, developerPayload, false);
    }

    private boolean purchase(Activity activity, String oldProductId, String productId,
                             String purchaseType, String developerPayload, boolean isSupportVR) {
        if (!isInitialized() || TextUtils.isEmpty(productId) || TextUtils.isEmpty(purchaseType)) {
            return false;
        }

        String purchasePayload = purchaseType + ":" + productId;
        if (!purchaseType.equals(BillingClient.SkuType.SUBS)) {
            purchasePayload += ":" + UUID.randomUUID().toString();
        }
        if (developerPayload != null) {
            purchasePayload += ":" + developerPayload;
        }
        savePurchasePayload(purchasePayload);

        SkuDetails details = getSkuDetails(productId);
        if (details == null) {
            reportBillingError(BillingResult.newBuilder()
                    .setDebugMessage("SkuDetails not found")
                    .setResponseCode(BillingClient.BillingResponseCode.DEVELOPER_ERROR)
                    .build());
            return false;
        }
        BillingFlowParams purchaseParams = BillingFlowParams.newBuilder()
                .setVrPurchaseFlow(isSupportVR)
                .setSkuDetails(details)
                .build();
        int response = mBillingClient.launchBillingFlow(activity, purchaseParams).getResponseCode();
        return response == BillingClient.BillingResponseCode.OK;

    }


    @Nullable
    private Purchase getPurchaseTransactionDetails(String productId, BillingCache cache) {
        Purchase details = cache.getDetails(productId);
        if (details != null && !TextUtils.isEmpty(details.getOriginalJson())) {
            return details;
        }
        return null;
    }

    public void acknowledgeSubscription(final String productId) {
        if (!isInitialized()) {
            return;
        }

        final Purchase transaction = getSubscriptionTransactionDetails(productId);
        if (transaction != null && !TextUtils.isEmpty(transaction.getPurchaseToken())) {
            if (transaction.isAcknowledged()) {
                return;
            }
            AcknowledgePurchaseParams acknowledgePurchaseParams =
                    AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(transaction.getPurchaseToken())
                            .build();

            mBillingClient.acknowledgePurchase(acknowledgePurchaseParams, new AcknowledgePurchaseResponseListener() {
                @Override
                public void onAcknowledgePurchaseResponse(BillingResult result) {
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        savePurchasePayload(null);
                        Log.d(LOG_TAG, "Successfully consumed " + productId + " purchase.");
                        if (mEventHandler != null) {
                            mEventHandler.onAcknowledgeSuccess(transaction);
                        }
                    } else {
                        reportBillingError(result);
                        Log.e(LOG_TAG, String.format("Failed to acknowledgePurchase %s: %d", productId, result.getResponseCode()));
                    }
                }
            });

        }
    }

    public void acknowledgeManagedProduct(final String productId) {
        if (!isInitialized()) {
            return;
        }

        final Purchase transaction = getPurchaseTransactionDetails(productId);
        if (transaction != null && !TextUtils.isEmpty(transaction.getPurchaseToken())) {
            if (transaction.isAcknowledged()) {
                return;
            }
            AcknowledgePurchaseParams acknowledgePurchaseParams =
                    AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(transaction.getPurchaseToken())
                            .build();

            mBillingClient.acknowledgePurchase(acknowledgePurchaseParams, new AcknowledgePurchaseResponseListener() {
                @Override
                public void onAcknowledgePurchaseResponse(BillingResult result) {
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        savePurchasePayload(null);
                        Log.d(LOG_TAG, "Successfully consumed " + productId + " purchase.");
                        if (mEventHandler != null) {
                            mEventHandler.onAcknowledgeSuccess(transaction);
                        }
                    } else {
                        reportBillingError(result);
                        Log.e(LOG_TAG, String.format("Failed to acknowledgePurchase %s: %d", productId, result.getResponseCode()));
                    }
                }
            });

        }
    }

    /**
     * Callback methods where billing events are reported.
     * Apps must implement one of these to construct a BillingProcessor.
     */
    public interface IBillingHandler {

        void onProductPurchased(@Nullable Purchase details);

        void onPurchaseHistoryRestored(List<String> products);

        void onBillingError(BillingResult result);

        void onBillingInitialized();

        void onConsumeSuccess(Purchase transaction);

        void onAcknowledgeSuccess(Purchase transaction);

        void onQuerySkuDetails(List<SkuDetails> skuDetails);
    }


    private SkuDetails getSkuDetails(String productId) {
        if (mSkuDetailsCache != null && mSkuDetailsCache.containsKey(productId)) {
            return mSkuDetailsCache.get(productId);
        }
        return null;
    }

    private List<SkuDetails> getSkuDetails(List<String> productId) {
        List<SkuDetails> details = new ArrayList<>();
        if (mSkuDetailsCache != null) {
            for (String s : productId) {
                if (mSkuDetailsCache.containsKey(s)) {
                    details.add(mSkuDetailsCache.get(s));
                }
            }
        }
        return details;
    }

    public void getSkuDetailsAsync(final List<String> productIdList, String purchaseType) {
        if (productIdList != null && productIdList.size() > 0) {
            SkuDetailsParams params = SkuDetailsParams.newBuilder()
                    .setSkusList(productIdList)
                    .setType(purchaseType)
                    .build();
            mBillingClient.querySkuDetailsAsync(params, new SkuDetailsResponseListener() {
                @Override
                public void onSkuDetailsResponse(BillingResult billingResult, List<SkuDetails> skuDetails) {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        if (mEventHandler != null) {
                            for (SkuDetails details : skuDetails) {
                                mSkuDetailsCache.put(details.getSku(), details);
                            }
                            mEventHandler.onQuerySkuDetails(skuDetails);
                        }
                    } else {
                        reportBillingError(billingResult);
                        Log.e(LOG_TAG, String.format("Failed to retrieve info for %d products, %d", productIdList.size(), billingResult.getResponseCode()));
                    }
                }
            });

        }
    }

    public SkuDetails getPurchaseListingDetails(String productId) {
        return getSkuDetails(productId);
    }

    public SkuDetails getSubscriptionListingDetails(String productId) {
        return getSkuDetails(productId);
    }


    @Nullable
    public Purchase getPurchaseTransactionDetails(String productId) {
        return getPurchaseTransactionDetails(productId, cachedProducts);
    }

    @Nullable
    public Purchase getSubscriptionTransactionDetails(String productId) {
        return getPurchaseTransactionDetails(productId, cachedSubscriptions);
    }

    private String detectPurchaseTypeFromPurchaseResponseData() {
        String purchasePayload = getPurchasePayload();
        // regular flow, based on developer payload
        if (!TextUtils.isEmpty(purchasePayload) && purchasePayload.startsWith(BillingClient.SkuType.SUBS)) {
            return BillingClient.SkuType.SUBS;
        }
        return BillingClient.SkuType.INAPP;
    }


    private boolean verifyPurchaseSignature(String productId, String purchaseData, String dataSignature) {
        try {
            /*
             * Skip the signature check if the provided License Key is NULL and return true in order to
             * continue the purchase flow
             */
            return TextUtils.isEmpty(signatureBase64) ||
                    Security.verifyPurchase(productId, signatureBase64, purchaseData, dataSignature);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isValidTransactionDetails(Purchase purchase) {
        return verifyPurchaseSignature(purchase.getSku(),
                purchase.getOriginalJson(),
                purchase.getSignature());

    }

    private void savePurchasePayload(String value) {
        saveString(getPreferencesBaseKey() + PURCHASE_PAYLOAD_CACHE_KEY, value);
    }

    public String getPurchasePayload() {
        return loadString(getPreferencesBaseKey() + PURCHASE_PAYLOAD_CACHE_KEY, null);
    }

    private void reportBillingError(BillingResult result) {
        if (mEventHandler != null) {
            mEventHandler.onBillingError(result);
        }
    }
}
