package com.termux.x11.utils;

import static android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;

import android.graphics.Rect;
import android.widget.FrameLayout;
import android.view.View;
import android.app.Activity;

import com.termux.x11.MainActivity;
import com.termux.x11.Prefs;

public class FullscreenWorkaround {
    // For more information, see https://issuetracker.google.com/issues/36911528
    // To use this class, simply invoke assistActivity() on an Activity that already has its content view set.

    public static void assistActivity(Activity activity) {
        new FullscreenWorkaround(activity);
    }

    private final Activity mActivity;
    private int usableHeightPrevious;

    private FullscreenWorkaround(Activity activity) {
        mActivity = activity;
        FrameLayout content = activity.findViewById(android.R.id.content);
        content.getViewTreeObserver().addOnGlobalLayoutListener(this::possiblyResizeChildOfContent);
    }

    private void possiblyResizeChildOfContent() {
        Prefs p = MainActivity.getPrefs();
        if (
                !resizesForKeyboard() ||
                !mActivity.hasWindowFocus() ||
                !((mActivity.getWindow().getAttributes().flags & FLAG_FULLSCREEN) == FLAG_FULLSCREEN) ||
                !p.Reseed.get() || !p.fullscreen.get() || SamsungDexUtils.checkDeXEnabled(mActivity)
        )
            return;

        FrameLayout content = (FrameLayout)  ((FrameLayout) mActivity.findViewById(android.R.id.content)).getChildAt(0);
        FrameLayout.LayoutParams frameLayoutParams = (FrameLayout.LayoutParams) content.getLayoutParams();

        int usableHeightNow = computeUsableHeight(content);
        if (usableHeightNow != usableHeightPrevious) {
            int usableHeightSansKeyboard = content.getRootView().getHeight();
            int heightDifference = usableHeightSansKeyboard - usableHeightNow;
            if (heightDifference > (usableHeightSansKeyboard/4)) {
                // keyboard probably just became visible
                frameLayoutParams.height = usableHeightSansKeyboard - heightDifference;
            } else {
                // keyboard probably just became hidden
                frameLayoutParams.height = usableHeightSansKeyboard;
            }
            content.requestLayout();
            usableHeightPrevious = usableHeightNow;
        }
    }

    /**
     * phoshdroid: only a window that asks to be resized for the keyboard may be resized here.
     * MainActivity always sets ADJUST_NOTHING so the keyboard overlays the surface and the X
     * root stays put; resizing the content view under that mode would reflow the desktop.
     */
    private boolean resizesForKeyboard() {
        int adjust = mActivity.getWindow().getAttributes().softInputMode & SOFT_INPUT_MASK_ADJUST;
        return adjust == SOFT_INPUT_ADJUST_RESIZE;
    }

    private int computeUsableHeight(View v) {
        Rect r = new Rect();
        v.getWindowVisibleDisplayFrame(r);
        return (r.bottom - r.top);
    }
}