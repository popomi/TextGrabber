package com.screen.textgrabber;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 核心无障碍服务类
 * 负责监听屏幕事件，遍历视图树，提取文字并保存，同时管理悬浮窗快捷提取按钮。
 */
public class MyAccessibilityService extends AccessibilityService {

    private static final String TAG = "MyAccessibilityService";
    private static volatile MyAccessibilityService instance;

    private boolean isFunctionEnabled = false;
    private DBManager dbManager;
    private DeduplicationLogic deduplicationLogic;
    private FloatingButtonController floatingController;
    private long lastProcessTime = 0;
    private static final long PROCESS_INTERVAL = 500;
    private int savedCount = 0;
    private long lastClearVersion = 0L;

    public static boolean isServiceConnected() {
        return instance != null;
    }

    /**
     * 立即同步悬浮窗显示/隐藏状态
     */
    public static void syncFloatingWindowState() {
        final MyAccessibilityService svc = instance;
        if (svc == null) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            svc.applyFloatingWindowPreference();
        } else {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    if (instance == svc) {
                        svc.applyFloatingWindowPreference();
                    }
                }
            });
        }
    }

    /**
     * 当外部（如 MainActivity 一键清空）清理数据时，通知服务立即清空内存去重缓存并归零本次计数
     */
    public static void notifyDataCleared() {
        MyAccessibilityService svc = instance;
        if (svc != null) {
            if (svc.deduplicationLogic != null) {
                svc.deduplicationLogic.clear();
            }
            svc.savedCount = 0;
            SharedPreferences sp = svc.getSharedPreferences("app_config", MODE_PRIVATE);
            svc.lastClearVersion = sp.getLong("clear_version", 0L);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.d(TAG, "onCreate: Service created");
        savedCount = 0;
        SharedPreferences sp = getSharedPreferences("app_config", MODE_PRIVATE);
        sp.edit().putInt("saved_count", 0).apply();
        lastClearVersion = sp.getLong("clear_version", 0L);
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "onServiceConnected: Service connected");
        android.widget.Toast.makeText(this, "TextGrabber 服务已启动", android.widget.Toast.LENGTH_SHORT).show();

        dbManager = new DBManager(this);
        deduplicationLogic = new DeduplicationLogic();
        floatingController = new FloatingButtonController(this);

        SharedPreferences sp = getSharedPreferences("app_config", MODE_PRIVATE);
        isFunctionEnabled = sp.getBoolean("is_active", false);
        lastClearVersion = sp.getLong("clear_version", 0L);
        Log.d(TAG, "Initial function enabled state: " + isFunctionEnabled);

        applyFloatingWindowPreference();
    }

    private void applyFloatingWindowPreference() {
        if (floatingController == null) {
            return;
        }
        SharedPreferences sp = getSharedPreferences("app_config", MODE_PRIVATE);
        boolean floatingEnabled = sp.getBoolean("floating_enabled", false);
        if (floatingEnabled) {
            floatingController.show();
        } else {
            floatingController.hide();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        SharedPreferences sp = getSharedPreferences("app_config", MODE_PRIVATE);
        long currentClearVersion = sp.getLong("clear_version", 0L);
        if (currentClearVersion != lastClearVersion) {
            if (deduplicationLogic != null) {
                deduplicationLogic.clear();
            }
            savedCount = 0;
            lastClearVersion = currentClearVersion;
        }

        isFunctionEnabled = sp.getBoolean("is_active", false);
        if (!isFunctionEnabled) return;

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastProcessTime < PROCESS_INTERVAL) {
            return;
        }
        lastProcessTime = currentTime;

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        String fallbackPkg = "";
        if (rootNode.getPackageName() != null) {
            fallbackPkg = rootNode.getPackageName().toString();
        } else if (event != null && event.getPackageName() != null) {
            fallbackPkg = event.getPackageName().toString();
        }

        try {
            traverseNode(rootNode, fallbackPkg);
        } finally {
            try {
                rootNode.recycle();
            } catch (Exception ignored) {
            }
        }
    }

    private void traverseNode(AccessibilityNodeInfo node, String fallbackPkg) {
        if (node == null) return;

        if (node.getText() != null && node.getText().length() > 0) {
            String content = node.getText().toString();
            String viewId = node.getViewIdResourceName();
            String className = node.getClassName() != null ? node.getClassName().toString() : "";
            String packageName = node.getPackageName() != null ? node.getPackageName().toString() : fallbackPkg;
            processAndSave(content, viewId, packageName, className);
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try {
                    traverseNode(child, fallbackPkg);
                } finally {
                    try {
                        child.recycle();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private void processAndSave(String content, String viewId, String packageName, String className) {
        if (content.trim().isEmpty()) return;

        if ("com.screen.textgrabber".equals(packageName)) {
            return;
        }

        if (deduplicationLogic == null) {
            deduplicationLogic = new DeduplicationLogic();
        }
        if (dbManager == null) {
            dbManager = new DBManager(this);
        }

        String uniqueKey = deduplicationLogic.generateKey(packageName, viewId, content);
        if (deduplicationLogic.isDuplicate(uniqueKey)) {
            return;
        }

        boolean inserted = dbManager.insertText(content, packageName, viewId, System.currentTimeMillis(), uniqueKey);
        if (inserted) {
            savedCount++;
            getSharedPreferences("app_config", MODE_PRIVATE).edit().putInt("saved_count", savedCount).apply();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (floatingController != null) {
            floatingController.onConfigurationChanged();
        }
    }

    @Override
    public void onInterrupt() {
        // 服务中断回调
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (floatingController != null) {
            floatingController.hide();
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingController != null) {
            floatingController.destroy();
            floatingController = null;
        }
        if (instance == this) {
            instance = null;
        }
    }
}
