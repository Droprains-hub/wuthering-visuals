package com.wuwa.config.manager.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

/**
 * Keeps short dialog content compact, then becomes scrollable after a runtime
 * maximum height is reached.
 */
public final class AdaptiveNestedScrollView extends NestedScrollView {
    private int maxHeightPx = Integer.MAX_VALUE;

    public AdaptiveNestedScrollView(@NonNull Context context) {
        super(context);
    }

    public AdaptiveNestedScrollView(
            @NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public AdaptiveNestedScrollView(
            @NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setMaxHeightPx(int maxHeightPx) {
        int safeHeight = Math.max(1, maxHeightPx);
        if (this.maxHeightPx == safeHeight) return;
        this.maxHeightPx = safeHeight;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (maxHeightPx == Integer.MAX_VALUE) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        int incomingMode = MeasureSpec.getMode(heightMeasureSpec);
        int incomingSize = MeasureSpec.getSize(heightMeasureSpec);
        int cappedSize = incomingMode == MeasureSpec.UNSPECIFIED
                ? maxHeightPx
                : Math.min(incomingSize, maxHeightPx);
        super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(cappedSize, MeasureSpec.AT_MOST));
    }
}
