package com.screen.textgrabber;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 悬浮窗快捷提取按钮控制器
 * 依托无障碍服务直接挂载 TYPE_ACCESSIBILITY_OVERLAY 悬浮按钮（免额外悬浮窗权限），
 * 支持拖动记忆位置、点击全量深抓当前应用页面无障碍文字并按指定前缀（默认“详细总结”）写入剪贴板。
 */
public class FloatingButtonController {

    private static final int CLIPBOARD_SAFE_MAX_CHARS = 400_000;
    private static final int COLOR_IDLE = 0xDD2563EB;
    private static final int COLOR_BUSY = 0xDD475569;
    private static final int COLOR_SUCCESS = 0xDD059669;
    private static final int COLOR_WARN = 0xDDD97706;
    private static final float ALPHA_IDLE = 0.86f;
    private static final float ALPHA_ACTIVE = 1.0f;

    private final AccessibilityService service;
    private final WindowManager windowManager;
    private final SharedPreferences sp;
    private final Handler mainHandler;
    private final ExecutorService executor;
    private final int touchSlop;

    private TextView buttonView;
    private WindowManager.LayoutParams layoutParams;
    private boolean isShowing = false;
    private volatile boolean extracting = false;

    private float downRawX;
    private float downRawY;
    private int startX;
    private int startY;
    private boolean isDragging;

    private final Runnable fadeRunnable = new Runnable() {
        @Override
        public void run() {
            if (isShowing && buttonView != null) {
                buttonView.setAlpha(ALPHA_IDLE);
            }
        }
    };

    private final Runnable resetBadgeRunnable = new Runnable() {
        @Override
        public void run() {
            if (isShowing && buttonView != null) {
                applyBadgeStyle(service.getString(R.string.float_btn_idle), COLOR_IDLE);
            }
        }
    };

    public FloatingButtonController(AccessibilityService service) {
        this.service = service;
        this.windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        this.sp = service.getSharedPreferences("app_config", Context.MODE_PRIVATE);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();
        this.touchSlop = ViewConfiguration.get(service).getScaledTouchSlop();
    }

    public boolean isShowing() {
        return isShowing;
    }

    public void show() {
        if (windowManager == null) {
            return;
        }
        if (isShowing && buttonView != null) {
            clampPositionToScreen();
            try {
                windowManager.updateViewLayout(buttonView, layoutParams);
            } catch (Exception ignored) {
            }
            return;
        }

        ensureButtonView();
        layoutParams = createLayoutParams();
        DisplayMetrics dm = service.getResources().getDisplayMetrics();
        int defaultX = Math.max(0, dm.widthPixels - dp(84));
        int defaultY = Math.max(dp(120), (int) (dm.heightPixels * 0.42f));
        layoutParams.x = sp.getInt("float_x", defaultX);
        layoutParams.y = sp.getInt("float_y", defaultY);
        clampPositionToScreen();

        boolean added = false;
        try {
            windowManager.addView(buttonView, layoutParams);
            added = true;
        } catch (Exception e) {
            // 极端 ROM 下若 TYPE_ACCESSIBILITY_OVERLAY 受限且已授予悬浮窗权限，则自动回退
            if (canUseOverlayFallback()) {
                layoutParams.type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;
                try {
                    windowManager.addView(buttonView, layoutParams);
                    added = true;
                } catch (Exception ignored) {
                }
            }
        }

        if (added) {
            isShowing = true;
            applyBadgeStyle(service.getString(R.string.float_btn_idle), COLOR_IDLE);
            scheduleIdleFade();
        }
    }

    public void hide() {
        mainHandler.removeCallbacks(fadeRunnable);
        mainHandler.removeCallbacks(resetBadgeRunnable);
        if (isShowing && buttonView != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(buttonView);
            } catch (Exception ignored) {
            }
        }
        isShowing = false;
    }

    public void onConfigurationChanged() {
        if (!isShowing || buttonView == null || layoutParams == null || windowManager == null) {
            return;
        }
        clampPositionToScreen();
        try {
            windowManager.updateViewLayout(buttonView, layoutParams);
        } catch (Exception ignored) {
        }
    }

    public void destroy() {
        hide();
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
    }

    private void ensureButtonView() {
        if (buttonView != null) {
            return;
        }
        TextView tv = new TextView(service);
        tv.setText(R.string.float_btn_idle);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13f);
        tv.getPaint().setFakeBoldText(true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(14), dp(9), dp(14), dp(9));
        tv.setMinWidth(dp(58));
        tv.setElevation(dp(6));
        tv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        tv.setContentDescription(service.getString(R.string.float_btn_desc));
        tv.setAlpha(ALPHA_IDLE);
        tv.setOnTouchListener(createTouchListener());
        buttonView = tv;
        applyBadgeStyle(service.getString(R.string.float_btn_idle), COLOR_IDLE);
    }

    private WindowManager.LayoutParams createLayoutParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        return params;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean canUseOverlayFallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(service);
        }
        return true;
    }

    private View.OnTouchListener createTouchListener() {
        return new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (layoutParams == null) {
                    return false;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = layoutParams.x;
                        startY = layoutParams.y;
                        isDragging = false;
                        mainHandler.removeCallbacks(fadeRunnable);
                        if (buttonView != null) {
                            buttonView.setAlpha(ALPHA_ACTIVE);
                        }
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(event.getRawX() - downRawX);
                        int dy = Math.round(event.getRawY() - downRawY);
                        if (!isDragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                            isDragging = true;
                        }
                        if (isDragging) {
                            layoutParams.x = startX + dx;
                            layoutParams.y = startY + dy;
                            clampPositionToScreen();
                            try {
                                windowManager.updateViewLayout(buttonView, layoutParams);
                            } catch (Exception ignored) {
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (isDragging) {
                            clampPositionToScreen();
                            sp.edit()
                                    .putInt("float_x", layoutParams.x)
                                    .putInt("float_y", layoutParams.y)
                                    .apply();
                            scheduleIdleFade();
                        } else {
                            triggerExtractAndCopy();
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        scheduleIdleFade();
                        return true;

                    default:
                        return false;
                }
            }
        };
    }

    private void triggerExtractAndCopy() {
        if (extracting || executor.isShutdown()) {
            return;
        }
        extracting = true;
        mainHandler.removeCallbacks(resetBadgeRunnable);
        mainHandler.removeCallbacks(fadeRunnable);
        if (buttonView != null) {
            buttonView.setAlpha(ALPHA_ACTIVE);
            applyBadgeStyle(service.getString(R.string.float_btn_busy), COLOR_BUSY);
        }

        final String prefix = sp.getString("float_copy_prefix", ScreenTextExtractor.DEFAULT_PREFIX);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                final ScreenTextExtractor.ExtractionResult result = ScreenTextExtractor.extractFullAppPage(service);
                final String rawText = result != null ? result.rawText : "";
                final int lineCount = result != null ? result.lineCount : 0;
                final String finalText = ScreenTextExtractor.buildPromptWithPrefix(prefix, rawText);

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        extracting = false;
                        if (finalText.isEmpty()) {
                            flashStatus(service.getString(R.string.float_btn_empty), COLOR_WARN);
                            Toast.makeText(service, R.string.float_extract_empty, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        copyExtractedTextToClipboard(finalText, lineCount, rawText.trim().length(), prefix);
                    }
                });
            }
        });
    }

    private void copyExtractedTextToClipboard(
            final String finalText,
            int lineCount,
            int rawCharCount,
            String prefix
    ) {
        ClipboardManager cm = (ClipboardManager) service.getSystemService(Context.CLIPBOARD_SERVICE);
        if (finalText.length() > CLIPBOARD_SAFE_MAX_CHARS) {
            try {
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText(
                            "TextGrabber",
                            finalText.substring(0, CLIPBOARD_SAFE_MAX_CHARS)
                    ));
                }
            } catch (RuntimeException ignored) {
            }
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    final ExportHelper.ExportResult res = ExportHelper.exportArchiveText(service, finalText);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            flashStatus(service.getString(R.string.float_btn_copied), COLOR_SUCCESS);
                            Toast.makeText(
                                    service,
                                    service.getString(R.string.copy_truncated_auto_export, res.displayPath),
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    });
                }
            });
            return;
        }

        try {
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("TextGrabber", finalText));
            }
            flashStatus(service.getString(R.string.float_btn_copied), COLOR_SUCCESS);
            String cleanPrefix = prefix != null ? prefix.trim() : "";
            String msg = cleanPrefix.isEmpty()
                    ? service.getString(R.string.float_extract_copied_no_prefix, lineCount, rawCharCount)
                    : service.getString(R.string.float_extract_copied_with_prefix, cleanPrefix, lineCount, rawCharCount);
            Toast.makeText(service, msg, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    final ExportHelper.ExportResult res = ExportHelper.exportArchiveText(service, finalText);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            flashStatus(service.getString(R.string.float_btn_copied), COLOR_SUCCESS);
                            Toast.makeText(
                                    service,
                                    service.getString(R.string.copy_failed_auto_export, res.displayPath),
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    });
                }
            });
        }
    }

    private void flashStatus(String label, int bgColor) {
        if (buttonView != null) {
            applyBadgeStyle(label, bgColor);
        }
        mainHandler.removeCallbacks(resetBadgeRunnable);
        mainHandler.postDelayed(resetBadgeRunnable, 1000L);
        scheduleIdleFade();
    }

    private void scheduleIdleFade() {
        mainHandler.removeCallbacks(fadeRunnable);
        mainHandler.postDelayed(fadeRunnable, 2600L);
    }

    private void applyBadgeStyle(String text, int bgColor) {
        if (buttonView == null) {
            return;
        }
        buttonView.setText(text);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(22));
        bg.setColor(bgColor);
        bg.setStroke(dp(1), 0x80FFFFFF);
        buttonView.setBackground(bg);
    }

    private void clampPositionToScreen() {
        if (layoutParams == null) {
            return;
        }
        DisplayMetrics dm = service.getResources().getDisplayMetrics();
        int btnW = (buttonView != null && buttonView.getWidth() > 0) ? buttonView.getWidth() : dp(72);
        int btnH = (buttonView != null && buttonView.getHeight() > 0) ? buttonView.getHeight() : dp(40);
        int maxX = Math.max(0, dm.widthPixels - btnW);
        int maxY = Math.max(0, dm.heightPixels - btnH);
        if (layoutParams.x < 0) layoutParams.x = 0;
        if (layoutParams.x > maxX) layoutParams.x = maxX;
        if (layoutParams.y < 0) layoutParams.y = 0;
        if (layoutParams.y > maxY) layoutParams.y = maxY;
    }

    private int dp(int value) {
        float density = service.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
