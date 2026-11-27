package com.termux.x11;

import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION.SDK_INT;
import static android.view.KeyEvent.*;
import static android.view.WindowManager.LayoutParams.*;
import static com.termux.x11.CmdEntryPoint.ACTION_START;
import static com.termux.x11.LoriePreferences.ACTION_PREFERENCES_CHANGED;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.math.MathUtils;
import androidx.viewpager.widget.ViewPager;

import com.termux.x11.input.InputEventSender;
import com.termux.x11.input.InputStub;
import com.termux.x11.input.TouchInputHandler;
import com.termux.x11.utils.FullscreenWorkaround;
import com.termux.x11.utils.KeyboardCapture;
import com.termux.x11.utils.KeyInterceptor;
import com.termux.x11.utils.SamsungDexUtils;
import com.termux.x11.utils.TermuxX11ExtraKeys;
import com.termux.x11.utils.X11ToolbarViewPager;

import java.util.Map;

@SuppressLint("ApplySharedPref")
@SuppressWarnings({"deprecation", "unused"})
public class MainActivity extends AppCompatActivity {
    public static final String ACTION_STOP = "com.termux.x11.ACTION_STOP";
    public static final String ACTION_CUSTOM = "com.termux.x11.ACTION_CUSTOM";

    // Host services can load this class before the activity, on a worker thread.
    public static Handler handler = new Handler(Looper.getMainLooper());
    FrameLayout frm;
    private TouchInputHandler mInputHandler;
    protected ICmdEntryInterface service = null;
    public TermuxX11ExtraKeys mExtraKeys;
    private Notification mNotification;
    private final int mNotificationId = 7892;
    NotificationManager mNotificationManager;
    static InputMethodManager inputMethodManager;
    private static boolean showIMEWhileExternalConnected = true;
    private static boolean externalKeyboardConnected = false;
    private View.OnKeyListener mLorieKeyListener;
    private boolean filterOutWinKey = false;
    boolean useTermuxEKBarBehaviour = false;
    private boolean isInPictureInPictureMode = false;
    /**
     * r12: whether the extra-key bar is on screen in THIS activity. Starts false
     * and is never restored from the saved preference, so every launch starts
     * with the bar hidden; the floating button (R.id.extra_keys_button) and the
     * "toggle additional key bar" input action flip it via toggleExtraKeys().
     */
    private boolean extraKeysVisible = false;

    public static Prefs prefs = null;

    private static boolean oldFullscreen = false, oldHideCutout = false;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesChangedListener = (__, key) -> onPreferencesChanged(key);

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        @Override
        public void onReceive(Context context, Intent intent) {
            prefs.recheckStoringSecondaryDisplayPreferences();
            if (ACTION_START.equals(intent.getAction())) {
                try {
                    Log.v("LorieBroadcastReceiver", "Got new ACTION_START intent");
                    onReceiveConnection(intent);
                } catch (Exception e) {
                    Log.e("MainActivity", "Something went wrong while we extracted connection details from binder.", e);
                }
            } else if (ACTION_STOP.equals(intent.getAction())) {
                finishAffinity();
            } else if (ACTION_PREFERENCES_CHANGED.equals(intent.getAction())) {
                Log.d("MainActivity", "preference: " + intent.getStringExtra("key"));
                if (!"additionalKbdVisible".equals(intent.getStringExtra("key")))
                    onPreferencesChanged("");
            } else if (ACTION_CUSTOM.equals(intent.getAction())) {
                android.util.Log.d("ACTION_CUSTOM", "action " + intent.getStringExtra("what"));
                mInputHandler.extractUserActionFromPreferences(prefs, intent.getStringExtra("what")).accept(0, true);
            }
        }
    };

    ViewTreeObserver.OnPreDrawListener mOnPredrawListener = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            if (LorieView.connected())
                handler.post(() -> findViewById(android.R.id.content).getViewTreeObserver().removeOnPreDrawListener(mOnPredrawListener));
            return false;
        }
    };

    @SuppressLint("StaticFieldLeak")
    private static MainActivity instance;

    public MainActivity() {
        instance = this;
    }

    public static Prefs getPrefs() {
        return prefs;
    }

    public static MainActivity getInstance() {
        return instance;
    }

    @Override
    @SuppressLint({"AppCompatMethod", "ObsoleteSdkInt", "ClickableViewAccessibility", "WrongConstant", "UnspecifiedRegisterReceiverFlag"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = new Prefs(this);
        int modeValue = Integer.parseInt(prefs.touchMode.get()) - 1;
        if (modeValue > 2)
            prefs.touchMode.put("1");

        oldFullscreen = prefs.fullscreen.get();
        oldHideCutout = prefs.hideCutout.get();

        prefs.get().registerOnSharedPreferenceChangeListener(preferencesChangedListener);

        getWindow().setFlags(FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS | FLAG_KEEP_SCREEN_ON | FLAG_TRANSLUCENT_STATUS, 0);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.main_activity);

        frm = findViewById(R.id.frame);
        // Phoshdroid passes padding extras for the X11 surface's margins. Since
        // r12 the launcher passes 0 for both: the extra-key bar is an overlay that
        // starts hidden, so nothing is reserved above or below the view. The extras
        // stay in the contract (see applyPhoshdroidPadding) so an older launcher
        // build still gets the margins it asks for.
        Intent launch = getIntent();
        if (launch != null) {
            builtinTopPadding = launch.getIntExtra("phoshdroid_builtin_top_padding_px",
                    launch.getIntExtra("phoshdroid_top_padding_px", 0));
            builtinBottomPadding = launch.getIntExtra("phoshdroid_builtin_bottom_padding_px",
                    launch.getIntExtra("phoshdroid_bottom_padding_px", 0));
        }
        findViewById(R.id.preferences_button).setOnClickListener((l) -> startActivity(new Intent(this, LoriePreferences.class) {{ setAction(Intent.ACTION_MAIN); }}));
        findViewById(R.id.help_button).setOnClickListener((l) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/termux/termux-x11/blob/master/README.md#running-graphical-applications"))));
        findViewById(R.id.exit_button).setOnClickListener((l) -> finish());

        LorieView lorieView = findViewById(R.id.lorieView);
        View lorieParent = (View) lorieView.getParent();

        applyPhoshdroidPadding();

        mInputHandler = new TouchInputHandler(this, new InputEventSender(lorieView));
        mLorieKeyListener = (v, k, e) -> {
            InputDevice dev = e.getDevice();
            boolean result = mInputHandler.sendKeyEvent(e);

            // Do not steal dedicated buttons from a full external keyboard.
            if (useTermuxEKBarBehaviour && mExtraKeys != null && (dev == null || dev.isVirtual()))
                mExtraKeys.unsetSpecialKeys();
            return result;
        };

        lorieParent.setOnTouchListener((v, e) -> {
            // Avoid batched MotionEvent objects and reduce potential latency.
            // For reference: https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features#rendering.
            if (e.getAction() == MotionEvent.ACTION_DOWN)
                lorieParent.requestUnbufferedDispatch(e);

            return mInputHandler.handleTouchEvent(lorieParent, lorieView, e);
        });
        lorieParent.setOnHoverListener((v, e) -> mInputHandler.handleTouchEvent(lorieParent, lorieView, e));
        lorieParent.setOnGenericMotionListener((v, e) -> mInputHandler.handleTouchEvent(lorieParent, lorieView, e));
        lorieView.setOnCapturedPointerListener((v, e) -> mInputHandler.handleTouchEvent(lorieView, lorieView, e));
        lorieParent.setOnCapturedPointerListener((v, e) -> mInputHandler.handleTouchEvent(lorieView, lorieView, e));
        lorieView.setOnKeyListener(mLorieKeyListener);

        lorieView.setCallback((surfaceWidth, surfaceHeight, screenWidth, screenHeight) -> {
            String name;
            int framerate = (int) ((lorieView.getDisplay() != null) ? lorieView.getDisplay().getRefreshRate() : 30);

            mInputHandler.handleHostSizeChanged(surfaceWidth, surfaceHeight);
            mInputHandler.handleClientSizeChanged(screenWidth, screenHeight);
            if (lorieView.getDisplay() == null || lorieView.getDisplay().getDisplayId() == Display.DEFAULT_DISPLAY)
                name = "builtin";
            else if (SamsungDexUtils.checkDeXEnabled(this))
                name = "dex";
            else
                name = "external";
            LorieView.sendWindowChange(screenWidth, screenHeight, framerate, name);
        });

        registerReceiver(receiver, new IntentFilter(ACTION_START) {{
            addAction(ACTION_PREFERENCES_CHANGED);
            addAction(ACTION_STOP);
            addAction(ACTION_CUSTOM);
        }}, SDK_INT >= VERSION_CODES.TIRAMISU ? RECEIVER_EXPORTED : 0);

        inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        // Taken from Stackoverflow answer https://stackoverflow.com/questions/7417123/android-how-to-adjust-layout-in-full-screen-mode-when-softkeyboard-is-visible/7509285#
        FullscreenWorkaround.assistActivity(this);
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotification = buildNotification();
        mNotificationManager.notify(mNotificationId, mNotification);

        if (tryConnect()) {
            final View content = findViewById(android.R.id.content);
            content.getViewTreeObserver().addOnPreDrawListener(mOnPredrawListener);
            handler.postDelayed(() -> content.getViewTreeObserver().removeOnPreDrawListener(mOnPredrawListener), 500);
        }
        onPreferencesChanged("");

        toggleExtraKeys(false, false);

        initStylusAuxButtons();
        initMouseAuxButtons();
        initExtraKeysButton();

        if (SDK_INT >= VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PERMISSION_GRANTED
                && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 0);
        }

        onReceiveConnection(getIntent());
        findViewById(android.R.id.content).addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> makeSureHelpersAreVisibleAndInScreenBounds());

        // Reserve the entire view area from Android's system gesture zones so the
        // bottom-edge swipe-up reaches the X11 surface instead of triggering Android
        // home/back. Android caps each exclusion rect to 200dp tall but the list of
        // rects is unlimited — fill the whole view with a stripe.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            final View content = findViewById(android.R.id.content);
            content.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                java.util.List<android.graphics.Rect> rects = new java.util.ArrayList<>();
                rects.add(new android.graphics.Rect(0, 0, right - left, bottom - top));
                content.setSystemGestureExclusionRects(rects);
            });
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(receiver);
        super.onDestroy();
        // r8: a destroyed activity never keeps a docked keyboard or mouse.
        KeyInterceptor.recheck();
    }

    /** r8: true between onStart and onStop, i.e. while this window can be seen. */
    private boolean startedForInput = false;

    /** r8: docked input capture only runs while the X window is visible. */
    public boolean isStartedForInput() {
        return startedForInput;
    }

    @Override
    protected void onStart() {
        super.onStart();
        startedForInput = true;
        KeyInterceptor.recheck();
    }

    @Override
    protected void onStop() {
        startedForInput = false;
        KeyInterceptor.recheck();
        super.onStop();
    }

    //Register the needed events to handle stylus as left, middle and right click
    @SuppressLint("ClickableViewAccessibility")
    private void initStylusAuxButtons() {
        final ViewPager pager = getTerminalToolbarViewPager();
        boolean stylusMenuEnabled = prefs.showStylusClickOverride.get() && LorieView.connected();
        final float menuUnselectedTrasparency = 0.66f;
        final float menuSelectedTrasparency = 1.0f;
        Button left = findViewById(R.id.button_left_click);
        Button right = findViewById(R.id.button_right_click);
        Button middle = findViewById(R.id.button_middle_click);
        Button visibility = findViewById(R.id.button_visibility);
        LinearLayout overlay = findViewById(R.id.mouse_helper_visibility);
        LinearLayout buttons = findViewById(R.id.mouse_helper_secondary_layer);
        overlay.setOnTouchListener((v, e) -> true);
        overlay.setOnHoverListener((v, e) -> true);
        overlay.setOnGenericMotionListener((v, e) -> true);
        overlay.setOnCapturedPointerListener((v, e) -> true);
        overlay.setVisibility(stylusMenuEnabled ? View.VISIBLE : View.GONE);
        View.OnClickListener listener = view -> {
            TouchInputHandler.STYLUS_INPUT_HELPER_MODE = (view.equals(left) ? 1 : (view.equals(middle) ? 2 : (view.equals(right) ? 4 : 0)));
            left.setAlpha((TouchInputHandler.STYLUS_INPUT_HELPER_MODE == 1) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            middle.setAlpha((TouchInputHandler.STYLUS_INPUT_HELPER_MODE == 2) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            right.setAlpha((TouchInputHandler.STYLUS_INPUT_HELPER_MODE == 4) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            visibility.setAlpha(menuUnselectedTrasparency);
        };

        left.setOnClickListener(listener);
        middle.setOnClickListener(listener);
        right.setOnClickListener(listener);

        visibility.setOnClickListener(view -> {
            if (buttons.getVisibility() == View.VISIBLE) {
                buttons.setVisibility(View.GONE);
                visibility.setAlpha(menuUnselectedTrasparency);
                int m = TouchInputHandler.STYLUS_INPUT_HELPER_MODE;
                visibility.setText(m == 1 ? "L" : (m == 2 ? "M" : (m == 3 ? "R" : "U")));
            } else {
                buttons.setVisibility(View.VISIBLE);
                visibility.setAlpha(menuUnselectedTrasparency);
                visibility.setText("X");

                //Calculate screen border making sure btn is fully inside the view
                float maxX = frm.getWidth() - 4 * left.getWidth();
                float maxY = frm.getHeight() - 4 * left.getHeight();
                if (pager.getVisibility() == View.VISIBLE)
                    maxY -= pager.getHeight();

                //Make sure the Stylus menu is fully inside the screen
                overlay.setX(MathUtils.clamp(overlay.getX(), 0, maxX));
                overlay.setY(MathUtils.clamp(overlay.getY(), 0, maxY));

                int m = TouchInputHandler.STYLUS_INPUT_HELPER_MODE;
                listener.onClick(m == 1 ? left : (m == 2 ? middle : (m == 3 ? right : left)));
            }
        });
        //Simulated mouse click 1 = left , 2 = middle , 3 = right
        TouchInputHandler.STYLUS_INPUT_HELPER_MODE = 1;
        listener.onClick(left);

        visibility.setOnLongClickListener(v -> {
            v.startDragAndDrop(ClipData.newPlainText("", ""), new View.DragShadowBuilder(visibility) {
                public void onDrawShadow(@NonNull Canvas canvas) {}
            }, null, View.DRAG_FLAG_GLOBAL);

            frm.setOnDragListener((v2, event) -> {
                //Calculate screen border making sure btn is fully inside the view
                float maxX = frm.getWidth() - visibility.getWidth();
                float maxY = frm.getHeight() - visibility.getHeight();
                if (pager.getVisibility() == View.VISIBLE)
                    maxY -= pager.getHeight();

                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_LOCATION:
                        //Center touch location with btn icon
                        float dX = event.getX() - visibility.getWidth() / 2.0f;
                        float dY = event.getY() - visibility.getHeight() / 2.0f;

                        //Make sure the dragged btn is inside the view with clamp
                        overlay.setX(MathUtils.clamp(dX, 0, maxX));
                        overlay.setY(MathUtils.clamp(dY, 0, maxY));
                        break;
                    case DragEvent.ACTION_DRAG_ENDED:
                        //Make sure the dragged btn is inside the view
                        overlay.setX(MathUtils.clamp(overlay.getX(), 0, maxX));
                        overlay.setY(MathUtils.clamp(overlay.getY(), 0, maxY));
                        break;
                }
                return true;
            });

            return true;
        });
    }

    private void showStylusAuxButtons(boolean show) {
        LinearLayout buttons = findViewById(R.id.mouse_helper_visibility);
        if (LorieView.connected() && show) {
            buttons.setVisibility(View.VISIBLE);
            buttons.setAlpha(isInPictureInPictureMode ? 0.f : 1.f);
        } else {
            //Reset default input back to normal
            TouchInputHandler.STYLUS_INPUT_HELPER_MODE = 1;
            final float menuUnselectedTrasparency = 0.66f;
            final float menuSelectedTrasparency = 1.0f;
            findViewById(R.id.button_left_click).setAlpha(menuSelectedTrasparency);
            findViewById(R.id.button_right_click).setAlpha(menuUnselectedTrasparency);
            findViewById(R.id.button_middle_click).setAlpha(menuUnselectedTrasparency);
            findViewById(R.id.button_visibility).setAlpha(menuUnselectedTrasparency);
            buttons.setVisibility(View.GONE);
        }
    }

    private void makeSureHelpersAreVisibleAndInScreenBounds() {
        final ViewPager pager = getTerminalToolbarViewPager();
        View mouseAuxButtons = findViewById(R.id.mouse_buttons);
        View stylusAuxButtons = findViewById(R.id.mouse_helper_visibility);
        int maxYDecrement = (pager.getVisibility() == View.VISIBLE) ? pager.getHeight() : 0;

        mouseAuxButtons.setX(MathUtils.clamp(mouseAuxButtons.getX(), frm.getX(), frm.getX() + frm.getWidth() - mouseAuxButtons.getWidth()));
        mouseAuxButtons.setY(MathUtils.clamp(mouseAuxButtons.getY(), frm.getY(), frm.getY() + frm.getHeight() - mouseAuxButtons.getHeight() - maxYDecrement));

        stylusAuxButtons.setX(MathUtils.clamp(stylusAuxButtons.getX(), frm.getX(), frm.getX() + frm.getWidth() - stylusAuxButtons.getWidth()));
        stylusAuxButtons.setY(MathUtils.clamp(stylusAuxButtons.getY(), frm.getY(), frm.getY() + frm.getHeight() - stylusAuxButtons.getHeight() - maxYDecrement));
    }

    public void toggleStylusAuxButtons() {
        showStylusAuxButtons(findViewById(R.id.mouse_helper_visibility).getVisibility() != View.VISIBLE);
        makeSureHelpersAreVisibleAndInScreenBounds();
    }

    private void showMouseAuxButtons(boolean show) {
        View v = findViewById(R.id.mouse_buttons);
        v.setVisibility((LorieView.connected() && show && "1".equals(prefs.touchMode.get())) ? View.VISIBLE : View.GONE);
        v.setAlpha(isInPictureInPictureMode ? 0.f : 0.7f);
        makeSureHelpersAreVisibleAndInScreenBounds();
    }

    public void toggleMouseAuxButtons() {
        showMouseAuxButtons(findViewById(R.id.mouse_buttons).getVisibility() != View.VISIBLE);
    }

    void setSize(View v, int width, int height) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        p.width = (int) (width * getResources().getDisplayMetrics().density);
        p.height = (int) (height * getResources().getDisplayMetrics().density);
        v.setLayoutParams(p);
        v.setMinimumWidth((int) (width * getResources().getDisplayMetrics().density));
        v.setMinimumHeight((int) (height * getResources().getDisplayMetrics().density));
    }

    @SuppressLint("ClickableViewAccessibility")
    void initMouseAuxButtons() {
        final ViewPager pager = getTerminalToolbarViewPager();
        Button left = findViewById(R.id.mouse_button_left_click);
        Button right = findViewById(R.id.mouse_button_right_click);
        Button middle = findViewById(R.id.mouse_button_middle_click);
        ImageButton pos = findViewById(R.id.mouse_buttons_position);
        LinearLayout primaryLayer = findViewById(R.id.mouse_buttons);
        LinearLayout secondaryLayer = findViewById(R.id.mouse_buttons_secondary_layer);

        boolean mouseHelperEnabled = prefs.showMouseHelper.get() && "1".equals(prefs.touchMode.get());
        primaryLayer.setVisibility(mouseHelperEnabled ? View.VISIBLE : View.GONE);

        pos.setOnClickListener((v) -> {
            if (secondaryLayer.getOrientation() == LinearLayout.HORIZONTAL) {
                setSize(left, 48, 96);
                setSize(right, 48, 96);
                secondaryLayer.setOrientation(LinearLayout.VERTICAL);
            } else {
                setSize(left, 96, 48);
                setSize(right, 96, 48);
                secondaryLayer.setOrientation(LinearLayout.HORIZONTAL);
            }
            handler.postDelayed(() -> {
                float maxX = frm.getX() + frm.getWidth() - primaryLayer.getWidth();
                float maxY = frm.getY() + frm.getHeight() - primaryLayer.getHeight();
                if (pager.getVisibility() == View.VISIBLE)
                    maxY -= pager.getHeight();
                primaryLayer.setX(MathUtils.clamp(primaryLayer.getX(), frm.getX(), maxX));
                primaryLayer.setY(MathUtils.clamp(primaryLayer.getY(), frm.getY(), maxY));
            }, 10);
        });

        Map.of(left, InputStub.BUTTON_LEFT, middle, InputStub.BUTTON_MIDDLE, right, InputStub.BUTTON_RIGHT)
                .forEach((v, b) -> v.setOnTouchListener((__, e) -> {
            switch(e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    getLorieView().sendMouseEvent(0, 0, b, true, true);
                    v.setPressed(true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    getLorieView().sendMouseEvent(0, 0, b, false, true);
                    v.setPressed(false);
                    break;
            }
            return true;
        }));

        pos.setOnTouchListener(new View.OnTouchListener() {
            final int touchSlop = (int) Math.pow(ViewConfiguration.get(MainActivity.this).getScaledTouchSlop(), 2);
            final int tapTimeout = ViewConfiguration.getTapTimeout();
            final float[] startOffset = new float[2];
            final int[] startPosition = new int[2];
            long startTime;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch(e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        primaryLayer.getLocationInWindow(startPosition);
                        startOffset[0] = e.getX();
                        startOffset[1] = e.getY();
                        startTime = SystemClock.uptimeMillis();
                        pos.setPressed(true);
                        break;
                    case MotionEvent.ACTION_MOVE: {
                        final ViewPager pager = getTerminalToolbarViewPager();
                        int[] offset = new int[2];
                        primaryLayer.getLocationInWindow(offset);
                        float maxX = frm.getX() + frm.getWidth() - primaryLayer.getWidth();
                        float maxY = frm.getY() + frm.getHeight() - primaryLayer.getHeight();
                        if (pager.getVisibility() == View.VISIBLE)
                            maxY -= pager.getHeight();

                        primaryLayer.setX(MathUtils.clamp(offset[0] - startOffset[0] + e.getX(), frm.getX(), maxX));
                        primaryLayer.setY(MathUtils.clamp(offset[1] - startOffset[1] + e.getY(), frm.getY(), maxY));
                        break;
                    }
                    case MotionEvent.ACTION_UP: {
                        final int[] _pos = new int[2];
                        primaryLayer.getLocationInWindow(_pos);
                        int deltaX = (int) (startOffset[0] - e.getX()) + (startPosition[0] - _pos[0]);
                        int deltaY = (int) (startOffset[1] - e.getY()) + (startPosition[1] - _pos[1]);
                        pos.setPressed(false);

                        if (deltaX * deltaX + deltaY * deltaY < touchSlop && SystemClock.uptimeMillis() - startTime <= tapTimeout) {
                            v.performClick();
                            return true;
                        }
                        break;
                    }
                }
                return true;
            }
        });
    }

    void onReceiveConnection(Intent intent) {
        Bundle bundle = intent == null ? null : intent.getBundleExtra(null);
        IBinder ibinder = bundle == null ? null : bundle.getBinder(null);
        if (ibinder == null)
            return;

        service = ICmdEntryInterface.Stub.asInterface(ibinder);
        try {
            service.asBinder().linkToDeath(() -> {
                service = null;

                Log.v("Lorie", "Disconnected");
                runOnUiThread(() -> { LorieView.connect(-1); clientConnectedStateChanged();} );
            }, 0);
        } catch (RemoteException ignored) {}

        try {
            if (service != null && service.asBinder().isBinderAlive()) {
                Log.v("LorieBroadcastReceiver", "Extracting logcat fd.");
                ParcelFileDescriptor logcatOutput = service.getLogcatOutput();
                if (logcatOutput != null)
                    LorieView.startLogcat(logcatOutput.detachFd());

                tryConnect();

                if (intent != getIntent())
                    getIntent().putExtra(null, bundle);
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", e);
        }
    }

    boolean tryConnect() {
        if (LorieView.connected())
            return false;

        if (service == null) {
            boolean sent = LorieView.requestConnection();
            handler.postDelayed(this::tryConnect, 250);
            return true;
        }

        try {
            ParcelFileDescriptor fd = service.getXConnection();
            if (fd != null) {
                Log.v("MainActivity", "Extracting X connection socket.");
                LorieView.connect(fd.detachFd());
                getLorieView().triggerCallback();
                clientConnectedStateChanged();
                getLorieView().reloadPreferences(prefs);
            } else
                handler.postDelayed(this::tryConnect, 250);
        } catch (Exception e) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", e);
            service = null;

            handler.postDelayed(this::tryConnect, 250);
        }
        return false;
    }

    void onPreferencesChanged(String key) {
        if ("additionalKbdVisible".equals(key))
            return;

        handler.removeCallbacks(this::onPreferencesChangedCallback);
        handler.postDelayed(this::onPreferencesChangedCallback, 100);
    }

    @SuppressLint("UnsafeIntentLaunch")
    void onPreferencesChangedCallback() {
        prefs.recheckStoringSecondaryDisplayPreferences();

        onWindowFocusChanged(hasWindowFocus());
        LorieView lorieView = getLorieView();

        mInputHandler.reloadPreferences(prefs);
        lorieView.reloadPreferences(prefs);

        setTerminalToolbarView();

        lorieView.triggerCallback();

        filterOutWinKey = prefs.filterOutWinkey.get();
        if (prefs.enableAccessibilityServiceAutomatically.get())
            KeyInterceptor.launch(this);
        else if (checkSelfPermission(WRITE_SECURE_SETTINGS) == PERMISSION_GRANTED)
            KeyInterceptor.shutdown(true);

        useTermuxEKBarBehaviour = prefs.useTermuxEKBarBehaviour.get();
        showIMEWhileExternalConnected = prefs.showIMEWhileExternalConnected.get();

        findViewById(R.id.mouse_buttons).setVisibility(prefs.showMouseHelper.get() && "1".equals(prefs.touchMode.get()) && LorieView.connected() ? View.VISIBLE : View.GONE);
        showMouseAuxButtons(prefs.showMouseHelper.get());
        showStylusAuxButtons(prefs.showStylusClickOverride.get());

        getTerminalToolbarViewPager().setAlpha(isInPictureInPictureMode ? 0.f : ((float) prefs.opacityEKBar.get())/100);

        lorieView.requestLayout();
        lorieView.invalidate();

        for (StatusBarNotification notification: mNotificationManager.getActiveNotifications())
            if (notification.getId() == mNotificationId) {
                mNotification = buildNotification();
                mNotificationManager.notify(mNotificationId, mNotification);
            }
    }

    @Override
    public void onResume() {
        super.onResume();

        mNotification = buildNotification();
        mNotificationManager.notify(mNotificationId, mNotification);

        setTerminalToolbarView();
        getLorieView().requestFocus();
    }

    @Override
    public void onPause() {
        inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getRootView().getWindowToken(), 0);

        for (StatusBarNotification notification: mNotificationManager.getActiveNotifications())
            if (notification.getId() == mNotificationId)
                mNotificationManager.cancel(mNotificationId);

        super.onPause();
    }

    public LorieView getLorieView() {
        return findViewById(R.id.lorieView);
    }

    public ViewPager getTerminalToolbarViewPager() {
        return findViewById(R.id.terminal_toolbar_view_pager);
    }

    private void setTerminalToolbarView() {
        final ViewPager pager = getTerminalToolbarViewPager();
        ViewGroup parent = (ViewGroup) pager.getParent();

        // r12: the saved additionalKbdVisible preference no longer decides this;
        // extraKeysVisible starts false on every launch and only a user action
        // (the summon button, or the toggle input action) turns it on.
        boolean showNow = LorieView.connected() && prefs.showAdditionalKbd.get() && extraKeysVisible;

        pager.setVisibility(showNow ? View.VISIBLE : View.INVISIBLE);

        if (showNow) {
            pager.setAdapter(new X11ToolbarViewPager.PageAdapter(this, (v, k, e) -> mInputHandler.sendKeyEvent(e)));
            pager.clearOnPageChangeListeners();
            pager.addOnPageChangeListener(new X11ToolbarViewPager.OnPageChangeListener(this, pager));
            pager.bringToFront();
        } else {
            parent.removeView(pager);
            parent.addView(pager, 0);
            if (mExtraKeys != null)
                mExtraKeys.unsetSpecialKeys();
        }

        ViewGroup.LayoutParams layoutParams = pager.getLayoutParams();
        layoutParams.height = Math.round(37.5f * getResources().getDisplayMetrics().density *
                (TermuxX11ExtraKeys.getExtraKeysInfo() == null ? 0 : TermuxX11ExtraKeys.getExtraKeysInfo().getMatrix().length));
        pager.setLayoutParams(layoutParams);

        frm.setPadding(0, 0, 0, prefs.adjustHeightForEK.get() && showNow ? layoutParams.height : 0);
        getLorieView().requestFocus();
        layoutExtraKeysButton();
    }

    public void toggleExtraKeys(boolean visible, boolean saveState) {
        boolean enabled = prefs.showAdditionalKbd.get();

        // r12: summoned, not restored. The preference still records the last
        // state (the settings screen uses it), but the bar's visibility comes
        // from this field, which starts false in every activity.
        extraKeysVisible = enabled && visible;

        if (enabled && LorieView.connected() && saveState)
            prefs.additionalKbdVisible.put(visible);

        setTerminalToolbarView();
        getWindow().setSoftInputMode(prefs.Reseed.get() ? SOFT_INPUT_ADJUST_RESIZE : SOFT_INPUT_ADJUST_PAN);
    }

    public void toggleExtraKeys() {
        toggleExtraKeys(getTerminalToolbarViewPager().getVisibility() != View.VISIBLE, true);
    }

    /**
     * r12: the extra-key bar's summon button. The bar starts hidden in every
     * session, so this button is the way back to it: it runs the same toggle as
     * the "toggle additional key bar" input action, showing and hiding the bar.
     */
    private void initExtraKeysButton() {
        View button = findViewById(R.id.extra_keys_button);
        if (button == null)
            return;
        button.setOnClickListener(v -> toggleExtraKeys());
        layoutExtraKeysButton();
    }

    /**
     * r12: the button follows the surface (visible while the X view is, gone on
     * the disconnected stub and in PiP) and rides above the extra-key bar while
     * the bar is shown, so the keys never cover it. Called from onCreate and
     * from every path that re-evaluates the bar.
     */
    private void layoutExtraKeysButton() {
        View button = findViewById(R.id.extra_keys_button);
        if (button == null)
            return;
        boolean show = LorieView.connected() && !isInPictureInPictureMode;
        button.setVisibility(show ? View.VISIBLE : View.GONE);
        button.setAlpha(show ? 1.f : 0.f);
        ViewPager pager = getTerminalToolbarViewPager();
        ViewGroup.LayoutParams lp = pager.getLayoutParams();
        int lift = (pager.getVisibility() == View.VISIBLE && lp != null) ? lp.height : 0;
        button.setTranslationY(-lift);
    }

    public boolean handleKey(KeyEvent e) {
        if (filterOutWinKey && (e.getKeyCode() == KEYCODE_META_LEFT || e.getKeyCode() == KEYCODE_META_RIGHT || e.isMetaPressed()))
            return false;
        return mLorieKeyListener.onKey(getLorieView(), e.getKeyCode(), e);
    }

    @SuppressLint("ObsoleteSdkInt")
    Notification buildNotification() {
        NotificationCompat.Builder builder =  new NotificationCompat.Builder(this, getNotificationChannel(mNotificationManager))
                .setContentTitle("Termux:X11")
                .setSmallIcon(R.drawable.ic_x11_icon)
                .setContentText(getResources().getText(R.string.lorie_notification_content_text))
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MAX)
                .setSilent(true)
                .setShowWhen(false)
                .setColor(0xFF607D8B);
        return mInputHandler.setupNotification(prefs, builder).build();
    }

    private String getNotificationChannel(NotificationManager notificationManager){
        String channelId = getResources().getString(R.string.app_name);
        String channelName = getResources().getString(R.string.app_name);
        NotificationChannel channel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH);
        channel.setImportance(NotificationManager.IMPORTANCE_HIGH);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        if (SDK_INT >= VERSION_CODES.Q)
            channel.setAllowBubbles(false);
        notificationManager.createNotificationChannel(channel);
        return channelId;
    }

    /** r7: the launcher's gaps for the built-in display, read once in onCreate. */
    private int builtinTopPadding = 0;
    private int builtinBottomPadding = 0;

    /** r7: true unless this window is on a display other than the built-in one. */
    private boolean onBuiltinDisplay() {
        Display display = null;
        try {
            display = getDisplay();
        } catch (Throwable ignored) {
            // No display attached yet: treat it as the built-in one, as before r7.
        }
        return display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY;
    }

    /**
     * r7: the view's margins for the display the window is on NOW. The built-in
     * display keeps the launcher's gaps, any other display gets none, so a session
     * moved to a monitor never keeps a black band from a built-in-display gap
     * (X root 1920x915 on a 1920x1080 display before r7). r12: the launcher passes
     * 0 for both gaps because the extra-key bar is an overlay that starts hidden,
     * so in practice the margins are 0; the mechanism stays for an older launcher
     * build's extras. Idempotent: called on create, on every configuration change
     * (a display move is one), focus change and new intent.
     */
    void applyPhoshdroidPadding() {
        View view = findViewById(R.id.lorieView);
        if (view == null)
            return;
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (!(lp instanceof FrameLayout.LayoutParams))
            return;
        boolean builtin = onBuiltinDisplay();
        int top = builtin ? builtinTopPadding : 0;
        int bottom = builtin ? builtinBottomPadding : 0;
        FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
        if (flp.topMargin == top && flp.bottomMargin == bottom)
            return;
        flp.topMargin = top;
        flp.bottomMargin = bottom;
        // The layout centres lorieView, so margins would read as a shift around
        // the centre row. Pin the view to the top instead: then top/bottom margins
        // mean what they say (view at row `top`, height = parent - top - bottom),
        // which is what the host's geometry prediction assumes.
        flp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        view.setLayoutParams(flp);
        android.util.Log.i("Phoshdroid", "x-view margins top=" + top + " bottom=" + bottom
                + (builtin ? " (built-in display)" : " (display " + getDisplay().getDisplayId() + ")"));
    }

    int orientation;

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (newConfig.orientation != orientation)
            inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getRootView().getWindowToken(), 0);

        orientation = newConfig.orientation;
        setTerminalToolbarView();
        // r7: a move between displays arrives here (configChanges covers it).
        applyPhoshdroidPadding();
        // r8: a move onto or off a monitor switches docked input capture.
        KeyInterceptor.recheck();
        handler.postDelayed(KeyInterceptor::recheck, 500);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // r7: the dock monitor re-launches this activity onto a monitor.
        applyPhoshdroidPadding();
        KeyInterceptor.recheck();
    }

    @SuppressLint("WrongConstant")
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        KeyInterceptor.recheck();
        prefs.recheckStoringSecondaryDisplayPreferences();
        Window window = getWindow();
        View decorView = window.getDecorView();
        boolean fullscreen = prefs.fullscreen.get();
        boolean hideCutout = prefs.hideCutout.get();
        boolean reseed = prefs.Reseed.get();

        if (oldHideCutout != hideCutout || oldFullscreen != fullscreen) {
            oldHideCutout = hideCutout;
            oldFullscreen = fullscreen;
            // For some reason cutout or fullscreen change makes layout calculations wrong and invalid.
            // I did not find simple and reliable way to fix it so it is better to start from the beginning.
            recreate();
            return;
        }

        int requestedOrientation;
        switch (prefs.forceOrientation.get()) {
            case "portrait": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; break;
            case "landscape": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; break;
            case "reverse portrait": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT; break;
            case "reverse landscape": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE; break;
            default: requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }

        if (getRequestedOrientation() != requestedOrientation)
            setRequestedOrientation(requestedOrientation);

        if (hasFocus) {
            if (SDK_INT >= VERSION_CODES.P) {
                if (hideCutout)
                    getWindow().getAttributes().layoutInDisplayCutoutMode = (SDK_INT >= VERSION_CODES.R) ?
                            LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS :
                            LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                else
                    getWindow().getAttributes().layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            }

            window.setStatusBarColor(Color.BLACK);
            window.setNavigationBarColor(Color.BLACK);
        }

        window.setFlags(FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS | FLAG_KEEP_SCREEN_ON | FLAG_TRANSLUCENT_STATUS, 0);
        if (hasFocus) {
            if (fullscreen) {
                window.addFlags(FLAG_FULLSCREEN);
                decorView.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            } else {
                window.clearFlags(FLAG_FULLSCREEN);
                decorView.setSystemUiVisibility(0);
            }
        }

        if (prefs.keepScreenOn.get())
            window.addFlags(FLAG_KEEP_SCREEN_ON);
        else
            window.clearFlags(FLAG_KEEP_SCREEN_ON);

        window.setSoftInputMode(reseed ? SOFT_INPUT_ADJUST_RESIZE : SOFT_INPUT_ADJUST_PAN);

        ((FrameLayout) findViewById(android.R.id.content)).getChildAt(0).setFitsSystemWindows(!fullscreen);

        // r7 a5: with this window focused on a monitor (or under DeX), ask for the
        // platform's keyboard capture so Meta reaches the X server before the
        // system's own shortcuts do. Reflection, because the API is Android 16.1
        // and this tree compiles against 35; when it is absent the accessibility
        // KeyInterceptor above is still the path. Capture is handed back as soon
        // as the window loses focus, so the phone's own shortcuts work again.
        boolean onExternalDisplay = getLorieView() != null
                && getLorieView().getDisplay() != null
                && getLorieView().getDisplay().getDisplayId() != Display.DEFAULT_DISPLAY;
        KeyboardCapture.apply(this, hasFocus && (onExternalDisplay || SamsungDexUtils.checkDeXEnabled(this)));
        applyPhoshdroidPadding();
    }

    @Override
    public void onBackPressed() {
    }

    public static boolean hasPipPermission(@NonNull Context context) {
        AppOpsManager appOpsManager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOpsManager == null)
            return false;
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            return appOpsManager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        else
            return appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public void onUserLeaveHint() {
        if (prefs.PIP.get() && hasPipPermission(this)) {
            enterPictureInPictureMode();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
        this.isInPictureInPictureMode = isInPictureInPictureMode;
        final ViewPager pager = getTerminalToolbarViewPager();
        pager.setAlpha(isInPictureInPictureMode ? 0.f : ((float) prefs.opacityEKBar.get())/100);
        findViewById(R.id.mouse_buttons).setAlpha(isInPictureInPictureMode ? 0.f : 0.7f);
        findViewById(R.id.mouse_helper_visibility).setAlpha(isInPictureInPictureMode ? 0.f : 1.f);
        layoutExtraKeysButton();

        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
    }

    /**
     * Manually toggle soft keyboard visibility
     * @param context calling context
     */
    public static void toggleKeyboardVisibility(Context context) {
        Log.d("MainActivity", "Toggling keyboard visibility");
        if(inputMethodManager != null) {
            android.util.Log.d("toggleKeyboardVisibility", "externalKeyboardConnected " + externalKeyboardConnected + " showIMEWhileExternalConnected " + showIMEWhileExternalConnected);
            if (!externalKeyboardConnected || showIMEWhileExternalConnected)
                inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
            else
                inputMethodManager.hideSoftInputFromWindow(getInstance().getWindow().getDecorView().getRootView().getWindowToken(), 0);

            getInstance().getLorieView().requestFocus();
        }
    }

    @SuppressWarnings("SameParameterValue")
    void clientConnectedStateChanged() {
        runOnUiThread(()-> {
            boolean connected = LorieView.connected();
            setTerminalToolbarView();
            findViewById(R.id.mouse_buttons).setVisibility(prefs.showMouseHelper.get() && "1".equals(prefs.touchMode.get()) && connected ? View.VISIBLE : View.GONE);
            findViewById(R.id.stub).setVisibility(connected?View.INVISIBLE:View.VISIBLE);
            getLorieView().setVisibility(connected?View.VISIBLE:View.INVISIBLE);

            // We should recover connection in the case if file descriptor for some reason was broken...
            if (!connected)
                tryConnect();
            else
                getLorieView().setPointerIcon(PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL));

            onWindowFocusChanged(hasWindowFocus());
        });
    }

    public static boolean isConnected() {
        if (getInstance() == null)
            return false;

        return LorieView.connected();
    }

    public static void getRealMetrics(DisplayMetrics m) {
        if (getInstance() != null &&
                getInstance().getLorieView() != null &&
                getInstance().getLorieView().getDisplay() != null)
            getInstance().getLorieView().getDisplay().getRealMetrics(m);
    }

    public static void setCapturingEnabled(boolean enabled) {
        if (getInstance() == null || getInstance().mInputHandler == null)
            return;

        getInstance().mInputHandler.setCapturingEnabled(enabled);
    }

    public boolean shouldInterceptKeys() {
        View textInput = findViewById(R.id.terminal_toolbar_text_input);
        if (mInputHandler == null || !hasWindowFocus() || (textInput != null && textInput.isFocused()))
            return false;

        return mInputHandler.shouldInterceptKeys();
    }

    public void setExternalKeyboardConnected(boolean connected) {
        externalKeyboardConnected = connected;
        EditText textInput = findViewById(R.id.terminal_toolbar_text_input);
        if (textInput != null)
            textInput.setShowSoftInputOnFocus(!connected || showIMEWhileExternalConnected);
        if (connected && !showIMEWhileExternalConnected)
            inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getRootView().getWindowToken(), 0);
        getLorieView().requestFocus();
    }
}
