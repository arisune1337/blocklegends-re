package net.blocklegends.databinding;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import androidx.viewbinding.ViewBinding;
import androidx.viewbinding.ViewBindings;
import net.blocklegends.R;

/* loaded from: classes4.dex */
public final class ActivityMainBinding implements ViewBinding {
    public final FrameLayout activityMain;
    public final EditText editText;
    private final FrameLayout rootView;

    private ActivityMainBinding(FrameLayout frameLayout, FrameLayout frameLayout2, EditText editText) {
        this.rootView = frameLayout;
        this.activityMain = frameLayout2;
        this.editText = editText;
    }

    public static ActivityMainBinding bind(View view) {
        FrameLayout frameLayout = (FrameLayout) view;
        int r1 = R.id.editText;
        EditText editText = (EditText) ViewBindings.findChildViewById(view, r1);
        if (editText != null) {
            return new ActivityMainBinding(frameLayout, frameLayout, editText);
        }
        throw new NullPointerException("Missing required view with ID: ".concat(view.getResources().getResourceName(r1)));
    }

    public static ActivityMainBinding inflate(LayoutInflater layoutInflater) {
        return inflate(layoutInflater, null, false);
    }

    public static ActivityMainBinding inflate(LayoutInflater layoutInflater, ViewGroup viewGroup, boolean z) {
        View inflate = layoutInflater.inflate(R.layout.activity_main, viewGroup, false);
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
