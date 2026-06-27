package net.blocklegends.billing;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.AcknowledgePurchaseResponseListener;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.ProductDetailsResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryProductDetailsResult;
import com.android.billingclient.api.QueryPurchasesParams;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import net.blocklegends.MainActivity;
import net.blocklegends.MainApp;
import net.blocklegends.natives.GNatives;
import shared.simple.JSONArray;
import shared.simple.JSONObject;

/* loaded from: classes.dex */
public class Billing implements LifecycleObserver, PurchasesUpdatedListener, BillingClientStateListener, PurchasesResponseListener {
    private static final long BILLING_FLOW_TIMEOUT_MS = 180000;
    public static volatile Billing INSTANCE = null;
    private static final int MAX_RECONNECT = 4;
    private static final String TAG = "BillingLifecycle";
    private Application app;
    private BillingClient billingClient;
    private boolean billingExternalUiOpen;
    private Runnable billingExternalUiTimeoutRunnable;
    private volatile boolean billingUnavailable;
    public List<Purchase> purchaseUpdateEvent = new LinkedList();
    public List<Purchase> purchases = new LinkedList();
    private final ArrayList<QueryProductDetailsParams.Product> productsSub = new ArrayList<>();
    private final ArrayList<QueryProductDetailsParams.Product> productsInapp = new ArrayList<>();
    private final Set<String> lastKnownTokens = new HashSet();
    private final Object billingExternalUiLock = new Object();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int retryCount = 0;
    private int reconnectAttempt = 0;
    private volatile boolean isQueryingInApp = false;
    private volatile boolean isQueryingSubs = false;
    private final ProductDetailsResponseListener listener = new ProductDetailsResponseListener() { // from class: net.blocklegends.billing.Billing$$ExternalSyntheticLambda2
        @Override // com.android.billingclient.api.ProductDetailsResponseListener
        public final void onProductDetailsResponse(BillingResult billingResult, QueryProductDetailsResult queryProductDetailsResult) {
            Billing.this.m2119lambda$new$4$netblocklegendsbillingBilling(billingResult, queryProductDetailsResult);
        }
    };

    /* loaded from: classes.dex */
    public enum BillingProductType {
        SUBS(BillingClient.ProductType.SUBS),
        INAPP(BillingClient.ProductType.INAPP);

        private String data;

        BillingProductType(String str) {
            this.data = str;
        }

        public String getValue() {
            return this.data;
        }
    }

    private Billing(Application application) {
        this.app = application;
        create();
    }

    private void endBillingExternalUi(String str) {
        synchronized (this.billingExternalUiLock) {
            Runnable runnable = this.billingExternalUiTimeoutRunnable;
            if (runnable != null) {
                this.handler.removeCallbacks(runnable);
                this.billingExternalUiTimeoutRunnable = null;
            }
            if (this.billingExternalUiOpen) {
                this.billingExternalUiOpen = false;
                MainActivity.endExternalUi(MainActivity.EXTERNAL_UI_BILLING_FLOW);
                Log.d(TAG, "Billing external UI ended: " + str);
            }
        }
    }

    private boolean isUnchangedPurchaseList(List<Purchase> list) {
        if (list == null) {
            return this.lastKnownTokens.isEmpty();
        }
        HashSet hashSet = new HashSet();
        for (Purchase purchase : list) {
            if (purchase != null) {
                hashSet.add(purchase.getPurchaseToken());
            }
        }
        return hashSet.equals(this.lastKnownTokens);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$acknowledgePurchase$6(BillingResult billingResult) {
        try {
            if (billingResult == null) {
                Log.e(TAG, "acknowledgePurchase: BillingResult is null");
                return;
            }
            Log.d(TAG, "acknowledgePurchase: " + billingResult.getResponseCode() + " " + billingResult.getDebugMessage());
        } catch (Exception e) {
            Log.e(TAG, "Error in acknowledgePurchase callback: " + e.getMessage(), e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$onBillingSetupFinished$2(final Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            Log.w(TAG, "Activity is no longer valid, skipping billing error dialog");
            return;
        }
        try {
            new AlertDialog.Builder(activity).setTitle("Billing Error").setMessage("Your device does not support Google Play Billing or it is currently unavailable. Please update Google Play Store and try again.").setCancelable(false).setPositiveButton("Exit App", new DialogInterface.OnClickListener() { // from class: net.blocklegends.billing.Billing$$ExternalSyntheticLambda4
                @Override // android.content.DialogInterface.OnClickListener
                public final void onClick(DialogInterface dialogInterface, int r2) {
                    activity.finishAffinity();
                }
            }).show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing billing error dialog: " + e.getMessage(), e);
        }
    }

    private void logAcknowledgementStatus(List<Purchase> list) {
        Iterator<Purchase> it = list.iterator();
        int r3 = 0;
        int r0 = 0;
        while (it.hasNext()) {
            if (it.next().isAcknowledged()) {
                r3++;
            } else {
                r0++;
            }
        }
        Log.d(TAG, "logAcknowledgementStatus: acknowledged=" + r3 + " unacknowledged=" + r0);
    }

    private void markBillingUnavailable(String str, Throwable th) {
        this.billingUnavailable = true;
        this.isQueryingSubs = false;
        this.isQueryingInApp = false;
        this.handler.removeCallbacksAndMessages(null);
        if (th == null) {
            Log.e(TAG, str);
        } else {
            Log.e(TAG, str, th);
        }
    }

    private void processPurchases(List<Purchase> list) {
        List<String> products;
        try {
            if (list == null) {
                Log.d(TAG, "processPurchases: with no purchases");
                return;
            }
            Log.d(TAG, "processPurchases: " + list.size() + " purchase(s)");
            if (isUnchangedPurchaseList(list)) {
                Log.d(TAG, "processPurchases: Purchase list has not changed, resending to server");
            }
            this.purchaseUpdateEvent.clear();
            this.purchaseUpdateEvent.addAll(list);
            this.purchases.clear();
            this.purchases.addAll(list);
            this.lastKnownTokens.clear();
            for (Purchase purchase : list) {
                if (purchase != null) {
                    this.lastKnownTokens.add(purchase.getPurchaseToken());
                }
            }
            logAcknowledgementStatus(list);
            List<Purchase> list2 = this.purchases;
            if (list2 != null && !list2.isEmpty()) {
                JSONObject jSONObject = new JSONObject();
                for (int r1 = 0; r1 < this.purchases.size(); r1++) {
                    Purchase purchase2 = this.purchases.get(r1);
                    if (purchase2 != null) {
                        jSONObject.put(purchase2.getPurchaseToken() + "%###%" + purchase2.getOrderId(), new JSONArray(purchase2.getProducts()));
                    }
                }
                GNatives.onProcessPurchases(jSONObject.toJSONString());
                for (BillingProducts billingProducts : BillingProducts.values()) {
                    for (int r4 = 0; r4 < this.purchases.size(); r4++) {
                        Purchase purchase3 = this.purchases.get(r4);
                        if (purchase3 != null && (products = purchase3.getProducts()) != null) {
                            String purchaseToken = purchase3.getPurchaseToken();
                            for (int r7 = 0; r7 < products.size(); r7++) {
                                String str = products.get(r7);
                                if (str != null && str.equals(billingProducts.getProductId())) {
                                    billingProducts.setOwned(true);
                                    billingProducts.setPurchaseToken(purchaseToken);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private void queryInApp() {
        try {
            if (this.billingUnavailable) {
                Log.w(TAG, "Billing unavailable, skipping INAPP query");
                return;
            }
            if (this.isQueryingInApp) {
                Log.w(TAG, "INAPP query already in progress, skipping");
                return;
            }
            BillingClient billingClient = this.billingClient;
            if (billingClient != null && billingClient.isReady()) {
                ArrayList<QueryProductDetailsParams.Product> arrayList = this.productsInapp;
                if (arrayList != null && !arrayList.isEmpty()) {
                    this.isQueryingInApp = true;
                    Log.d(TAG, "Starting INAPP query with " + this.productsInapp.size() + " products");
                    this.billingClient.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(this.productsInapp).build(), this.listener);
                    return;
                }
                Log.w(TAG, "INAPP product list is empty, marking as complete");
                BillingProducts.SETUP_INAPP = true;
                if (BillingProducts.SETUP_SUBS) {
                    try {
                        GNatives.onBillingSetupComplete();
                        queryPurchases();
                        return;
                    } catch (Exception e) {
                        Log.e(TAG, "Error in completion: " + e.getMessage(), e);
                        return;
                    }
                }
                return;
            }
            Log.e(TAG, "BillingClient not ready for INAPP query");
        } catch (Exception e2) {
            Log.e(TAG, "Error in queryInApp: " + e2.getMessage(), e2);
            this.isQueryingInApp = false;
            BillingProducts.SETUP_INAPP = true;
        }
    }

    private void querySubs() {
        try {
            if (this.billingUnavailable) {
                Log.w(TAG, "Billing unavailable, skipping SUBS query");
                return;
            }
            if (this.isQueryingSubs) {
                Log.w(TAG, "SUBS query already in progress, skipping");
                return;
            }
            BillingClient billingClient = this.billingClient;
            if (billingClient != null && billingClient.isReady()) {
                ArrayList<QueryProductDetailsParams.Product> arrayList = this.productsSub;
                if (arrayList != null && !arrayList.isEmpty()) {
                    this.isQueryingSubs = true;
                    Log.d(TAG, "Starting SUBS query with " + this.productsSub.size() + " products");
                    this.billingClient.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(this.productsSub).build(), this.listener);
                    return;
                }
                Log.w(TAG, "SUBS product list is empty, marking as complete");
                BillingProducts.SETUP_SUBS = true;
                if (BillingProducts.SETUP_INAPP) {
                    return;
                }
                queryInApp();
                return;
            }
            Log.e(TAG, "BillingClient not ready for SUBS query");
        } catch (Exception e) {
            Log.e(TAG, "Error in querySubs: " + e.getMessage(), e);
            this.isQueryingSubs = false;
            BillingProducts.SETUP_SUBS = true;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void retryBillingConnection() {
        BillingClient billingClient;
        if (this.billingUnavailable || (billingClient = this.billingClient) == null || billingClient.isReady()) {
            return;
        }
        Log.d(TAG, "Retrying BillingClient connection… attempt " + this.reconnectAttempt);
        startConnectionSafely("retry-" + this.reconnectAttempt);
    }

    private void retryWithBackoff() {
        if (this.billingUnavailable) {
            return;
        }
        long pow = ((long) Math.pow(2.0d, this.retryCount)) * 1000;
        int r2 = this.retryCount;
        this.retryCount = r2 + 1;
        if (r2 < 3) {
            this.handler.postDelayed(new Runnable() { // from class: net.blocklegends.billing.Billing$$ExternalSyntheticLambda7
                @Override // java.lang.Runnable
                public final void run() {
                    Billing.this.m2121lambda$retryWithBackoff$0$netblocklegendsbillingBilling();
                }
            }, pow);
        }
    }

    public static void startBillingService() {
        if (INSTANCE == null) {
            synchronized (Billing.class) {
                if (INSTANCE == null) {
                    INSTANCE = new Billing(MainApp.getApp());
                }
            }
        }
    }

    private boolean startConnectionSafely(String str) {
        if (this.billingUnavailable) {
            return false;
        }
        BillingClient billingClient = this.billingClient;
        if (billingClient == null) {
            Log.e(TAG, "BillingClient is null, cannot start connection: " + str);
            return false;
        }
        if (billingClient.isReady()) {
            return true;
        }
        try {
            Log.d(TAG, "BillingClient: Start connection... " + str);
            this.billingClient.startConnection(this);
            return true;
        } catch (SecurityException e) {
            markBillingUnavailable("Billing service bind blocked by Android user isolation", e);
            return false;
        } catch (RuntimeException e2) {
            Log.e(TAG, "BillingClient startConnection failed: " + str, e2);
            return false;
        }
    }

    private boolean tryBeginBillingExternalUi() {
        synchronized (this.billingExternalUiLock) {
            if (this.billingExternalUiOpen) {
                return false;
            }
            Runnable runnable = this.billingExternalUiTimeoutRunnable;
            if (runnable != null) {
                this.handler.removeCallbacks(runnable);
            }
            MainActivity.beginExternalUi(MainActivity.EXTERNAL_UI_BILLING_FLOW);
            this.billingExternalUiOpen = true;
            Runnable runnable2 = new Runnable() { // from class: net.blocklegends.billing.Billing$$ExternalSyntheticLambda5
                @Override // java.lang.Runnable
                public final void run() {
                    Billing.this.m2122x31ead9a9();
                }
            };
            this.billingExternalUiTimeoutRunnable = runnable2;
            this.handler.postDelayed(runnable2, BILLING_FLOW_TIMEOUT_MS);
            return true;
        }
    }

    public void acknowledgePurchase(String str) {
        try {
            Log.d(TAG, "acknowledgePurchase");
            if (this.billingUnavailable) {
                Log.w(TAG, "acknowledgePurchase: Billing unavailable");
                return;
            }
            if (str != null && !str.isEmpty()) {
                BillingClient billingClient = this.billingClient;
                if (billingClient != null && billingClient.isReady()) {
                    this.billingClient.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(str).build(), new AcknowledgePurchaseResponseListener() { // from class: net.blocklegends.billing.Billing$$ExternalSyntheticLambda6
                        @Override // com.android.billingclient.api.AcknowledgePurchaseResponseListener
                        public final void onAcknowledgePurchaseResponse(BillingResult billingResult) {
                            Billing.lambda$acknowledgePurchase$6(billingResult);
                        }
                    });
                    return;
                }
                Log.e(TAG, "acknowledgePurchase: BillingClient is not ready");
                return;
            }
            Log.e(TAG, "acknowledgePurchase: purchaseToken is null or empty");
        } catch (Exception e) {
            Log.e(TAG, "Error in acknowledgePurchase: " + e.getMessage(), e);
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    public void create() {
        try {
            Log.d(TAG, "ON_CREATE");
            Application application = this.app;
            if (application == null) {
                Log.e(TAG, "Application is null, cannot create BillingClient");
                return;
            }
            BillingClient build = BillingClient.newBuilder(application).setListener(this).enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()).build();
            this.billingClient = build;
            if (build == null) {
                Log.e(TAG, "Failed to create BillingClient");
            } else {
                startConnectionSafely("create");
            }
        } catch (Exception e) {
            if (e instanceof SecurityException) {
                markBillingUnavailable("create security exception", e);
            } else {
                Log.e(TAG, "Error in create(): " + e.getMessage(), e);
            }
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    public void destroy() {
        try {
            Log.d(TAG, "ON_DESTROY");
            this.isQueryingSubs = false;
            this.isQueryingInApp = false;
            this.billingUnavailable = false;
            this.handler.removeCallbacksAndMessages(null);
            BillingClient billingClient = this.billingClient;
            if (billingClient != null && billingClient.isReady()) {
                Log.d(TAG, "BillingClient can only be used once -- closing connection");
                this.billingClient.endConnection();
            }
            this.billingClient = null;
            INSTANCE = null;
        } catch (Exception e) {
            Log.e(TAG, "Error in destroy(): " + e.getMessage(), e);
        }
    }

    public boolean isLoadedProducts() {
        if (this.billingUnavailable) {
            return true;
        }
        return BillingProducts.SETUP_INAPP && BillingProducts.SETUP_SUBS;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$new$4$net-blocklegends-billing-Billing, reason: not valid java name */
    public /* synthetic */ void m2119lambda$new$4$netblocklegendsbillingBilling(BillingResult billingResult, QueryProductDetailsResult queryProductDetailsResult) {
        try {
            if (this.billingUnavailable) {
                Log.w(TAG, "Billing unavailable, ignoring product details response");
                return;
            }
            if (billingResult == null) {
                Log.e(TAG, "BillingResult is null in listener");
                return;
            }
            List<ProductDetails> productDetailsList = queryProductDetailsResult != null ? queryProductDetailsResult.getProductDetailsList() : null;
            int responseCode = billingResult.getResponseCode();
            Log.d(TAG, "ProductDetailsResponse: code=" + responseCode + ", listSize=" + (productDetailsList != null ? productDetailsList.size() : 0));
            if (responseCode == 0) {
                this.retryCount = 0;
                if (this.isQueryingSubs) {
                    BillingProducts.SETUP_SUBS = true;
                    this.isQueryingSubs = false;
                    Log.d(TAG, "SUBS query completed");
                } else if (this.isQueryingInApp) {
                    BillingProducts.SETUP_INAPP = true;
                    this.isQueryingInApp = false;
                    Log.d(TAG, "INAPP query completed");
                }
                if (productDetailsList == null || productDetailsList.isEmpty()) {
                    Log.w(TAG, "Product list is empty or null");
                } else {
                    for (ProductDetails productDetails : productDetailsList) {
                        if (productDetails != null) {
                            try {
                                String productId = productDetails.getProductId();
                                for (BillingProducts billingProducts : BillingProducts.values()) {
                                    if (billingProducts.getProductId().equals(productId)) {
                                        billingProducts.setup(productDetails);
                                        Log.d(TAG, "Product setup: " + productId + " -> " + billingProducts.name());
                                    }
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error setting up product: " + e.getMessage(), e);
                            }
                        }
                    }
                }
            } else {
                if (responseCode == 2) {
                    Log.w(TAG, "Service unavailable, retrying with backoff");
                    this.isQueryingSubs = false;
                    this.isQueryingInApp = false;
                    retryWithBackoff();
                    return;
                }
                Log.e(TAG, "Query failed with code: " + responseCode + ", message: " + billingResult.getDebugMessage());
                this.isQueryingSubs = false;
                this.isQueryingInApp = false;
            }
            if (!BillingProducts.SETUP_SUBS || BillingProducts.SETUP_INAPP || this.isQueryingInApp) {
                if (BillingProducts.SETUP_INAPP && BillingProducts.SETUP_SUBS) {
                    Log.d(TAG, "Both SUBS and INAPP completed, calling onBillingSetupComplete");
                    try {
                        GNatives.onBillingSetupComplete();
                        queryPurchases();
                    } catch (Exception e2) {
                        Log.e(TAG, "Error in onBillingSetupComplete: " + e2.getMessage(), e2);
                    }
                }
            } else {
                Log.d(TAG, "SUBS done, starting INAPP query");
                queryInApp();
            }
        } catch (Exception e3) {
            Log.e(TAG, "Error in ProductDetailsResponseListener: " + e3.getMessage(), e3);
            this.isQueryingSubs = false;
            this.isQueryingInApp = false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$queryPurchases$3$net-blocklegends-billing-Billing, reason: not valid java name */
    public /* synthetic */ void m2120lambda$queryPurchases$3$netblocklegendsbillingBilling(BillingResult billingResult, List list) {
        try {
            processPurchases(list);
            BillingClient billingClient = this.billingClient;
            if (billingClient == null || !billingClient.isReady()) {
                return;
            }
            this.billingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(), this);
        } catch (Exception e) {
            Log.e(TAG, "Error in SUBS purchase query callback: " + e.getMessage(), e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$retryWithBackoff$0$net-blocklegends-billing-Billing, reason: not valid java name */
    public /* synthetic */ void m2121lambda$retryWithBackoff$0$netblocklegendsbillingBilling() {
        setupProducts(true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$tryBeginBillingExternalUi$5$net-blocklegends-billing-Billing, reason: not valid java name */
    public /* synthetic */ void m2122x31ead9a9() {
        Log.w(TAG, "Billing external UI timeout");
        endBillingExternalUi("timeout");
    }

    public int launchBillingFlow(Activity activity, BillingFlowParams billingFlowParams) {
        try {
            if (this.billingUnavailable) {
                Log.e(TAG, "launchBillingFlow: Billing unavailable");
                return 3;
            }
            if (activity == null) {
                Log.e(TAG, "launchBillingFlow: Activity is null");
                return 5;
            }
            if (billingFlowParams == null) {
                Log.e(TAG, "launchBillingFlow: BillingFlowParams is null");
                return 5;
            }
            BillingClient billingClient = this.billingClient;
            if (billingClient != null && billingClient.isReady()) {
                if (!tryBeginBillingExternalUi()) {
                    Log.w(TAG, "launchBillingFlow: Billing UI is already open");
                    return 6;
                }
                BillingResult launchBillingFlow = this.billingClient.launchBillingFlow(activity, billingFlowParams);
                if (launchBillingFlow == null) {
                    Log.e(TAG, "launchBillingFlow: BillingResult is null");
                    endBillingExternalUi("launch_null_result");
                    return 6;
                }
                int responseCode = launchBillingFlow.getResponseCode();
                Log.d(TAG, "launchBillingFlow: BillingResponse " + responseCode + " " + launchBillingFlow.getDebugMessage());
                if (responseCode != 0) {
                    endBillingExternalUi("launch_response_" + responseCode);
                }
                return responseCode;
            }
            Log.e(TAG, "launchBillingFlow: BillingClient is not ready");
            return -1;
        } catch (Exception e) {
            if (0 != 0) {
                endBillingExternalUi("launch_exception");
            }
            Log.e(TAG, "Error in launchBillingFlow: " + e.getMessage(), e);
            return 6;
        }
    }

    @Override // com.android.billingclient.api.BillingClientStateListener
    public void onBillingServiceDisconnected() {
        Log.d(TAG, "onBillingServiceDisconnected");
        if (this.billingUnavailable) {
            return;
        }
        int r0 = this.reconnectAttempt;
        if (r0 >= 4) {
            Log.w(TAG, "Billing reconnection failed after " + this.reconnectAttempt + " attempts.");
            markBillingUnavailable("reconnect limit reached", null);
        } else {
            long pow = ((long) Math.pow(2.0d, r0)) * 1000;
            this.reconnectAttempt++;
            this.handler.postDelayed(new Runnable() { // from class: net.blocklegends.billing.Billing$$ExternalSyntheticLambda1
                @Override // java.lang.Runnable
                public final void run() {
                    Billing.this.retryBillingConnection();
                }
            }, pow);
        }
    }

    @Override // com.android.billingclient.api.BillingClientStateListener
    public void onBillingSetupFinished(BillingResult billingResult) {
        try {
            if (billingResult == null) {
                Log.e(TAG, "onBillingSetupFinished: BillingResult is null");
                retryBillingConnection();
                return;
            }
            int responseCode = billingResult.getResponseCode();
            billingResult.getDebugMessage();
            if (responseCode == 0) {
                this.reconnectAttempt = 0;
                this.billingUnavailable = false;
                setupProducts(false);
                return;
            }
            INSTANCE = null;
            this.billingClient = null;
            if (responseCode != -2 && responseCode != 3) {
                return;
            }
            final MainActivity activity = MainActivity.getActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                Log.w(TAG, "Activity is not valid, cannot show billing error dialog");
            } else {
                activity.runOnUiThread(new Runnable() { // from class: net.blocklegends.billing.Billing$$ExternalSyntheticLambda0
                    @Override // java.lang.Runnable
                    public final void run() {
                        Billing.lambda$onBillingSetupFinished$2(activity);
                    }
                });
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    @Override // com.android.billingclient.api.PurchasesUpdatedListener
    public void onPurchasesUpdated(BillingResult billingResult, List<Purchase> list) {
        try {
            if (billingResult == null) {
                Log.wtf(TAG, "onPurchasesUpdated: null BillingResult");
                return;
            }
            int responseCode = billingResult.getResponseCode();
            Log.d(TAG, String.format("onPurchasesUpdated: %s %s", Integer.valueOf(responseCode), billingResult.getDebugMessage()));
            if (responseCode != 0) {
                if (responseCode == 1) {
                    Log.i(TAG, "onPurchasesUpdated: User canceled the purchase");
                } else if (responseCode == 5) {
                    Log.e(TAG, "onPurchasesUpdated: Developer error means that Google Play does not recognize the configuration. If you are just getting started, make sure you have configured the application correctly in the Google Play Console. The SKU product ID must match and the APK you are using must be signed with release keys.");
                } else if (responseCode == 7) {
                    Log.i(TAG, "onPurchasesUpdated: The user already owns this item");
                }
            } else if (list == null) {
                Log.d(TAG, "onPurchasesUpdated: null purchase list");
                processPurchases(null);
            } else {
                processPurchases(list);
            }
        } finally {
            endBillingExternalUi("purchases_updated");
        }
    }

    @Override // com.android.billingclient.api.PurchasesResponseListener
    public void onQueryPurchasesResponse(BillingResult billingResult, List<Purchase> list) {
        processPurchases(list);
    }

    public void queryPurchases() {
        try {
            if (this.billingUnavailable) {
                Log.w(TAG, "Billing unavailable, skipping queryPurchases");
                return;
            }
            BillingClient billingClient = this.billingClient;
            if (billingClient != null && billingClient.isReady()) {
                Log.d(TAG, "Querying purchases");
                this.billingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(), new PurchasesResponseListener() { // from class: net.blocklegends.billing.Billing$$ExternalSyntheticLambda3
                    @Override // com.android.billingclient.api.PurchasesResponseListener
                    public final void onQueryPurchasesResponse(BillingResult billingResult, List list) {
                        Billing.this.m2120lambda$queryPurchases$3$netblocklegendsbillingBilling(billingResult, list);
                    }
                });
                return;
            }
            Log.w(TAG, "BillingClient not ready for queryPurchases");
        } catch (Exception e) {
            Log.e(TAG, "Error in queryPurchases: " + e.getMessage(), e);
        }
    }

    public void setupProducts(boolean z) {
        try {
            if (this.billingUnavailable) {
                Log.w(TAG, "Billing unavailable, skipping setupProducts");
                return;
            }
            if (!z && BillingProducts.SETUP_INAPP && BillingProducts.SETUP_SUBS) {
                Log.d(TAG, "Products already setup, skipping");
                return;
            }
            BillingClient billingClient = this.billingClient;
            if (billingClient != null && billingClient.isReady()) {
                Log.d(TAG, "Setting up products, force=" + z);
                if (z) {
                    BillingProducts.SETUP_SUBS = false;
                    BillingProducts.SETUP_INAPP = false;
                    this.isQueryingSubs = false;
                    this.isQueryingInApp = false;
                }
                this.productsSub.clear();
                this.productsInapp.clear();
                for (BillingProducts billingProducts : BillingProducts.values()) {
                    if (billingProducts != null) {
                        try {
                            String productId = billingProducts.getProductId();
                            if (productId != null && !productId.isEmpty()) {
                                BillingProductType productType = billingProducts.getProductType();
                                if (productType == null) {
                                    Log.w(TAG, "Product " + productId + " has null type, skipping");
                                } else if (productType == BillingProductType.SUBS) {
                                    this.productsSub.add(QueryProductDetailsParams.Product.newBuilder().setProductId(productId).setProductType(productType.getValue()).build());
                                } else {
                                    this.productsInapp.add(QueryProductDetailsParams.Product.newBuilder().setProductId(productId).setProductType(productType.getValue()).build());
                                }
                            }
                            Log.w(TAG, "Product has null or empty ID, skipping");
                        } catch (Exception e) {
                            Log.e(TAG, "Error adding product: " + e.getMessage(), e);
                        }
                    }
                }
                Log.d(TAG, "Products prepared: SUBS=" + this.productsSub.size() + ", INAPP=" + this.productsInapp.size());
                querySubs();
                return;
            }
            Log.e(TAG, "BillingClient not ready in setupProducts");
        } catch (Exception e2) {
            Log.e(TAG, "Error in setupProducts: " + e2.getMessage(), e2);
        }
    }
}
