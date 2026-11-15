package com.screen.textgrabber;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 当前屏幕窗口无障碍文字提取与复制提示词拼接工具
 */
public final class ScreenTextExtractor {

    public static final String DEFAULT_PREFIX = "详细总结";

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
     * 提取当前屏幕应用窗口的全部可见无障碍文字。
     * 优先读取非本应用的活动应用窗口；若当前仅本应用在前台（例如主界面测试时），则回退提取本应用窗口，再兜底 getRootInActiveWindow。
     */
    public static String extractCurrentScreenText(AccessibilityService service) {
        if (service == null) {
            return null;
        }
        String ownPkg = service.getPackageName();

        List<AccessibilityWindowInfo> rawWindows = null;
        try {
            rawWindows = service.getWindows();
        } catch (Exception ignored) {
        }

        List<AccessibilityWindowInfo> appWindows = new ArrayList<>();
        if (rawWindows != null) {
            for (AccessibilityWindowInfo w : rawWindows) {
                if (w != null && w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    appWindows.add(w);
                }
            }
        }

        Collections.sort(appWindows, new Comparator<AccessibilityWindowInfo>() {
            @Override
            public int compare(AccessibilityWindowInfo a, AccessibilityWindowInfo b) {
                return Boolean.compare(b.isActive(), a.isActive());
            }
        });

        List<String> externalSegments = new ArrayList<>();
        List<String> ownSegments = new ArrayList<>();
        Set<String> seenExternal = new HashSet<>();
        Set<String> seenOwn = new HashSet<>();

        for (AccessibilityWindowInfo window : appWindows) {
            AccessibilityNodeInfo root = null;
            try {
                root = window.getRoot();
            } catch (Exception ignored) {
            }
            if (root == null) {
                continue;
            }
            try {
                String pkg = root.getPackageName() != null ? root.getPackageName().toString() : "";
                if ("com.android.systemui".equals(pkg)) {
                    continue;
                }
                boolean isOwn = ownPkg != null && ownPkg.equals(pkg);
                List<String> lines = extractLinesFromRoot(root, isOwn ? seenOwn : seenExternal);
                if (!lines.isEmpty()) {
                    (isOwn ? ownSegments : externalSegments).add(joinLines(lines, "\n"));
                }
            } finally {
                safeRecycle(root);
            }
        }

        if (!externalSegments.isEmpty()) {
            String result = joinLines(externalSegments, "\n\n").trim();
            if (!result.isEmpty()) {
                return result;
            }
        }

        // 兜底尝试 getRootInActiveWindow（防止部分 ROM 下 getWindows 漏掉当前活动窗口）
        AccessibilityNodeInfo activeRoot = null;
        try {
            activeRoot = service.getRootInActiveWindow();
        } catch (Exception ignored) {
        }
        if (activeRoot != null) {
            try {
                String pkg = activeRoot.getPackageName() != null ? activeRoot.getPackageName().toString() : "";
                boolean isOwn = ownPkg != null && ownPkg.equals(pkg);
                if (!"com.android.systemui".equals(pkg) && (!isOwn || ownSegments.isEmpty())) {
                    List<String> lines = extractLinesFromRoot(activeRoot, new HashSet<String>());
                    if (!lines.isEmpty()) {
                        String joined = joinLines(lines, "\n").trim();
                        if (!joined.isEmpty()) {
                            return joined;
                        }
                    }
                }
            } finally {
                safeRecycle(activeRoot);
            }
        }

        if (!ownSegments.isEmpty()) {
            String result = joinLines(ownSegments, "\n\n").trim();
            if (!result.isEmpty()) {
                return result;
            }
        }
        return null;
    }

    private static List<String> extractLinesFromRoot(AccessibilityNodeInfo root, Set<String> seenLongLines) {
        List<String> lines = new ArrayList<>();
        collectText(root, lines, seenLongLines, true);
        if (lines.isEmpty()) {
            collectText(root, lines, seenLongLines, false);
        }
        return lines;
    }

    private static void collectText(
            AccessibilityNodeInfo node,
            List<String> lines,
            Set<String> seenLongLines,
            boolean visibleOnly
    ) {
        if (node == null) {
            return;
        }
        if (visibleOnly && !node.isVisibleToUser()) {
            return;
        }
        if (node.isPassword()) {
            return;
        }

        String candidate = null;
        CharSequence nodeText = node.getText();
        if (nodeText != null && !nodeText.toString().trim().isEmpty()) {
            candidate = nodeText.toString();
        } else {
            CharSequence desc = node.getContentDescription();
            if (desc != null && desc.toString().trim().length() >= 2) {
                candidate = desc.toString();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                CharSequence paneTitle = node.getPaneTitle();
                if (paneTitle != null && paneTitle.toString().trim().length() >= 2) {
                    candidate = paneTitle.toString();
                }
            }
        }

        appendCandidateLine(candidate, lines, seenLongLines);

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try {
                    collectText(child, lines, seenLongLines, visibleOnly);
                } finally {
                    safeRecycle(child);
                }
            }
        }
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
}
