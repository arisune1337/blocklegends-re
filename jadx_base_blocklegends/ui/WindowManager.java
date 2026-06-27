package net.blocklegends.ui;

import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import net.blocklegends.MainActivity;

/* loaded from: classes.dex */
public class WindowManager {
    public MainActivity activity;

    public WindowManager(MainActivity mainActivity) {
        this.activity = mainActivity;
        init();
    }

    public static void hideNavigation(Window window) {
        if (Build.VERSION.SDK_INT < 30) {
            window.getDecorView().setSystemUiVisibility(5894);
            return;
        }
        WindowInsetsController insetsController = window.getInsetsController();
        if (insetsController != null) {
            insetsController.hide(WindowInsets.Type.navigationBars() | WindowInsets.Type.statusBars());
            insetsController.setSystemBarsBehavior(2);
        }
    }

    public Window getWindow() {
        return this.activity.getWindow();
    }

    public void init() {
        try {
            if (!MainActivity.isExternalUiInProgress()) {
                this.activity.forceLandscapeOrientation("WindowManager.init");
            }
        } catch (Throwable unused) {
        }
        try {
            this.activity.requestWindowFeature(1);
        } catch (Throwable unused2) {
        }
        getWindow().setFlags(1024, 1024);
        getWindow().addFlags(128);
        if (Build.VERSION.SDK_INT >= 30) {
            hideNavigation(getWindow());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(5894);
            getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() { // from class: net.blocklegends.ui.WindowManager$$ExternalSyntheticLambda0
                @Override // android.view.View.OnSystemUiVisibilityChangeListener
                public final void onSystemUiVisibilityChange(int r1) {
                    WindowManager.this.m2145lambda$init$0$netblocklegendsuiWindowManager(r1);
                }
            });
        }
        View decorView = getWindow().getDecorView();
        decorView.setFocusable(true);
        decorView.setFocusableInTouchMode(true);
        decorView.setFocusedByDefault(true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$init$0$net-blocklegends-ui-WindowManager, reason: not valid java name */
    public /* synthetic */ void m2145lambda$init$0$netblocklegendsuiWindowManager(int r1) {
        if ((r1 & 4) == 0) {
            getWindow().getDecorView().setSystemUiVisibility(5894);
        }
    }

    public void onWindowFocusChanged(boolean z) {
        if (z) {
            hideNavigation(getWindow());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
    }
}
