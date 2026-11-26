package com.termux.x11.utils;

import android.view.MotionEvent;

/**
 * r8: turns the MotionEvents of a docked mouse or touchpad into X pointer input.
 *
 * Docked, Android's pointer is stuck on the built-in display, so absolute positions
 * mean nothing on the monitor: only the relative motion, the buttons and the scroll
 * amounts are used, and X moves its own pointer (Hyprland draws it on the monitor).
 * Pure state: KeyInterceptor reads the axes out of each MotionEvent and passes them
 * here with a Sink that writes to the X server, and the host app's JVM tests drive
 * it with a recording Sink.
 *
 * What one Android pointer frame looks like (AOSP 16, CursorInputMapper::sync and
 * GestureConverter): a mouse frame emits BUTTON_RELEASE, then the main event
 * (DOWN / UP / MOVE / HOVER_MOVE), then BUTTON_PRESS, a HOVER_MOVE after an UP and
 * a SCROLL, and every one of them carries the frame's AXIS_RELATIVE_X/Y. Motion is
 * therefore taken from DOWN, MOVE and HOVER_MOVE only, or a click with movement
 * would move X twice. Buttons are taken from the button state of every event, as
 * a difference against what X already has. A touchpad's two-finger scroll is a
 * fake finger classified TWO_FINGER_SWIPE: its DOWN/MOVE/UP are not clicks, and
 * only the AXIS_GESTURE_SCROLL_X/Y_DISTANCE of its MOVEs count.
 */
public final class DockedPointer {

    /** Where the pointer input goes: the X server, or a test recorder. */
    public interface Sink {
        /** Relative pointer motion in X pixels (before X's own acceleration). */
        void move(float dx, float dy);

        /** X button 1 (left), 2 (middle) or 3 (right). */
        void button(int xButton, boolean down);

        /** Scroll in X wheel units: 120 is one wheel click, positive is right / down. */
        void wheel(float dx, float dy);
    }

    /** One mouse-wheel click, in the X server's wheel units (lorie divides by 120). */
    public static final float WHEEL_CLICK = 120f;

    /**
     * Wheel units per pixel of touchpad scroll distance. 1 makes a scroll of 120 px
     * one wheel click, which is about three terminal lines.
     */
    public static final float TOUCHPAD_SCROLL_GAIN = 1f;

    public static final int X_LEFT = 1;
    public static final int X_MIDDLE = 2;
    public static final int X_RIGHT = 3;

    /**
     * MotionEvent.CLASSIFICATION_MULTI_FINGER_SWIPE (three/four-finger touchpad swipe).
     * The constant is @hide in AOSP 16 (MotionEvent.java), so it is not in the SDK jar.
     */
    public static final int CLASSIFICATION_MULTI_FINGER_SWIPE = 4;

    private static final int[][] BUTTONS = {
            {MotionEvent.BUTTON_PRIMARY, X_LEFT},
            {MotionEvent.BUTTON_TERTIARY, X_MIDDLE},
            {MotionEvent.BUTTON_SECONDARY, X_RIGHT},
    };

    /** The Android button bits whose X button is currently held. */
    private int held = 0;

    /**
     * Feeds one MotionEvent's values.
     *
     * @param action         getActionMasked()
     * @param buttonState    getButtonState()
     * @param classification getClassification() (0 below API 29)
     * @param relX           AXIS_RELATIVE_X, summed over the event's samples
     * @param relY           AXIS_RELATIVE_Y, summed over the event's samples
     * @param vscroll        AXIS_VSCROLL (positive = up, away from the user)
     * @param hscroll        AXIS_HSCROLL (positive = right)
     * @param gestureScrollX AXIS_GESTURE_SCROLL_X_DISTANCE
     * @param gestureScrollY AXIS_GESTURE_SCROLL_Y_DISTANCE
     */
    public void onEvent(int action, int buttonState, int classification,
                        float relX, float relY, float vscroll, float hscroll,
                        float gestureScrollX, float gestureScrollY, Sink sink) {
        if (classification == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE) {
            if (action == MotionEvent.ACTION_MOVE && (gestureScrollX != 0 || gestureScrollY != 0))
                sink.wheel(gestureScrollX * TOUCHPAD_SCROLL_GAIN, gestureScrollY * TOUCHPAD_SCROLL_GAIN);
            return;
        }
        if (classification == CLASSIFICATION_MULTI_FINGER_SWIPE
                || classification == MotionEvent.CLASSIFICATION_PINCH)
            return; // no X equivalent: three/four-finger swipes and pinches are dropped

        if (action == MotionEvent.ACTION_CANCEL) {
            releaseAll(sink);
            return;
        }

        if (action == MotionEvent.ACTION_SCROLL) {
            // Android: VSCROLL +1 is one click up; X: negative is up. HSCROLL +1 is right in both.
            if (vscroll != 0 || hscroll != 0)
                sink.wheel(hscroll * WHEEL_CLICK, -vscroll * WHEEL_CLICK);
            return;
        }

        if ((action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE
                || action == MotionEvent.ACTION_DOWN) && (relX != 0 || relY != 0))
            sink.move(relX, relY);

        for (int[] b : BUTTONS) {
            boolean down = (buttonState & b[0]) != 0;
            boolean wasDown = (held & b[0]) != 0;
            if (down != wasDown) {
                sink.button(b[1], down);
                held = down ? (held | b[0]) : (held & ~b[0]);
            }
        }
    }

    /** Releases every button X still holds (capture going off, a cancelled stream). */
    public void releaseAll(Sink sink) {
        for (int[] b : BUTTONS) {
            if ((held & b[0]) != 0)
                sink.button(b[1], false);
        }
        held = 0;
    }

    /** True while X holds at least one of this pointer's buttons. */
    public boolean anyButtonHeld() {
        return held != 0;
    }
}
