package com.screen.textgrabber;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 当前应用页面级无障碍全量深抓与提示词拼接工具。
 * 移除了 isVisibleToUser() 限制，全量采集同包名窗口（含子窗口、未滚入屏幕节点、隐藏节点与虚拟节点），
 * 并在短时多轮采样后与后台页面级事件缓存融合去重。
 */
public final class ScreenTextExtractor {

    public static final String DEFAULT_PREFIX = "详细总结";
    public static final int LONG_LINE_DEDUP_THRESHOLD = 24;

    private static final int MAX_TREE_DEPTH = 120;
    private static final int MAX_VISITED_NODES = 25000;
    private static final long[] FOLLOWUP_PASS_DELAYS_MS = {260L, 280L, 320L, 320L, 320L};

    public static final class NodeLine {
        public final String viewId;
        public final String text;

        public NodeLine(String viewId, String text) {
            this.viewId = viewId != null ? viewId : "";
            this.text = text != null ? text : "";
        }
    }

    public static final class ExtractionResult {
        public final String pkgName;
        public final String rawText;
        public final int lineCount;
        public final int passCount;

        public ExtractionResult(String pkgName, String rawText, int lineCount, int passCount) {
            this.pkgName = pkgName != null ? pkgName : "";
            this.rawText = rawText != null ? rawText : "";
            this.lineCount = lineCount;
            this.passCount = passCount;
        }
    }

    private static final class WindowEntry {
        final int windowId;
        final int layer;
        final boolean activeOrFocused;
        final boolean isActive;
        final String pkgName;
        final AccessibilityNodeInfo root;

        WindowEntry(
                int windowId,
                int layer,
                boolean activeOrFocused,
                boolean isActive,
                String pkgName,
                AccessibilityNodeInfo root
        ) {
            this.windowId = windowId;
            this.layer = layer;
            this.activeOrFocused = activeOrFocused;
            this.isActive = isActive;
            this.pkgName = pkgName;
            this.root = root;
        }
    }

    private static final class SnapshotCapture {
        final String pkgName;
        final List<NodeLine> lines;

        SnapshotCapture(String pkgName, List<NodeLine> lines) {
            this.pkgName = pkgName != null ? pkgName : "";
            this.lines = lines != null ? lines : Collections.<NodeLine>emptyList();
        }
    }

    private ScreenTextExtractor() {
    }

    /**
     * 将前置提示词（默认"详细总结"）与提取出的屏幕正文拼接为最终复制内容。
     * 若提取正文为空则返回空字符串。
     */
    public static String buildPromptWithPrefix(String prefix, String extractedText) {
        if (extractedText == null) {
            return "";
        }
        String body = extractedText.trim();
        if (body.isEmpty()) {
            return "";
        }
        String cleanPrefix = prefix != null ? prefix.trim() : "";
        if (cleanPrefix.isEmpty()) {
            return body;
        }
        return cleanPrefix + "\n" + body;
    }

    /**
     * 过滤空行、相邻同文重复与长句嵌套重复（保留长度 <= 4 的短标签如选项字母/数字）
     */
    public static void appendCandidateLine(String rawCandidate, List<String> lines, Set<String> seenLongLines) {
        if (rawCandidate == null) {
            return;
        }
        String candidate = rawCandidate.trim();
        if (candidate.isEmpty()) {
            return;
        }
        if (!lines.isEmpty() && candidate.equals(lines.get(lines.size() - 1))) {
            return;
        }
        if (candidate.length() > 4 && seenLongLines != null && !seenLongLines.add(candidate)) {
            return;
        }
        lines.add(candidate);
    }

    /**
     * 单轮快照清洗：剔除空白、紧邻同文重复以及单轮内的超长（> 24 字符）重复正文；
     * 保留跨控件重复出现的短/中等标签（如多道题的 "A"、"B"、"正确答案：C"）。
     */
    public static List<NodeLine> dedupSingleSnapshot(List<NodeLine> rawItems) {
        if (rawItems == null || rawItems.isEmpty()) {
            return Collections.emptyList();
        }
        List<NodeLine> result = new ArrayList<>(rawItems.size());
        Set<String> seenLong = new HashSet<>();
        for (int i = 0; i < rawItems.size(); i++) {
            NodeLine item = rawItems.get(i);
            if (item == null || item.text == null) {
                continue;
            }
            String cleanText = item.text.trim();
            if (cleanText.isEmpty()) {
                continue;
            }
            if (!result.isEmpty() && cleanText.equals(result.get(result.size() - 1).text)) {
                continue;
            }
            if (cleanText.length() > LONG_LINE_DEDUP_THRESHOLD && !seenLong.add(cleanText)) {
                continue;
            }
            result.add(new NodeLine(item.viewId, cleanText));
        }
        return result;
    }

    /**
     * 按控件 viewId 与单快照出现序号精确合并多轮/缓存快照，既零重复保留多处短选项，又完整追加异步/隐藏新增节点。
     */
    public static List<String> mergeSnapshots(List<List<NodeLine>> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> merged = new ArrayList<>();
        Set<String> globalOccKeys = new HashSet<>();
        Set<String> globalLongTexts = new HashSet<>();

        for (int s = 0; s < snapshots.size(); s++) {
            List<NodeLine> raw = snapshots.get(s);
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            List<NodeLine> normalized = dedupSingleSnapshot(raw);
            if (normalized.isEmpty()) {
                continue;
            }
            Map<String, Integer> occByBaseKey = new HashMap<>();
            for (int i = 0; i < normalized.size(); i++) {
                NodeLine line = normalized.get(i);
                String normViewId = (line.viewId != null && !line.viewId.isEmpty()) ? line.viewId : "no_id";
                String baseKey = normViewId + "\0" + line.text;
                Integer prev = occByBaseKey.get(baseKey);
                int occ = prev != null ? prev + 1 : 1;
                occByBaseKey.put(baseKey, occ);

                String occKey = baseKey + "#" + occ;
                if (!globalOccKeys.add(occKey)) {
                    continue;
                }
                if (line.text.length() > LONG_LINE_DEDUP_THRESHOLD && !globalLongTexts.add(line.text)) {
                    continue;
                }
                if (!merged.isEmpty() && line.text.equals(merged.get(merged.size() - 1))) {
                    continue;
                }
                merged.add(line.text);
            }
        }
        return merged;
    }

    /**
     * 融合后台页面级事件缓存与本次点击后的多轮实时全树采样结果。
     * 若本页发生过滚动，优先将先前滑过顶部并被回收的缓存文本置于前方；否则以实时全树自上而下顺序为主，末尾补齐事件曾捕获的异步/瞬时隐藏节点。
     */
    public static List<String> combineBufferAndPasses(
            List<NodeLine> bufferedLines,
            boolean scrolledOnPage,
            List<List<NodeLine>> livePasses
    ) {
        boolean hasBuffer = bufferedLines != null && !bufferedLines.isEmpty();
        boolean hasPasses = livePasses != null && !livePasses.isEmpty();
        if (!hasBuffer && !hasPasses) {
            return Collections.emptyList();
        }
        if (!hasBuffer) {
            return mergeSnapshots(livePasses);
        }
        List<List<NodeLine>> combined = new ArrayList<>();
        if (scrolledOnPage) {
            combined.add(bufferedLines);
            if (hasPasses) {
                combined.addAll(livePasses);
            }
        } else {
            if (hasPasses) {
                combined.addAll(livePasses);
            }
            combined.add(bufferedLines);
        }
        return mergeSnapshots(combined);
    }

    /**
     * 向后兼容接口：执行全量深抓并返回纯文本正文（无内容返回 null）。
     */
    public static String extractCurrentScreenText(AccessibilityService service) {
        ExtractionResult result = extractFullAppPage(service);
        if (result == null || result.rawText.isEmpty()) {
            return null;
        }
        return result.rawText;
    }

    /**
     * 执行全应用页面级深抓：
     * 1. 立即无剪枝（不过滤 isVisibleToUser）遍历同应用全部窗口、子窗口与 getRootInActiveWindow（调用 refresh 刷新虚拟节点）；
     * 2. 短时多轮延迟采样捕获“过几秒才加载”的 WebView/异步节点（若连续两轮无新增且已有足够内容则提前在 ~0.54s 结束）；
     * 3. 与 MyAccessibilityService 后台维护的页面级事件缓存融合去重。
     */
    public static ExtractionResult extractFullAppPage(AccessibilityService service) {
        if (service == null) {
            return new ExtractionResult("", "", 0, 0);
        }
        String ownPkg = service.getPackageName() != null ? service.getPackageName() : PageTextBuffer.OWN_PACKAGE_FALLBACK;
        PageTextBuffer buffer = (service instanceof MyAccessibilityService)
                ? ((MyAccessibilityService) service).getPageTextBuffer()
                : null;
        String preferredExternalPkg = buffer != null ? buffer.getLastForegroundExternalPkg() : "";

        List<List<NodeLine>> livePasses = new ArrayList<>();
        String resolvedPkg = "";

        SnapshotCapture pass0 = captureTargetAppSnapshot(service, ownPkg, preferredExternalPkg);
        if (!pass0.pkgName.isEmpty()) {
            resolvedPkg = pass0.pkgName;
        }
        if (!pass0.lines.isEmpty()) {
            livePasses.add(pass0.lines);
        }

        int prevMergedSize = mergeSnapshots(livePasses).size();
        int stableRounds = 0;
        int passCount = 1;

        for (int i = 0; i < FOLLOWUP_PASS_DELAYS_MS.length; i++) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            try {
                Thread.sleep(FOLLOWUP_PASS_DELAYS_MS[i]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            String targetHint = !resolvedPkg.isEmpty() ? resolvedPkg : preferredExternalPkg;
            SnapshotCapture nextPass = captureTargetAppSnapshot(service, ownPkg, targetHint);
            passCount++;
            if (resolvedPkg.isEmpty() && !nextPass.pkgName.isEmpty()) {
                resolvedPkg = nextPass.pkgName;
            }
            if (!nextPass.lines.isEmpty()) {
                livePasses.add(nextPass.lines);
                int newMergedSize = mergeSnapshots(livePasses).size();
                if (newMergedSize > prevMergedSize) {
                    prevMergedSize = newMergedSize;
                    stableRounds = 0;
                } else {
                    stableRounds++;
                }
            } else {
                stableRounds++;
            }

            if (i >= 1 && stableRounds >= 2 && prevMergedSize >= 5) {
                break;
            }
        }

        String targetKeyPkg = !resolvedPkg.isEmpty() ? resolvedPkg : preferredExternalPkg;
        PageTextBuffer.PageSnapshot buffered = (buffer != null && !targetKeyPkg.isEmpty())
                ? buffer.getSnapshot(targetKeyPkg)
                : null;

        List<String> finalLines = combineBufferAndPasses(
                buffered != null ? buffered.lines : null,
                buffered != null && buffered.scrolledOnPage,
                livePasses
        );

        if (buffer != null && !resolvedPkg.isEmpty() && !livePasses.isEmpty()) {
            buffer.recordSnapshot(resolvedPkg, livePasses.get(livePasses.size() - 1), true, System.currentTimeMillis());
        }

        String rawText = joinLines(finalLines, "\n").trim();
        return new ExtractionResult(targetKeyPkg, rawText, finalLines.size(), passCount);
    }

    /**
     * 无剪枝遍历指定根节点下的全部子孙节点，收集完整文本（含不可见、屏幕外及隐藏节点）。
     */
    public static List<NodeLine> collectNodesFromRoot(AccessibilityNodeInfo root, String ownPkg) {
        if (root == null) {
            return Collections.emptyList();
        }
        List<NodeLine> out = new ArrayList<>();
        int[] visitedCounter = new int[]{0};
        collectNodesRecursive(root, ownPkg, out, 0, visitedCounter);
        return out;
    }

    private static void collectNodesRecursive(
            AccessibilityNodeInfo node,
            String ownPkg,
            List<NodeLine> out,
            int depth,
            int[] visitedCounter
    ) {
        if (node == null || depth > MAX_TREE_DEPTH || visitedCounter[0] >= MAX_VISITED_NODES) {
            return;
        }
        visitedCounter[0]++;

        boolean skipSelfText = false;
        try {
            skipSelfText = node.isPassword();
        } catch (Exception ignored) {
        }

        if (!skipSelfText) {
            String viewId = "";
            try {
                String rawId = node.getViewIdResourceName();
                if (rawId != null) {
                    viewId = rawId;
                }
            } catch (Exception ignored) {
            }

            List<String> nodeTexts = extractNodeTexts(node);
            for (int i = 0; i < nodeTexts.size(); i++) {
                out.add(new NodeLine(viewId, nodeTexts.get(i)));
            }
        }

        int childCount = 0;
        try {
            childCount = node.getChildCount();
        } catch (Exception ignored) {
        }
        for (int i = 0; i < childCount; i++) {
            if (visitedCounter[0] >= MAX_VISITED_NODES) {
                break;
            }
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Exception ignored) {
            }
            if (child != null) {
                try {
                    collectNodesRecursive(child, ownPkg, out, depth + 1, visitedCounter);
                } finally {
                    safeRecycle(child);
                }
            }
        }
    }

    private static List<String> extractNodeTexts(AccessibilityNodeInfo node) {
        List<String> out = new ArrayList<>(3);
        String text = "";
        String desc = "";
        try {
            text = safeTrim(node.getText());
        } catch (Exception ignored) {
        }
        try {
            desc = safeTrim(node.getContentDescription());
        } catch (Exception ignored) {
        }

        if (!text.isEmpty() && !desc.isEmpty()) {
            if (desc.equals(text) || text.contains(desc)) {
                out.add(text);
            } else if (desc.contains(text)) {
                out.add(desc);
            } else {
                out.add(text);
                out.add(desc);
            }
        } else if (!text.isEmpty()) {
            out.add(text);
        } else if (!desc.isEmpty()) {
            out.add(desc);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                addExtraIfUncovered(out, safeTrim(node.getPaneTitle()));
            } catch (Exception ignored) {
            }
            try {
                addExtraIfUncovered(out, safeTrim(node.getTooltipText()));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static void addExtraIfUncovered(List<String> out, String extra) {
        if (extra.isEmpty()) {
            return;
        }
        for (int i = 0; i < out.size(); i++) {
            String existing = out.get(i);
            if (existing.equals(extra) || existing.contains(extra)) {
                return;
            }
        }
        out.add(extra);
    }

    private static SnapshotCapture captureTargetAppSnapshot(
            AccessibilityService service,
            String ownPkg,
            String preferredExternalPkg
    ) {
        List<WindowEntry> entries = new ArrayList<>();
        List<AccessibilityWindowInfo> childWindowsToRecycle = new ArrayList<>();
        AccessibilityNodeInfo activeRoot = null;
        boolean activeRootUsable = false;
        String activeRootPkg = "";

        try {
            collectWindowEntries(service, entries, childWindowsToRecycle);

            try {
                activeRoot = service.getRootInActiveWindow();
            } catch (Exception ignored) {
            }
            if (activeRoot != null) {
                AccessibilityWindowInfo activeWin = null;
                try {
                    activeWin = activeRoot.getWindow();
                } catch (Exception ignored) {
                }
                boolean excludedType = activeWin != null && isExcludedWindowType(activeWin.getType());
                safeRecycleWindow(activeWin);

                if (!excludedType) {
                    activeRootUsable = true;
                    try {
                        CharSequence p = activeRoot.getPackageName();
                        activeRootPkg = p != null ? p.toString().trim() : "";
                    } catch (Exception ignored) {
                    }
                }
            }

            String targetPkg = pickTargetPackage(
                    entries, ownPkg, activeRootUsable ? activeRootPkg : "", preferredExternalPkg
            );
            if (targetPkg.isEmpty()) {
                return new SnapshotCapture("", Collections.<NodeLine>emptyList());
            }

            List<WindowEntry> matching = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                WindowEntry entry = entries.get(i);
                if (targetPkg.equals(entry.pkgName)) {
                    matching.add(entry);
                }
            }
            Collections.sort(matching, new Comparator<WindowEntry>() {
                @Override
                public int compare(WindowEntry a, WindowEntry b) {
                    return Integer.compare(a.layer, b.layer);
                }
            });

            List<NodeLine> aggregated = new ArrayList<>();
            Set<Integer> scannedWindowIds = new HashSet<>();
            for (int i = 0; i < matching.size(); i++) {
                WindowEntry entry = matching.get(i);
                try {
                    entry.root.refresh();
                } catch (Exception ignored) {
                }
                List<NodeLine> winLines = collectNodesFromRoot(entry.root, ownPkg);
                if (!winLines.isEmpty()) {
                    aggregated.addAll(winLines);
                }
                scannedWindowIds.add(entry.windowId);
            }

            if (activeRootUsable && (targetPkg.equals(activeRootPkg) || aggregated.isEmpty())) {
                try {
                    activeRoot.refresh();
                } catch (Exception ignored) {
                }
                List<NodeLine> activeLines = collectNodesFromRoot(activeRoot, ownPkg);
                if (!activeLines.isEmpty()) {
                    int activeWid = -1;
                    try {
                        activeWid = activeRoot.getWindowId();
                    } catch (Exception ignored) {
                    }
                    if (scannedWindowIds.isEmpty() || !scannedWindowIds.contains(activeWid)) {
                        aggregated.addAll(activeLines);
                    } else {
                        mergeSupplementalLines(aggregated, activeLines);
                    }
                }
            }

            return new SnapshotCapture(targetPkg, aggregated);
        } finally {
            safeRecycle(activeRoot);
            for (int i = 0; i < entries.size(); i++) {
                safeRecycle(entries.get(i).root);
            }
            for (int i = 0; i < childWindowsToRecycle.size(); i++) {
                safeRecycleWindow(childWindowsToRecycle.get(i));
            }
        }
    }

    private static void collectWindowEntries(
            AccessibilityService service,
            List<WindowEntry> outEntries,
            List<AccessibilityWindowInfo> childWindowsToRecycle
    ) {
        List<AccessibilityWindowInfo> topWindows = null;
        try {
            topWindows = service.getWindows();
        } catch (Exception ignored) {
        }
        if (topWindows == null || topWindows.isEmpty()) {
            return;
        }

        Deque<AccessibilityWindowInfo> queue = new ArrayDeque<>();
        Set<Integer> seenIds = new HashSet<>();
        for (int i = 0; i < topWindows.size(); i++) {
            AccessibilityWindowInfo w = topWindows.get(i);
            if (w != null) {
                queue.add(w);
            }
        }

        while (!queue.isEmpty()) {
            AccessibilityWindowInfo w = queue.poll();
            if (w == null) {
                continue;
            }
            int childCount = 0;
            try {
                childCount = w.getChildCount();
            } catch (Exception ignored) {
            }
            for (int i = 0; i < childCount; i++) {
                AccessibilityWindowInfo child = null;
                try {
                    child = w.getChild(i);
                } catch (Exception ignored) {
                }
                if (child != null) {
                    childWindowsToRecycle.add(child);
                    queue.add(child);
                }
            }

            int wid = -1;
            int type = -1;
            try {
                wid = w.getId();
                type = w.getType();
            } catch (Exception ignored) {
            }
            if (wid != -1 && !seenIds.add(wid)) {
                continue;
            }
            if (isExcludedWindowType(type)) {
                continue;
            }

            AccessibilityNodeInfo root = null;
            try {
                root = w.getRoot();
            } catch (Exception ignored) {
            }
            if (root == null) {
                continue;
            }

            String pkg = "";
            try {
                CharSequence rawPkg = root.getPackageName();
                if (rawPkg != null) {
                    pkg = rawPkg.toString().trim();
                }
            } catch (Exception ignored) {
            }
            if (pkg.isEmpty() || "com.android.systemui".equals(pkg)) {
                safeRecycle(root);
                continue;
            }

            boolean isActive = false;
            boolean isFocused = false;
            int layer = 0;
            try {
                isActive = w.isActive();
                isFocused = w.isFocused();
                layer = w.getLayer();
            } catch (Exception ignored) {
            }

            outEntries.add(new WindowEntry(wid, layer, isActive || isFocused, isActive, pkg, root));
        }
    }

    private static String pickTargetPackage(
            List<WindowEntry> entries,
            String ownPkg,
            String activeRootPkg,
            String preferredExternalPkg
    ) {
        WindowEntry bestActiveExternal = null;
        for (int i = 0; i < entries.size(); i++) {
            WindowEntry e = entries.get(i);
            if (e.activeOrFocused && !PageTextBuffer.isIgnoredPackage(e.pkgName, ownPkg)) {
                if (bestActiveExternal == null
                        || (e.isActive && !bestActiveExternal.isActive)
                        || (e.isActive == bestActiveExternal.isActive && e.layer > bestActiveExternal.layer)) {
                    bestActiveExternal = e;
                }
            }
        }
        if (bestActiveExternal != null) {
            return bestActiveExternal.pkgName;
        }

        if (!PageTextBuffer.isIgnoredPackage(activeRootPkg, ownPkg)) {
            return activeRootPkg;
        }

        WindowEntry preferredEntry = null;
        WindowEntry highestExternal = null;
        for (int i = 0; i < entries.size(); i++) {
            WindowEntry e = entries.get(i);
            if (PageTextBuffer.isIgnoredPackage(e.pkgName, ownPkg)) {
                continue;
            }
            if (preferredExternalPkg != null && preferredExternalPkg.equals(e.pkgName)) {
                if (preferredEntry == null || e.layer > preferredEntry.layer) {
                    preferredEntry = e;
                }
            }
            if (highestExternal == null || e.layer > highestExternal.layer) {
                highestExternal = e;
            }
        }
        if (preferredEntry != null) {
            return preferredEntry.pkgName;
        }
        if (highestExternal != null) {
            return highestExternal.pkgName;
        }

        if (ownPkg != null && !ownPkg.isEmpty()) {
            if (ownPkg.equals(activeRootPkg)) {
                return ownPkg;
            }
            for (int i = 0; i < entries.size(); i++) {
                if (ownPkg.equals(entries.get(i).pkgName)) {
                    return ownPkg;
                }
            }
        }
        return "";
    }

    private static void mergeSupplementalLines(List<NodeLine> primary, List<NodeLine> supplemental) {
        List<NodeLine> normPrimary = dedupSingleSnapshot(primary);
        Set<String> existingOcc = new HashSet<>();
        Set<String> existingLong = new HashSet<>();
        Map<String, Integer> primaryCounts = new HashMap<>();
        for (int i = 0; i < normPrimary.size(); i++) {
            NodeLine line = normPrimary.get(i);
            String normId = !line.viewId.isEmpty() ? line.viewId : "no_id";
            String base = normId + "\0" + line.text;
            Integer prev = primaryCounts.get(base);
            int occ = prev != null ? prev + 1 : 1;
            primaryCounts.put(base, occ);
            existingOcc.add(base + "#" + occ);
            if (line.text.length() > LONG_LINE_DEDUP_THRESHOLD) {
                existingLong.add(line.text);
            }
        }

        List<NodeLine> normSupp = dedupSingleSnapshot(supplemental);
        Map<String, Integer> suppCounts = new HashMap<>();
        for (int i = 0; i < normSupp.size(); i++) {
            NodeLine line = normSupp.get(i);
            String normId = !line.viewId.isEmpty() ? line.viewId : "no_id";
            String base = normId + "\0" + line.text;
            Integer prev = suppCounts.get(base);
            int occ = prev != null ? prev + 1 : 1;
            suppCounts.put(base, occ);
            if (!existingOcc.add(base + "#" + occ)) {
                continue;
            }
            if (line.text.length() > LONG_LINE_DEDUP_THRESHOLD && !existingLong.add(line.text)) {
                continue;
            }
            primary.add(line);
        }
    }

    public static boolean isExcludedWindowType(int windowType) {
        return windowType == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                || windowType == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                || windowType == AccessibilityWindowInfo.TYPE_SYSTEM
                || windowType == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER;
    }

    private static String safeTrim(CharSequence cs) {
        return cs != null ? cs.toString().trim() : "";
    }

    private static String joinLines(List<String> items, String delimiter) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(delimiter);
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static void safeRecycle(AccessibilityNodeInfo node) {
        if (node != null) {
            try {
                node.recycle();
            } catch (Exception ignored) {
            }
        }
    }

    private static void safeRecycleWindow(AccessibilityWindowInfo window) {
        if (window != null) {
            try {
                window.recycle();
            } catch (Exception ignored) {
            }
        }
    }
}
