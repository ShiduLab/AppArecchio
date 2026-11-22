package org.shidulab.apparecchio;

import android.content.Context;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.SeekBar;

/** Keeps the native adjustable control and rotates its layout and touch coordinates. */
final class VerticalBand extends FrameLayout {
    final SeekBar slider;

    VerticalBand(Context context) {
        super(context);
        setClipChildren(false);
        slider = new SeekBar(context) {
            @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
                return super.onKeyDown(switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP -> KeyEvent.KEYCODE_DPAD_RIGHT;
                    case KeyEvent.KEYCODE_DPAD_DOWN -> KeyEvent.KEYCODE_DPAD_LEFT;
                    default -> keyCode;
                }, event);
            }
        };
        slider.setLayoutDirection(LAYOUT_DIRECTION_LTR);
        slider.setRotation(-90);
        addView(slider);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        slider.measure(MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY));
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int height = bottom - top;
        int x = (width - height) / 2;
        int y = (height - width) / 2;
        slider.layout(x, y, x + height, y + width);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return super.dispatchTouchEvent(event);
    }
}
