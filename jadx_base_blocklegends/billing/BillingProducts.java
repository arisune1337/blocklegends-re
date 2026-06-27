package net.blocklegends.billing;

import android.util.Log;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.ProductDetails;
import com.google.common.collect.ImmutableList;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import net.blocklegends.MainActivity;
import net.blocklegends.billing.Billing;

/* JADX WARN: Enum visitor error
jadx.core.utils.exceptions.JadxRuntimeException: Can't remove SSA var: r3v3 net.blocklegends.billing.BillingProducts, still in use, count: 1, list:
  (r3v3 net.blocklegends.billing.BillingProducts) from 0x00b1: FILLED_NEW_ARRAY 
  (r3v3 net.blocklegends.billing.BillingProducts)
  (r4v5 net.blocklegends.billing.BillingProducts)
  (r5v6 net.blocklegends.billing.BillingProducts)
  (r6v5 net.blocklegends.billing.BillingProducts)
  (r7v3 net.blocklegends.billing.BillingProducts)
  (r8v2 net.blocklegends.billing.BillingProducts)
 A[WRAPPED] elemType: net.blocklegends.billing.BillingProducts
	at jadx.core.utils.InsnRemover.removeSsaVar(InsnRemover.java:151)
	at jadx.core.utils.InsnRemover.unbindResult(InsnRemover.java:116)
	at jadx.core.utils.InsnRemover.lambda$unbindInsns$1(InsnRemover.java:88)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)
	at jadx.core.utils.InsnRemover.unbindInsns(InsnRemover.java:87)
	at jadx.core.utils.InsnRemover.removeAllAndUnbind(InsnRemover.java:238)
	at jadx.core.dex.visitors.EnumVisitor.convertToEnum(EnumVisitor.java:180)
	at jadx.core.dex.visitors.EnumVisitor.visit(EnumVisitor.java:100)
 */
/* JADX WARN: Failed to restore enum class, 'enum' modifier and super class removed */
/* loaded from: classes.dex */
public final class BillingProducts {
    PASS("pass_1", "pass-1", Billing.BillingProductType.SUBS),
    PASS_25_DISCOUNT("pass_1", "pass-2", Billing.BillingProductType.SUBS),
    PASS_10_DISCOUNT("pass_1", "pass-3", Billing.BillingProductType.SUBS),
    RC_BOX_1("rc_box_1_", Billing.BillingProductType.INAPP),
    RC_BOX_1_DISCOUNT("rc_box_1_discount", Billing.BillingProductType.INAPP),
    RC_BOX_2("rc_box_2", Billing.BillingProductType.INAPP),
    RC_BOX_3("rc_box_3", Billing.BillingProductType.INAPP),
    RC_BOX_4("rc_box_4", Billing.BillingProductType.INAPP),
    RC_BOX_5("rc_box_5", Billing.BillingProductType.INAPP),
    RC_BOX_6("rc_box_6", Billing.BillingProductType.INAPP);

    public static Map<BillingProducts, String> PRODUCT_NAME;
    public static BillingProducts[] RC_PRODUCTS;
    public static boolean SETUP_INAPP;
    public static boolean SETUP_SUBS;
    public static BillingProducts[] VIP_PRODUCTS;
    private String basePlanId;
    private String color;
    private boolean isOwned;
    private String offerToken;
    private double price;
    private double priceAsDouble = -1.0d;
    private String priceCurrencyCode;
    private String priceCurrenySymbol;
    private ProductDetails productDetails;
    private final String productId;
    private String productName;
    private final Billing.BillingProductType productType;
    private String purchaseToken;

    static {
        BillingProducts billingProducts = PASS;
        HashMap hashMap = new HashMap();
        PRODUCT_NAME = hashMap;
        hashMap.put(billingProducts, "§6PASS");
        billingProducts.color("§d");
        VIP_PRODUCTS = new BillingProducts[]{billingProducts};
        RC_PRODUCTS = new BillingProducts[]{r3, r4, r5, r6, r7, r8};
        SETUP_SUBS = false;
        SETUP_INAPP = false;
    }

    private BillingProducts(String str, String str2, Billing.BillingProductType billingProductType) {
        this.productId = str;
        this.productType = billingProductType;
        this.basePlanId = str2;
    }

    private BillingProducts(String str, Billing.BillingProductType billingProductType) {
        this.productId = str;
        this.productType = billingProductType;
    }

    public static String getCurrencySymbol(String str) {
        if (str == null) {
            return null;
        }
        if (str.equals("TL")) {
            str = "TRY";
        }
        try {
            return Currency.getInstance(str).getSymbol();
        } catch (Throwable unused) {
            return str;
        }
    }

    public static BillingProducts getProductFromId(String str) {
        if (str == null) {
            return null;
        }
        for (BillingProducts billingProducts : values()) {
            if (billingProducts.getProductId().equals(str)) {
                return billingProducts;
            }
        }
        return null;
    }

    public static BillingProducts valueOf(String str) {
        return (BillingProducts) Enum.valueOf(BillingProducts.class, str);
    }

    public static BillingProducts[] values() {
        return (BillingProducts[]) $VALUES.clone();
    }

    public static String vipName(BillingProducts billingProducts) {
        return billingProducts.ordinal() != 0 ? "UNKNOWN" : "PASS";
    }

    public String color() {
        return this.color;
    }

    public BillingProducts color(String str) {
        this.color = str;
        return this;
    }

    public double getPrice() {
        return this.price;
    }

    public String getPriceCurrenySymbol() {
        return this.priceCurrenySymbol;
    }

    public String getPriceText() {
        return "";
    }

    public String getProductId() {
        return this.productId;
    }

    public String getProductName() {
        return this.productName;
    }

    public Billing.BillingProductType getProductType() {
        return this.productType;
    }

    public String getPurchaseToken() {
        return this.purchaseToken;
    }

    public boolean isOwned() {
        return this.isOwned;
    }

    public void openBuyScreen(String str) {
        try {
            if (this.productDetails == null) {
                Log.e("BillingProducts", "ProductDetails is null, cannot open buy screen for " + this.productId);
                return;
            }
            if (str != null && !str.isEmpty()) {
                Billing billing = Billing.INSTANCE;
                if (billing == null) {
                    Log.e("BillingProducts", "Billing.INSTANCE is null");
                    return;
                }
                MainActivity activity = MainActivity.getActivity();
                if (activity == null) {
                    Log.e("BillingProducts", "MainActivity is null");
                    return;
                }
                BillingFlowParams.ProductDetailsParams.Builder newBuilder = BillingFlowParams.ProductDetailsParams.newBuilder();
                newBuilder.setProductDetails(this.productDetails);
                if (getProductType() == Billing.BillingProductType.SUBS) {
                    String str2 = this.offerToken;
                    if (str2 == null) {
                        Log.e("BillingProducts", "Offer token is null for SUBS product " + this.productId);
                        return;
                    }
                    newBuilder.setOfferToken(str2);
                }
                billing.launchBillingFlow(activity, BillingFlowParams.newBuilder().setObfuscatedAccountId(str).setProductDetailsParamsList(ImmutableList.of(newBuilder.build())).build());
                Log.d("BillingProducts", "Buy screen opened for " + this.productId);
                return;
            }
            Log.e("BillingProducts", "User UUID is null or empty");
        } catch (Throwable th) {
            Log.e("BillingProducts", "Error opening buy screen for " + this.productId + ": " + th.getMessage());
            th.printStackTrace();
        }
    }

    public double priceAsDouble() {
        return this.price;
    }

    public String priceCurrencyCode() {
        return this.priceCurrencyCode;
    }

    public void setOwned(boolean z) {
        this.isOwned = z;
    }

    public void setPurchaseToken(String str) {
        this.purchaseToken = str;
    }

    public void setup() {
        Log.e("BillingProducts", "This method can not be called! This OS not supported!");
        throw new UnsupportedOperationException("This OS is not supported for billing setup");
    }

    /* JADX WARN: Removed duplicated region for block: B:18:0x01b7 A[Catch: Exception -> 0x01f0, TryCatch #0 {Exception -> 0x01f0, blocks: (B:4:0x000c, B:7:0x0012, B:11:0x0197, B:13:0x019b, B:15:0x01a3, B:16:0x01a7, B:18:0x01b7, B:19:0x01bf, B:9:0x0142, B:28:0x0178, B:74:0x011d, B:31:0x0020, B:33:0x0028, B:36:0x0030, B:38:0x0038, B:40:0x0040, B:42:0x0046, B:47:0x0059, B:49:0x0084, B:51:0x008a, B:53:0x00a3, B:55:0x00ad, B:58:0x00b4, B:60:0x00bc, B:62:0x00d5, B:63:0x00f0, B:66:0x0053, B:71:0x0109, B:22:0x014a, B:24:0x0150, B:26:0x0163), top: B:2:0x000a, inners: #1, #2 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void setup(com.android.billingclient.api.ProductDetails r10) {
        /*
            Method dump skipped, instructions count: 530
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: net.blocklegends.billing.BillingProducts.setup(com.android.billingclient.api.ProductDetails):void");
    }
}
