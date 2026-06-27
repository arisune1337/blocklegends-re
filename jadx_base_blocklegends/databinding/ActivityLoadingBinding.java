package net.blocklegends.databinding;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import androidx.viewbinding.ViewBinding;
import androidx.viewbinding.ViewBindings;
import net.blocklegends.R;

/* loaded from: classes4.dex */
public final class ActivityLoadingBinding implements ViewBinding {
    public final ImageView loadingLogo;
    public final ProgressBar loadingProgress;
    public final LinearLayout progressContainer;
    private final FrameLayout rootView;

    private ActivityLoadingBinding(FrameLayout frameLayout, ImageView imageView, ProgressBar progressBar, LinearLayout linearLayout) {
        this.rootView = frameLayout;
        this.loadingLogo = imageView;
        this.loadingProgress = progressBar;
        this.progressContainer = linearLayout;
    }

    public static ActivityLoadingBinding bind(View view) {
        int r0 = R.id.loading_logo;
        ImageView imageView = (ImageView) ViewBindings.findChildViewById(view, r0);
        if (imageView != null) {
            r0 = R.id.loading_progress;
            ProgressBar progressBar = (ProgressBar) ViewBindings.findChildViewById(view, r0);
            if (progressBar != null) {
                r0 = R.id.progress_container;
                LinearLayout linearLayout = (LinearLayout) ViewBindings.findChildViewById(view, r0);
                if (linearLayout != null) {
                    return new ActivityLoadingBinding((FrameLayout) view, imageView, progressBar, linearLayout);
                }
            }
        }
        throw new NullPointerException("Missing required view with ID: ".concat(view.getResources().getResourceName(r0)));
    }

    public static ActivityLoadingBinding inflate(LayoutInflater layoutInflater) {
        return inflate(layoutInflater, null, false);
    }

    public static ActivityLoadingBinding inflate(LayoutInflater layoutInflater, ViewGroup viewGroup, boolean z) {
        View inflate = layoutInflater.inflate(R.layout.activity_loading, viewGroup, false);
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
