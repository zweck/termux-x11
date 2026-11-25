package com.termux.x11.utils;

import android.app.Activity;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import java.lang.reflect.Method;

/**
 * r7 a5: Android 16.1's keyboard capture, requested by reflection.
 *
 * {@code WindowManager.LayoutParams#setKeyboardCaptureEnabled(boolean)} exists
 * from Android 16.1 (API 36.1), is gated by
 * {@code android.permission.CAPTURE_KEYBOARD} (protectionLevel normal, granted
 * from the host app's manifest line) and makes the focused window receive keys
 * before system processes handle their own shortcuts — which is what Meta/Super
 * needs on a docked monitor. Neither the method nor the permission constant is in
 * the compileSdk 35 (or 36) android.jar, so this class looks the method up at
 * runtime: an old platform answers NoSuchMethodException and nothing changes,
 * and every other failure (permission missing, method throwing) is logged and
 * ignored so the accessibility KeyInterceptor stays the fallback path.
 */
public final class KeyboardCapture {
    private static final String TAG = "KeyboardCapture";
    private static final String METHOD = "setKeyboardCaptureEnabled";

    /** null = not tried yet, FALSE = this platform has no such method. */
    private static Boolean supported = null;
    private static Boolean applied = null;

    private KeyboardCapture() {
    }

    /** True once the platform has answered that the method exists. */
    public static boolean supported() {
        return Boolean.TRUE.equals(supported);
    }

    /**
     * Asks the window for keyboard capture when [enabled], or gives it back when
     * not. Safe to call on every focus change: the attributes update is idempotent
     * and the log only fires when the state changes.
     */
    public static void apply(Activity activity, boolean enabled) {
        if (activity == null) {
            return;
        }
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        WindowManager.LayoutParams params = window.getAttributes();
        try {
            Method method = params.getClass().getMethod(METHOD, boolean.class);
            method.invoke(params, enabled);
            window.setAttributes(params);
            supported = Boolean.TRUE;
            if (applied == null || applied != enabled) {
                applied = enabled;
                Log.i(TAG, "keyboard capture " + (enabled ? "on" : "off"));
            }
        } catch (NoSuchMethodException e) {
            if (supported == null) {
                supported = Boolean.FALSE;
                Log.i(TAG, "keyboard capture API absent (needs Android 16.1+);"
                        + " the accessibility KeyInterceptor is the key path");
            }
        } catch (Throwable t) {
            // SecurityException when the permission was not granted, plus any
            // reflection failure. None of them is fatal.
            Log.w(TAG, "keyboard capture " + (enabled ? "on" : "off") + " failed: " + t);
        }
    }
}
