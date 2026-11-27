package org.shidulab.apparecchio;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.HorizontalScrollView;

/** Allows sideways swipes even when they start over a vertical control. */
final class BandStrip extends HorizontalScrollView {
    private final int touchSlop;
    private float downX, downY, lastX;
    private boolean horizontal, vertical;

    BandStrip(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = lastX = event.getX();
            downY = event.getY();
            horizontal = vertical = false;
        } else if (action == MotionEvent.ACTION_MOVE) {
            float x = event.getX();
            float dx = Math.abs(x - downX);
            float dy = Math.abs(event.getY() - downY);
            if (!horizontal && !vertical && Math.max(dx, dy) > touchSlop) {
                vertical = dy >= dx;
                if (!vertical && (canScrollHorizontally(-1) || canScrollHorizontally(1))) {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    horizontal = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
            }
            if (horizontal) {
                scrollBy(Math.round(lastX - x), 0);
                lastX = x;
                return true;
            }
        } else if (horizontal && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            horizontal = false;
            getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        return super.dispatchTouchEvent(event);
    }
}
