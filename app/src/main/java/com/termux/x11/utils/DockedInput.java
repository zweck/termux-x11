package com.termux.x11.utils;

import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * r8: the rules that decide which input belongs to the X desktop while the X
 * activity is docked on a monitor.
 *
 * The problem they solve: Android keeps keyboard focus and its mouse pointer on the
 * built-in display. With the X activity on a monitor (HyperOS, no desktop mode)
 * the monitor never gets focus, the pointer cannot leave the tablet screen, and
 * the KeyInterceptor only filtered keys while MainActivity had window focus, so a
 * docked keyboard and trackpad drove the tablet's app instead of the desktop.
 *
 * While docked, every physical keyboard and pointing device belongs to the desktop,
 * independent of Android focus; the touchscreen stays Android's. Undocked nothing
 * changes. Every rule here is pure (constants and arguments only), so the host
 * app's JVM tests cover them; KeyInterceptor feeds them the live values.
 */
public final class DockedInput {
    private DockedInput() {
    }

    /**
     * The sources the accessibility service asks for while docked. The platform hands
     * every MotionEvent from these sources to the service INSTEAD of the rest of the
     * system (AccessibilityService#onMotionEvent, API 34), so the set must stay
     * exactly the pointing devices: touchscreen and stylus are other sources and are
     * never taken. A touchpad reports its pointer events as SOURCE_MOUSE; the
     * touchpad source itself only appears under pointer capture.
     */
    public static final int POINTER_SOURCES = InputDevice.SOURCE_MOUSE | InputDevice.SOURCE_TOUCHPAD;

    /**
     * Whether keyboards and pointers go to the desktop now. Every condition must hold;
     * anything the caller cannot establish counts as false, so the rule fails open and
     * Android keeps its keyboard and mouse.
     *
     * @param activityAlive   the X activity exists and is neither finishing nor destroyed
     * @param activityStarted the X activity is visible (between onStart and onStop)
     * @param displayId       the display the X view is on, or Display.INVALID_DISPLAY
     * @param xConnected      the activity is connected to the X server
     * @param keyguardLocked  the keyguard is up: the PIN must stay typeable
     * @param dex             Samsung DeX: its pointer and focus reach the monitor already
     */
    public static boolean captureWanted(boolean activityAlive, boolean activityStarted, int displayId,
                                        boolean xConnected, boolean keyguardLocked, boolean dex) {
        return activityAlive && activityStarted
                && displayId != Display.DEFAULT_DISPLAY && displayId != Display.INVALID_DISPLAY
                && xConnected && !keyguardLocked && !dex;
    }

    /**
     * A keyboard whose keys type on the desktop while docked: a real (not virtual)
     * full alphabetic keyboard. That includes the tablet's own keyboard cover, which
     * Android reports as an internal alphabetic keyboard: docked, it types on the
     * monitor like any other. Button-only devices (power, volume, the hall sensor,
     * headset keys) are not alphabetic and stay Android's.
     */
    public static boolean isDesktopKeyboard(boolean virtual, int sources, int keyboardType) {
        return !virtual
                && hasSource(sources, InputDevice.SOURCE_KEYBOARD)
                && keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }

    /** A real mouse or touchpad (including the keyboard cover's touchpad). */
    public static boolean isDesktopPointer(boolean virtual, int sources) {
        return !virtual
                && (hasSource(sources, InputDevice.SOURCE_MOUSE) || hasSource(sources, InputDevice.SOURCE_TOUCHPAD));
    }

    /**
     * Whether a key event from this device goes to the desktop while docked: a
     * keyboard's keys, and the keys a mouse synthesizes for its side buttons (Android
     * would otherwise run Back on the tablet). System keys stay Android's.
     */
    public static boolean isDesktopKey(boolean virtual, int sources, int keyboardType, int keyCode) {
        return (isDesktopKeyboard(virtual, sources, keyboardType) || isDesktopPointer(virtual, sources))
                && !staysWithAndroid(keyCode);
    }

    /**
     * Keys Android keeps even from a docked keyboard: power and sleep, volume, media,
     * brightness, camera and call keys. The guest has no use for them (no sound
     * server or backlight of its own) and the tablet still owns those functions.
     */
    public static boolean staysWithAndroid(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_SOFT_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_BRIGHTNESS_UP:
            case KeyEvent.KEYCODE_BRIGHTNESS_DOWN:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_CAMERA:
            case KeyEvent.KEYCODE_CALL:
            case KeyEvent.KEYCODE_ENDCALL:
                return true;
            default:
                return false;
        }
    }

    /**
     * Where a docked desktop key goes. A press goes to X. A release follows its
     * press: X when X got the press, Android otherwise (a key held across the dock
     * moment), so neither side is left with a key it never sees released.
     *
     * @return true for X, false for Android
     */
    public static boolean dockedKeyToX(boolean down, boolean pressedInX) {
        return down || pressedInX;
    }

    private static boolean hasSource(int sources, int source) {
        return (sources & source) == source;
    }
}
