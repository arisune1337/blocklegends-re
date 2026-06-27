package net.blocklegends.game;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.messaging.Constants;
import net.blocklegends.R;

/* loaded from: classes4.dex */
public class AppleSignActivity extends AppCompatActivity {
    private static final String CALLBACK_HOST = "apple-callback";
    private static final String CALLBACK_HTTPS_HOST = "blocklegends.net";
    private static final String CALLBACK_HTTPS_PATH = "/apple-callback";
    private static final String CALLBACK_HTTPS_SCHEME = "https";
    private static final String CALLBACK_SCHEME = "blocklegends";
    public static final String EXTRA_AUTH_URL = "auth_url";
    public static final String EXTRA_REDIRECT_URI = "redirect_uri";
    private static final String TAG = "AppleSignActivity";
    private String authUrl;
    private boolean callbackHandled;
    private ProgressBar progressBar;
    private WebView webView;

    private void finishWithError(String str) {
        Log.e(TAG, "Finishing with error: " + str);
        Intent intent = new Intent();
        intent.putExtra(Constants.IPC_BUNDLE_KEY_SEND_ERROR, str);
        setResult(0, intent);
        finish();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleCallback(Uri uri) {
        if (this.callbackHandled) {
            return;
        }
        this.callbackHandled = true;
        Log.i(TAG, "Callback received");
        Intent intent = new Intent();
        intent.setData(uri);
        setResult(-1, intent);
        finish();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean handleUrl(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        Log.d(TAG, "Handling URL: " + scheme + "://" + host);
        if (isCallbackUrl(uri)) {
            handleCallback(uri);
            return true;
        }
        if (!CALLBACK_HTTPS_HOST.equals(host) && (host == null || !host.endsWith(".blocklegends.net"))) {
            "appleid.apple.com".equals(host);
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void initWebView() {
        FrameLayout frameLayout = (FrameLayout) findViewById(R.id.apple_webview_container);
        try {
            WebView webView = new WebView(this);
            this.webView = webView;
            frameLayout.addView(webView, new FrameLayout.LayoutParams(-1, -1));
            WebSettings settings = this.webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setCacheMode(2);
            settings.setUserAgentString(settings.getUserAgentString() + " AppleSignIn/1.0");
            this.webView.setWebViewClient(new WebViewClient() { // from class: net.blocklegends.game.AppleSignActivity.1
                @Override // android.webkit.WebViewClient
                public void onPageFinished(WebView webView2, String str) {
                    super.onPageFinished(webView2, str);
                    AppleSignActivity.this.progressBar.setVisibility(8);
                }

                @Override // android.webkit.WebViewClient
                public void onPageStarted(WebView webView2, String str, Bitmap bitmap) {
                    super.onPageStarted(webView2, str, bitmap);
                    AppleSignActivity.this.progressBar.setVisibility(0);
                    Uri parse = Uri.parse(str);
                    if (AppleSignActivity.this.isCallbackUrl(parse)) {
                        AppleSignActivity.this.handleCallback(parse);
                    }
                }

                @Override // android.webkit.WebViewClient
                public boolean shouldOverrideUrlLoading(WebView webView2, WebResourceRequest webResourceRequest) {
                    return AppleSignActivity.this.handleUrl(webResourceRequest.getUrl());
                }

                @Override // android.webkit.WebViewClient
                public boolean shouldOverrideUrlLoading(WebView webView2, String str) {
                    return AppleSignActivity.this.handleUrl(Uri.parse(str));
                }
            });
            this.webView.loadUrl(this.authUrl);
        } catch (RuntimeException e) {
            Log.e(TAG, "WebView creation failed (AwDataDirLock): " + e.getMessage());
            finishWithError("WebView unavailable");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isCallbackUrl(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (CALLBACK_SCHEME.equals(scheme) && CALLBACK_HOST.equals(host)) {
            return true;
        }
        return "https".equals(scheme) && CALLBACK_HTTPS_HOST.equals(host) && CALLBACK_HTTPS_PATH.equals(uri.getPath());
    }

    @Override // androidx.activity.ComponentActivity, android.app.Activity
    public void onBackPressed() {
        WebView webView = this.webView;
        if (webView == null || !webView.canGoBack()) {
            finishWithError("User cancelled");
        } else {
            this.webView.goBack();
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, androidx.core.app.ComponentActivity, android.app.Activity
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        requestWindowFeature(1);
        getWindow().setFlags(1024, 1024);
        setContentView(R.layout.activity_apple_sign);
        ProgressBar progressBar = (ProgressBar) findViewById(R.id.apple_progress);
        this.progressBar = progressBar;
        progressBar.setVisibility(0);
        String stringExtra = getIntent().getStringExtra(EXTRA_AUTH_URL);
        this.authUrl = stringExtra;
        if (stringExtra == null || stringExtra.isEmpty()) {
            Log.e(TAG, "No auth URL provided");
            finishWithError("No auth URL");
        } else {
            Log.i(TAG, "Loading auth URL");
            findViewById(R.id.apple_webview_container).post(new Runnable() { // from class: net.blocklegends.game.AppleSignActivity$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    AppleSignActivity.this.initWebView();
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // androidx.appcompat.app.AppCompatActivity, androidx.fragment.app.FragmentActivity, android.app.Activity
    public void onDestroy() {
        super.onDestroy();
        WebView webView = this.webView;
        if (webView != null) {
            webView.destroy();
        }
    }
}
