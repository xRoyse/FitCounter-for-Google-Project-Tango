package com.example.fitcounter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.widget.Button;

public final class CameraSwitchButton extends Button {
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path symbol = new Path();

    public CameraSwitchButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(1.7f);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        symbol.moveTo(3, 9);
        symbol.lineTo(3, 6);
        symbol.lineTo(7, 6);
        symbol.lineTo(9, 4);
        symbol.lineTo(15, 4);
        symbol.lineTo(17, 6);
        symbol.lineTo(21, 6);
        symbol.lineTo(21, 9);
        symbol.moveTo(21, 15);
        symbol.lineTo(21, 19);
        symbol.lineTo(3, 19);
        symbol.lineTo(3, 15);
        symbol.moveTo(8, 12);
        symbol.cubicTo(9, 8, 14, 8, 16, 12);
        symbol.lineTo(13, 12);
        symbol.moveTo(16, 12);
        symbol.lineTo(16, 9);
        symbol.moveTo(16, 14);
        symbol.cubicTo(15, 17, 10, 17, 8, 14);
        symbol.lineTo(11, 14);
        symbol.moveTo(8, 14);
        symbol.lineTo(8, 17);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight()) * 0.54f;
        int save = canvas.save();
        canvas.translate((getWidth() - size) / 2, (getHeight() - size) / 2);
        canvas.scale(size / 24, size / 24);
        ink.setColor(isEnabled() ? 0xfffbf8ee : 0xffa3b3aa);
        canvas.drawPath(symbol, ink);
        canvas.restoreToCount(save);
    }
}
