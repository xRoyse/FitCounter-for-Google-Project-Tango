package com.example.fitcounter;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

public final class PreviewPane extends FrameLayout {
    private int imageWidth = 4, imageHeight = 3;

    public PreviewPane(Context context, AttributeSet attrs) { super(context, attrs); }

    void setFrameSize(int width, int height) {
        if (width == imageWidth && height == imageHeight) return;
        imageWidth = width;
        imageHeight = height;
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        float scale = Math.min(width / (float) imageWidth, height / (float) imageHeight);
        int childWidth = MeasureSpec.makeMeasureSpec(Math.round(imageWidth * scale), MeasureSpec.EXACTLY);
        int childHeight = MeasureSpec.makeMeasureSpec(Math.round(imageHeight * scale), MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(childWidth, childHeight);
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        for (int i = 0; i < getChildCount(); i++) {
            android.view.View child = getChildAt(i);
            int x = (getWidth() - child.getMeasuredWidth()) / 2;
            int y = (getHeight() - child.getMeasuredHeight()) / 2;
            child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
        }
    }
}
