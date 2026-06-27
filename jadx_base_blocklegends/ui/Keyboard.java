package net.blocklegends.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import net.blocklegends.MainActivity;
import net.blocklegends.natives.GNatives;
import net.blocklegends.utils.Util;

/* loaded from: classes.dex */
public class Keyboard {

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: net.blocklegends.ui.Keyboard$1, reason: invalid class name */
    /* loaded from: classes.dex */
    public class AnonymousClass1 implements ViewTreeObserver.OnGlobalLayoutListener {
        final /* synthetic */ MainActivity val$activity;
        final /* synthetic */ GameSurfaceView val$view;

        AnonymousClass1(GameSurfaceView gameSurfaceView, MainActivity mainActivity) {
            this.val$view = gameSurfaceView;
            this.val$activity = mainActivity;
        }

        @Override // android.view.ViewTreeObserver.OnGlobalLayoutListener
        public void onGlobalLayout() {
            Rect rect = new Rect();
            this.val$view.getWindowVisibleDisplayFrame(rect);
            final int width = this.val$view.getRootView().getWidth();
            final int height = this.val$view.getRootView().getHeight();
            final int r7 = height - rect.bottom;
            final int navigationBarHeight = Keyboard.getNavigationBarHeight(this.val$view.getContext());
            final int navigationBarHeight2 = Keyboard.getNavigationBarHeight(this.val$activity);
            Util.run(new Runnable() { // from class: net.blocklegends.ui.Keyboard$1$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    GNatives.onKeyboardListener(navigationBarHeight, navigationBarHeight2, width, height, r7);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static int getNavigationBarHeight(Context context) {
        Resources resources = context.getResources();
        int identifier = resources.getIdentifier("navigation_bar_height", "dimen", "android");
        if (identifier > 0) {
            return resources.getDimensionPixelSize(identifier);
        }
        return 0;
    }

    private static int getNavigationBarHeight(View view) {
        int identifier = view.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (identifier > 0) {
            return view.getResources().getDimensionPixelSize(identifier);
        }
        return 0;
    }

    public static void init() {
        final MainActivity activity = MainActivity.getActivity();
        final GameSurfaceView gameSurfaceView = activity.surfaceView;
        if (Build.VERSION.SDK_INT < 30) {
            activity.getWindow().setSoftInputMode(19);
            gameSurfaceView.getViewTreeObserver().addOnGlobalLayoutListener(new AnonymousClass1(gameSurfaceView, activity));
        } else {
            View decorView = activity.getWindow().getDecorView();
            ViewCompat.setOnApplyWindowInsetsListener(decorView, new OnApplyWindowInsetsListener() { // from class: net.blocklegends.ui.Keyboard$$ExternalSyntheticLambda0
                @Override // androidx.core.view.OnApplyWindowInsetsListener
                public final WindowInsetsCompat onApplyWindowInsets(View view, WindowInsetsCompat windowInsetsCompat) {
                    return Keyboard.lambda$init$1(GameSurfaceView.this, activity, view, windowInsetsCompat);
                }
            });
            ViewCompat.requestApplyInsets(decorView);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ WindowInsetsCompat lambda$init$1(GameSurfaceView gameSurfaceView, MainActivity mainActivity, View view, WindowInsetsCompat windowInsetsCompat) {
        final int r6 = windowInsetsCompat.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        final int width = gameSurfaceView.getRootView().getWidth();
        final int height = gameSurfaceView.getRootView().getHeight();
        if (width > 0 && height > 0) {
            final int navigationBarHeight = getNavigationBarHeight(gameSurfaceView.getContext());
            final int navigationBarHeight2 = getNavigationBarHeight(mainActivity);
            Util.run(new Runnable() { // from class: net.blocklegends.ui.Keyboard$$ExternalSyntheticLambda1
                @Override // java.lang.Runnable
                public final void run() {
                    GNatives.onKeyboardListener(navigationBarHeight, navigationBarHeight2, width, height, r6);
                }
            });
        }
        return ViewCompat.onApplyWindowInsets(view, windowInsetsCompat);
    }
}
