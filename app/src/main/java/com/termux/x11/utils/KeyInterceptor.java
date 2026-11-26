package com.termux.x11.utils;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.termux.x11.LorieView;
import com.termux.x11.MainActivity;

import java.util.LinkedHashSet;

public class KeyInterceptor extends AccessibilityService {
    private static final String TAG = "KeyInterceptor";

    LinkedHashSet<Integer> pressedKeys = new LinkedHashSet<>();

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static KeyInterceptor self;
    private static boolean launchedAutomatically = false;
    private boolean enabled = false;

    /**
     * r8: the display the X activity is docked on while keyboards and pointers are
     * captured for the desktop, or Display.INVALID_DISPLAY while they are not.
     */
    private int dockedDisplay = Display.INVALID_DISPLAY;
    private final DockedPointer pointer = new DockedPointer();
    private long lastPointerRecheck = 0;
    private boolean watching = false;

    public KeyInterceptor() {
        self = this;
    }

    /**
     * The service's flattened component, built from the package this APK actually runs as.
     * The literal that used to live here ("com.termux.x11/.utils.KeyInterceptor") names a
     * component that does not exist in an APK with a different applicationId (Omdroid is
     * com.phoshdroid.app), so the write silently enabled nothing.
     */
    private static String component(@NonNull Context ctx) {
        return new ComponentName(ctx, KeyInterceptor.class).flattenToString();
    }

    /**
     * Adds {@code component} to a colon-separated accessibility-service list without
     * dropping or reordering anything else: the old code wrote the bare component over
     * the whole setting, which disabled every other accessibility service the user had on.
     */
    private static String withComponent(String current, String component) {
        if (current == null || current.isEmpty())
            return component;

        for (String entry : current.split(":"))
            if (entry.equals(component))
                return current;

        return current + ":" + component;
    }

    public static void launch(@NonNull Context ctx) {
        try {
            String component = component(ctx);
            String current = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            Settings.Secure.putString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, withComponent(current, component));
            Settings.Secure.putString(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, "1");
            launchedAutomatically = true;
        } catch (SecurityException e) {
            new AlertDialog.Builder(ctx)
                    .setTitle("Permission denied")
                    .setMessage("Android requires WRITE_SECURE_SETTINGS permission to start accessibility service automatically.\n" +
                            "Please, launch this command using ADB:\n" +
                            "adb shell pm grant " + ctx.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS")
                    .setNegativeButton("OK", null)
                    .create()
                    .show();

            MainActivity.prefs.enableAccessibilityServiceAutomatically.put(false);
        }
    }

    public static void shutdown(boolean onlyIfEnabledAutomatically) {
        if (onlyIfEnabledAutomatically && !launchedAutomatically)
            return;

        if (self != null) {
            self.disableSelf();
            self.pressedKeys.clear();
            self = null;
        }
    }

    public static boolean isLaunched() {
        AccessibilityServiceInfo info = self == null ? null : self.getServiceInfo();
        return info != null && info.getId() != null;
    }

    private static final Runnable disableImmediatelyCallback = KeyInterceptor::disableImmediately;
    private static void disableImmediately() {
        if (self == null)
            return;

        android.util.Log.d("KeyInterceptor", "disabling interception service");
        self.applyServiceInfo(false, self.dockedDisplay != Display.INVALID_DISPLAY);
        self.enabled = false;
    }

    /**
     * Key filtering and the docked pointer sources in one service info. The pointer
     * sources are only ever set while docked: the platform then hands every mouse and
     * touchpad event to onMotionEvent and to nothing else (API 34+; older platforms
     * keep the keys-only behaviour).
     */
    private void applyServiceInfo(boolean filterKeys, boolean pointers) {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.flags = filterKeys ? AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS : AccessibilityServiceInfo.DEFAULT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            info.setMotionEventSources(pointers ? DockedInput.POINTER_SOURCES : 0);
        setServiceInfo(info);
    }

    /**
     * r8: the display to capture keyboards and pointers for, or INVALID_DISPLAY.
     * Every unknown answers "not docked", so a failure here leaves Android its input.
     */
    private int dockedDisplayNow(MainActivity a) {
        if (a == null)
            return Display.INVALID_DISPLAY;
        try {
            LorieView view = a.getLorieView();
            Display display = view == null ? null : view.getDisplay();
            int displayId = display == null ? Display.INVALID_DISPLAY : display.getDisplayId();
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            boolean locked = km != null && km.isKeyguardLocked();
            boolean wanted = DockedInput.captureWanted(!a.isFinishing() && !a.isDestroyed(), a.isStartedForInput(),
                    displayId, MainActivity.isConnected(), locked, SamsungDexUtils.checkDeXEnabled(a));
            return wanted ? displayId : Display.INVALID_DISPLAY;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "input: docked check failed, leaving input to Android: " + t);
            return Display.INVALID_DISPLAY;
        }
    }

    /** "kbd=<n> pointer=<n>": the physical keyboards and pointing devices Android sees. */
    private static String deviceCounts() {
        int keyboards = 0, pointers = 0;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null)
                continue;
            if (DockedInput.isDesktopKeyboard(d.isVirtual(), d.getSources(), d.getKeyboardType()))
                keyboards++;
            if (DockedInput.isDesktopPointer(d.isVirtual(), d.getSources()))
                pointers++;
        }
        return "kbd=" + keyboards + " pointer=" + pointers;
    }

    public static void recheck() {
        KeyInterceptor s = self;
        if (s == null)
            return;
        MainActivity a = MainActivity.getInstance();

        // r8: docked or not, decided fresh. A change applies the pointer sources at
        // once in both directions: a mouse must never stay with the service after an undock.
        int docked = s.dockedDisplayNow(a);
        if (docked != s.dockedDisplay) {
            boolean wasDocked = s.dockedDisplay != Display.INVALID_DISPLAY;
            boolean isDocked = docked != Display.INVALID_DISPLAY;
            s.dockedDisplay = docked;
            if (!isDocked)
                s.pointer.releaseAll(xSink(a));
            if (isDocked) {
                handler.removeCallbacks(disableImmediatelyCallback);
                s.enabled = true;
            }
            s.applyServiceInfo(s.enabled, isDocked);
            if (isDocked)
                android.util.Log.i(TAG, "input: docked capture on display=" + docked + " " + deviceCounts());
            else if (wasDocked)
                android.util.Log.i(TAG, "input: capture off");
        }

        boolean shouldBeEnabled = a != null && (a.hasWindowFocus() || !s.pressedKeys.isEmpty()
                || s.dockedDisplay != Display.INVALID_DISPLAY);
        if (shouldBeEnabled != s.enabled) {
            if (shouldBeEnabled) {
                handler.removeCallbacks(disableImmediatelyCallback);
                android.util.Log.d("KeyInterceptor", "enabling interception service");
                s.applyServiceInfo(true, s.dockedDisplay != Display.INVALID_DISPLAY);
                s.enabled = true;
            } else
                // In the case if service info is changed Android current dragging processes
                // so it is impossible to pull notification bar or call recents screen by swiping activity up.
                handler.postDelayed(disableImmediatelyCallback, 120000);
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        watch();
        recheck();
    }

    @Override
    public void onDestroy() {
        unwatch();
        super.onDestroy();
    }

    /** r8: a monitor coming or going, the screen and the keyguard re-run the docked rule. */
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            recheck();
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            recheck();
        }

        @Override
        public void onDisplayChanged(int displayId) {
            recheck();
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            recheck();
        }
    };

    private void watch() {
        if (watching)
            return;
        try {
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null)
                dm.registerDisplayListener(displayListener, handler);
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(screenReceiver, filter);
            watching = true;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "input: cannot watch displays: " + t);
        }
    }

    private void unwatch() {
        if (!watching)
            return;
        watching = false;
        try {
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null)
                dm.unregisterDisplayListener(displayListener);
            unregisterReceiver(screenReceiver);
        } catch (Throwable ignored) {
        }
    }

    /** Writes docked pointer input to the X server through the activity's LorieView. */
    private static DockedPointer.Sink xSink(MainActivity a) {
        final LorieView view = a == null ? null : a.getLorieView();
        return new DockedPointer.Sink() {
            @Override
            public void move(float dx, float dy) {
                if (view != null)
                    view.sendMouseEvent(dx, dy, 0, false, true);
            }

            @Override
            public void button(int xButton, boolean down) {
                if (view != null)
                    view.sendMouseEvent(0, 0, xButton, down, true);
            }

            @Override
            public void wheel(float dx, float dy) {
                if (view != null)
                    view.sendMouseWheelEvent(dx, dy);
            }
        };
    }

    /**
     * r8: a docked mouse or touchpad. Only called while the docked pointer sources are
     * set; the platform has already taken the event from the rest of the system, so it
     * cannot be passed on. A rule that no longer holds drops the sources for the next one.
     */
    @Override
    public void onMotionEvent(@NonNull MotionEvent e) {
        long now = SystemClock.uptimeMillis();
        if (now - lastPointerRecheck > 500) {
            lastPointerRecheck = now;
            recheck();
        }
        MainActivity a = MainActivity.getInstance();
        if (dockedDisplay == Display.INVALID_DISPLAY || a == null)
            return;

        float relX = e.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
        float relY = e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
        for (int h = 0; h < e.getHistorySize(); h++) {
            relX += e.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, h);
            relY += e.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, h);
        }
        int classification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? e.getClassification() : 0;
        pointer.onEvent(e.getActionMasked(), e.getButtonState(), classification, relX, relY,
                e.getAxisValue(MotionEvent.AXIS_VSCROLL), e.getAxisValue(MotionEvent.AXIS_HSCROLL),
                e.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE),
                e.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE), xSink(a));
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        boolean ret = false;
        MainActivity instance = MainActivity.getInstance();

        if (instance == null)
            return false;

        // r8: while docked, a physical keyboard types on the desktop whatever Android has
        // focused. A press goes to X; a release follows its press, so a key held across
        // the dock moment is released where it was pressed.
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
            recheck();
        InputDevice device = event.getDevice();
        if (dockedDisplay != Display.INVALID_DISPLAY && device != null
                && DockedInput.isDesktopKey(device.isVirtual(), device.getSources(), device.getKeyboardType(), event.getKeyCode())) {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            if (DockedInput.dockedKeyToX(down, pressedKeys.contains(event.getKeyCode()))) {
                ret = instance.handleKey(event);
                if (down && ret)
                    pressedKeys.add(event.getKeyCode());
            }
            if (event.getAction() == KeyEvent.ACTION_UP)
                pressedKeys.remove(event.getKeyCode());
            recheck();
            return ret;
        }

        boolean intercept = instance.shouldInterceptKeys();

        if (intercept || (event.getAction() == KeyEvent.ACTION_UP && pressedKeys.contains(event.getKeyCode())))
            ret = instance.handleKey(event);

        if (intercept && event.getAction() == KeyEvent.ACTION_DOWN)
            pressedKeys.add(event.getKeyCode());
        else
        // We should send key releases to activity for the case if user was pressing some keys when Activity lost focus.
        // I.e. if user switched window with Win+Tab or if he was pressing Ctrl while switching activity.
        if (event.getAction() == KeyEvent.ACTION_UP)
            pressedKeys.remove(event.getKeyCode());

        recheck();

        return ret;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {}

    @Override
    public void onInterrupt() {}
}
