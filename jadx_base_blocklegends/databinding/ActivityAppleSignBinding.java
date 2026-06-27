package net.blocklegends.databinding;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import androidx.viewbinding.ViewBinding;
import androidx.viewbinding.ViewBindings;
import net.blocklegends.R;

/* loaded from: classes4.dex */
public final class ActivityAppleSignBinding implements ViewBinding {
    public final ProgressBar appleProgress;
    public final FrameLayout appleWebviewContainer;
    private final FrameLayout rootView;

    private ActivityAppleSignBinding(FrameLayout frameLayout, ProgressBar progressBar, FrameLayout frameLayout2) {
        this.rootView = frameLayout;
        this.appleProgress = progressBar;
        this.appleWebviewContainer = frameLayout2;
    }

    public static ActivityAppleSignBinding bind(View view) {
        int r0 = R.id.apple_progress;
        ProgressBar progressBar = (ProgressBar) ViewBindings.findChildViewById(view, r0);
        if (progressBar != null) {
            r0 = R.id.apple_webview_container;
            FrameLayout frameLayout = (FrameLayout) ViewBindings.findChildViewById(view, r0);
            if (frameLayout != null) {
                return new ActivityAppleSignBinding((FrameLayout) view, progressBar, frameLayout);
            }
        }
        throw new NullPointerException("Missing required view with ID: ".concat(view.getResources().getResourceName(r0)));
    }

    public static ActivityAppleSignBinding inflate(LayoutInflater layoutInflater) {
        return inflate(layoutInflater, null, false);
    }

    public static ActivityAppleSignBinding inflate(LayoutInflater layoutInflater, ViewGroup viewGroup, boolean z) {
        View inflate = layoutInflater.inflate(R.layout.activity_apple_sign, viewGroup, false);
        if (z) {
            viewGroup.addView(inflate);
        }
        return bind(inflate);
    }

    @Override // androidx.viewbinding.ViewBinding
    public FrameLayout getRoot() {
        return this.rootView;
    }
}
