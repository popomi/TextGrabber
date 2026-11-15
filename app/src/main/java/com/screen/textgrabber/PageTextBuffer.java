package com.screen.textgrabber;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 当前应用页面级无障碍事件缓存。
 * 在后台随无障碍事件增量收集当前页面的可见、隐藏及滚动节点，并在切页或 SPA 整屏换页时自动重置，
 * 使悬浮窗点击“提取”时能够获得与“实时抓包”同等的页面全量文本。
 */
public final class PageTextBuffer {

    public static final String OWN_PACKAGE_FALLBACK = "com.screen.textgrabber";
    private static final int MAX_APPS = 3;
    private static final int MAX_LINES_PER_PAGE = 5000;
    private static final long SPA_SCROLL_GRACE_MS = 2500L;
    private static final int SPA_MIN_NODE_COUNT = 10;
    private static final double SPA_OVERLAP_THRESHOLD = 0.12d;

    public static final class PageSnapshot {
        public final String pkgName;
        public final List<ScreenTextExtractor.NodeLine> lines;
        public final boolean scrolledOnPage;
        public final long lastUpdatedMs;

        public PageSnapshot(
                String pkgName,
                List<ScreenTextExtractor.NodeLine> lines,
                boolean scrolledOnPage,
                long lastUpdatedMs
        ) {
            this.pkgName = pkgName != null ? pkgName : "";
            this.lines = lines != null
                    ? Collections.unmodifiableList(new ArrayList<>(lines))
                    : Collections.<ScreenTextExtractor.NodeLine>emptyList();
            this.scrolledOnPage = scrolledOnPage;
            this.lastUpdatedMs = lastUpdatedMs;
        }
    }

    private static final class AppPageState {
        String activeWindowClass;
        boolean scrolledOnPage = false;
        long lastScrollTimeMs = 0L;
        long lastUpdatedMs = 0L;
        final List<ScreenTextExtractor.NodeLine> accumulatedLines = new ArrayList<>();
        final Set<String> seenOccKeys = new HashSet<>();
        final Set<String> seenLongTexts = new HashSet<>();
        Set<String> lastFullRootTexts = new HashSet<>();

        void resetPageContent(String newWindowClass) {
            activeWindowClass = newWindowClass;
            scrolledOnPage = false;
            lastScrollTimeMs = 0L;
            accumulatedLines.clear();
            seenOccKeys.clear();
            seenLongTexts.clear();
            lastFullRootTexts = new HashSet<>();
        }
    }

    private final Map<String, AppPageState> appStates = new LinkedHashMap<String, AppPageState>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, AppPageState> eldest) {
            return size() > MAX_APPS;
        }
    };

    private volatile String ownPackageName = OWN_PACKAGE_FALLBACK;
    private String lastForegroundExternalPkg = "";

    public PageTextBuffer() {
    }

    public PageTextBuffer(String ownPackageName) {
        setOwnPackageName(ownPackageName);
    }

    public synchronized void setOwnPackageName(String ownPackageName) {
        if (ownPackageName != null && !ownPackageName.trim().isEmpty()) {
            this.ownPackageName = ownPackageName.trim();
        }
    }

    /**
     * 判断是否为应忽略的系统栏、输入法或本应用自身包名。
     */
    public static boolean isIgnoredPackage(String pkg, String ownPkg) {
        if (pkg == null) {
            return true;
        }
        String trimmed = pkg.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        if (OWN_PACKAGE_FALLBACK.equals(trimmed)) {
            return true;
        }
        if (ownPkg != null && !ownPkg.trim().isEmpty() && ownPkg.trim().equals(trimmed)) {
            return true;
        }
        if ("com.android.systemui".equals(trimmed)) {
            return true;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.contains(".inputmethod")
                || lower.contains("inputmethod.")
                || "com.tencent.wetype".equals(lower)
                || "com.samsung.android.honeyboard".equals(lower);
    }

    /**
     * 判断窗口类名是否为当前页面上的临时浮层/弹窗（Toast/Dialog/Popup 等），此类浮层不触发切页清空。
     */
    public static boolean isTransientWindowClass(String className) {
        if (className == null) {
            return true;
        }
        String trimmed = className.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        if (trimmed.startsWith("android.widget.") || trimmed.startsWith("android.view.")) {
            return true;
        }
        return trimmed.contains("Toast")
                || trimmed.contains("Popup")
                || trimmed.contains("Menu")
                || trimmed.contains("DropDown")
                || trimmed.contains("Snackbar")
                || trimmed.contains("Tooltip")
                || trimmed.contains("Dialog")
                || trimmed.contains("BottomSheet")
                || trimmed.contains("InputMethod")
                || trimmed.contains("SoftInput");
    }

    public synchronized void onWindowStateChanged(String pkgName, String className, long nowMs) {
        if (isIgnoredPackage(pkgName, ownPackageName)) {
            return;
        }
        String cleanPkg = pkgName.trim();
        lastForegroundExternalPkg = cleanPkg;

        if (isTransientWindowClass(className)) {
            return;
        }
        String cleanClass = className.trim();
        AppPageState state = getOrCreateState(cleanPkg);
        if (state.activeWindowClass != null && !state.activeWindowClass.equals(cleanClass)) {
            state.resetPageContent(cleanClass);
        } else if (state.activeWindowClass == null) {
            state.activeWindowClass = cleanClass;
        }
        state.lastUpdatedMs = nowMs;
    }

    public synchronized void onViewScrolled(String pkgName, long nowMs) {
        if (isIgnoredPackage(pkgName, ownPackageName)) {
            return;
        }
        String cleanPkg = pkgName.trim();
        lastForegroundExternalPkg = cleanPkg;
        AppPageState state = getOrCreateState(cleanPkg);
        state.scrolledOnPage = true;
        state.lastScrollTimeMs = nowMs;
        state.lastUpdatedMs = nowMs;
    }

    public synchronized void recordSnapshot(
            String pkgName,
            List<ScreenTextExtractor.NodeLine> rawLines,
            boolean isFullWindowRoot,
            long nowMs
    ) {
        if (isIgnoredPackage(pkgName, ownPackageName) || rawLines == null || rawLines.isEmpty()) {
            return;
        }
        List<ScreenTextExtractor.NodeLine> normalized = ScreenTextExtractor.dedupSingleSnapshot(rawLines);
        if (normalized.isEmpty()) {
            return;
        }

        String cleanPkg = pkgName.trim();
        lastForegroundExternalPkg = cleanPkg;
        AppPageState state = getOrCreateState(cleanPkg);

        if (isFullWindowRoot) {
            Set<String> currSet = buildComparableTextSet(normalized);
            if ((nowMs - state.lastScrollTimeMs) > SPA_SCROLL_GRACE_MS
                    && state.lastFullRootTexts.size() >= SPA_MIN_NODE_COUNT
                    && currSet.size() >= SPA_MIN_NODE_COUNT) {
                double overlap = computeOverlapRatio(state.lastFullRootTexts, currSet);
                if (overlap < SPA_OVERLAP_THRESHOLD) {
                    state.resetPageContent(state.activeWindowClass);
                }
            }
            if (currSet.size() >= 4) {
                state.lastFullRootTexts = currSet;
            }
        }

        Map<String, Integer> snapshotCounts = new LinkedHashMap<>();
        for (ScreenTextExtractor.NodeLine line : normalized) {
            if (state.accumulatedLines.size() >= MAX_LINES_PER_PAGE) {
                break;
            }
            String normViewId = (line.viewId != null && !line.viewId.isEmpty()) ? line.viewId : "no_id";
            String baseKey = normViewId + "\0" + line.text;
            Integer prevCount = snapshotCounts.get(baseKey);
            int occInSnapshot = prevCount != null ? prevCount + 1 : 1;
            snapshotCounts.put(baseKey, occInSnapshot);

            String occKey = baseKey + "#" + occInSnapshot;
            if (!state.seenOccKeys.add(occKey)) {
                continue;
            }
            if (line.text.length() > ScreenTextExtractor.LONG_LINE_DEDUP_THRESHOLD
                    && !state.seenLongTexts.add(line.text)) {
                continue;
            }
            state.accumulatedLines.add(new ScreenTextExtractor.NodeLine(normViewId, line.text));
        }
        state.lastUpdatedMs = nowMs;
    }

    public synchronized PageSnapshot getSnapshot(String pkgName) {
        if (pkgName == null || pkgName.trim().isEmpty()) {
            return null;
        }
        AppPageState state = appStates.get(pkgName.trim());
        if (state == null || state.accumulatedLines.isEmpty()) {
            return null;
        }
        return new PageSnapshot(pkgName.trim(), state.accumulatedLines, state.scrolledOnPage, state.lastUpdatedMs);
    }

    public synchronized String getLastForegroundExternalPkg() {
        return lastForegroundExternalPkg;
    }

    public synchronized void clear() {
        appStates.clear();
        lastForegroundExternalPkg = "";
    }

    private AppPageState getOrCreateState(String cleanPkg) {
        AppPageState state = appStates.get(cleanPkg);
        if (state == null) {
            state = new AppPageState();
            appStates.put(cleanPkg, state);
        }
        return state;
    }

    private static Set<String> buildComparableTextSet(List<ScreenTextExtractor.NodeLine> lines) {
        Set<String> set = new HashSet<>();
        for (ScreenTextExtractor.NodeLine line : lines) {
            if (line.text != null && line.text.length() >= 2) {
                set.add(line.text);
            }
        }
        return set;
    }

    private static double computeOverlapRatio(Set<String> prev, Set<String> curr) {
        int minSize = Math.min(prev.size(), curr.size());
        if (minSize <= 0) {
            return 0.0d;
        }
        int shared = 0;
        Set<String> smaller = prev.size() <= curr.size() ? prev : curr;
        Set<String> larger = prev.size() <= curr.size() ? curr : prev;
        for (String s : smaller) {
            if (larger.contains(s)) {
                shared++;
            }
        }
        return shared / (double) minSize;
    }
}
