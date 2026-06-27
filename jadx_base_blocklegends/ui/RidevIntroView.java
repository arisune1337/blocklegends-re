package net.blocklegends.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;
import androidx.core.view.ViewCompat;

/* loaded from: classes3.dex */
public class RidevIntroView extends View {
    private static final long INTRO_DURATION = 1800;
    private static final String TAG = "RidevIntroView";
    private ValueAnimator animator;
    private volatile boolean finished;
    private OnIntroCompleteListener listener;
    private final Bitmap logoBitmap;
    private final Paint logoPaint;
    private float progress;

    /* loaded from: classes3.dex */
    public interface OnIntroCompleteListener {
        void onIntroComplete();
    }

    public RidevIntroView(Context context, int r4, final OnIntroCompleteListener onIntroCompleteListener) {
        super(context);
        this.progress = 0.0f;
        this.finished = false;
        this.listener = onIntroCompleteListener;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        this.logoBitmap = BitmapFactory.decodeResource(context.getResources(), r4, options);
        this.logoPaint = new Paint(3);
        setBackgroundColor(ViewCompat.MEASURED_STATE_MASK);
        ValueAnimator ofFloat = ValueAnimator.ofFloat(0.0f, 1.0f);
        this.animator = ofFloat;
        ofFloat.setDuration(INTRO_DURATION);
        this.animator.setInterpolator(new LinearInterpolator());
        this.animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { // from class: net.blocklegends.ui.RidevIntroView$$ExternalSyntheticLambda0
            @Override // android.animation.ValueAnimator.AnimatorUpdateListener
            public final void onAnimationUpdate(ValueAnimator valueAnimator) {
                RidevIntroView.this.m2144lambda$new$0$netblocklegendsuiRidevIntroView(valueAnimator);
            }
        });
        this.animator.addListener(new AnimatorListenerAdapter() { // from class: net.blocklegends.ui.RidevIntroView.1
            @Override // android.animation.AnimatorListenerAdapter, android.animation.Animator.AnimatorListener
            public void onAnimationEnd(Animator animator) {
                RidevIntroView.this.finished = true;
                OnIntroCompleteListener onIntroCompleteListener2 = onIntroCompleteListener;
                if (onIntroCompleteListener2 != null) {
                    onIntroCompleteListener2.onIntroComplete();
                }
            }
        });
        this.animator.start();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$new$0$net-blocklegends-ui-RidevIntroView, reason: not valid java name */
    public /* synthetic */ void m2144lambda$new$0$netblocklegendsuiRidevIntroView(ValueAnimator valueAnimator) {
        this.progress = ((Float) valueAnimator.getAnimatedValue()).floatValue();
        invalidate();
    }

    @Override // android.view.View
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        ValueAnimator valueAnimator = this.animator;
        if (valueAnimator != null) {
            valueAnimator.cancel();
            this.animator = null;
        }
        Bitmap bitmap = this.logoBitmap;
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        this.logoBitmap.recycle();
    }

    @Override // android.view.View
    protected void onDraw(Canvas canvas) {
        float f;
        int width;
        Bitmap bitmap = this.logoBitmap;
        if (bitmap == null || bitmap.isRecycled() || this.finished) {
            canvas.drawColor(ViewCompat.MEASURED_STATE_MASK);
            return;
        }
        canvas.drawColor(ViewCompat.MEASURED_STATE_MASK);
        float f2 = this.progress;
        float f3 = 0.4f;
        if (f2 >= 0.4f) {
            if (f2 <= 0.7f) {
                f = 1.0f;
                float max = Math.max(0.0f, Math.min(1.0f, f));
                width = getWidth();
                int height = getHeight();
                if (width > 0 || height <= 0) {
                }
                float min = Math.min(width, height) * 0.45f;
                float f4 = (width - min) / 2.0f;
                float f5 = (height - min) / 2.0f;
                this.logoPaint.setAlpha((int) (max * 255.0f));
                canvas.drawBitmap(this.logoBitmap, (Rect) null, new RectF(f4, f5, f4 + min, min + f5), this.logoPaint);
                return;
            }
            f2 = 1.0f - f2;
            f3 = 0.3f;
        }
        f = f2 / f3;
        float max2 = Math.max(0.0f, Math.min(1.0f, f));
        width = getWidth();
        int height2 = getHeight();
        if (width > 0) {
        }
    }

    public void onPause() {
        ValueAnimator valueAnimator = this.animator;
        if (valueAnimator == null || !valueAnimator.isRunning()) {
            return;
        }
        this.animator.pause();
    }

    public void onResume() {
        ValueAnimator valueAnimator = this.animator;
        if (valueAnimator == null || !valueAnimator.isPaused()) {
            return;
        }
        this.animator.resume();
    }

    public void skip() {
        ValueAnimator valueAnimator;
        if (this.finished || (valueAnimator = this.animator) == null) {
            return;
        }
        valueAnimator.cancel();
        this.finished = true;
        OnIntroCompleteListener onIntroCompleteListener = this.listener;
        if (onIntroCompleteListener != null) {
            onIntroCompleteListener.onIntroComplete();
        }
    }
}
