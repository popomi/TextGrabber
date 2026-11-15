package com.screen.textgrabber;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 抓包聚合与存档格式化逻辑单元测试
 */
public class ArchiveLogicTest {

    private final ArchiveFormatter.AppLabelResolver testResolver = new ArchiveFormatter.AppLabelResolver() {
        @Override
        public String resolveLabel(String pkgName) {
            if ("com.tencent.mm".equals(pkgName)) {
                return "微信 (com.tencent.mm)";
            }
            if ("com.example.browser".equals(pkgName)) {
                return "浏览器 (com.example.browser)";
            }
            return pkgName != null && !pkgName.isEmpty() ? pkgName : "未知包名";
        }
    };

    @Test
    public void buildPackets_groupsByPackageAndWindow_andPreservesInnerOrder() {
        CaptureRecord r1 = new CaptureRecord(1, "第一行标题", "com.tencent.mm", "com.tencent.mm:id/title", 1000L);
        CaptureRecord r2 = new CaptureRecord(2, "第二行正文", "com.tencent.mm", "com.tencent.mm:id/body", 1500L);
        CaptureRecord r3 = new CaptureRecord(3, "不同包名记录", "com.example.browser", "com.example.browser:id/url", 1800L);
        CaptureRecord r4 = new CaptureRecord(4, "超时新包记录", "com.example.browser", "com.example.browser:id/text", 5000L);

        // 乱序传入，验证能先按时间升序聚合
        List<CaptureRecord> input = Arrays.asList(r4, r2, r3, r1);

        List<CapturePacket> ascPackets = CapturePacket.buildPackets(input, false, 2000L);
        assertEquals(3, ascPackets.size());
        assertEquals(1, ascPackets.get(0).packetNumber);
        assertEquals("com.tencent.mm", ascPackets.get(0).pkgName);
        assertEquals(2, ascPackets.get(0).records.size());
        assertEquals("第一行标题\n第二行正文", ascPackets.get(0).getJoinedContent());

        assertEquals(2, ascPackets.get(1).packetNumber);
        assertEquals("com.example.browser", ascPackets.get(1).pkgName);
        assertEquals(1, ascPackets.get(1).records.size());

        assertEquals(3, ascPackets.get(2).packetNumber);
        assertEquals("超时新包记录", ascPackets.get(2).getJoinedContent());

        // 最新优先：外层反转，但包内依然保持自然正序
        List<CapturePacket> descPackets = CapturePacket.buildPackets(input, true, 2000L);
        assertEquals(3, descPackets.size());
        assertEquals(3, descPackets.get(0).packetNumber);
        assertEquals(1, descPackets.get(2).packetNumber);
        assertEquals("第一行标题\n第二行正文", descPackets.get(2).getJoinedContent());
    }

    @Test
    public void formatFullArchive_preservesLongTextWithoutTruncation_acrossAllModes() {
        StringBuilder longSb = new StringBuilder();
        for (int i = 0; i < 260; i++) {
            longSb.append('字');
        }
        String longText = longSb.toString();

        CaptureRecord r1 = new CaptureRecord(10, longText, "com.tencent.mm", "com.tencent.mm:id/msg", 1700000001000L);
        CaptureRecord r2 = new CaptureRecord(11, "另一条短文本", "com.tencent.mm", "", 1700000001500L);
        List<CaptureRecord> records = Arrays.asList(r1, r2);

        String packetOut = ArchiveFormatter.formatFullArchive(
                records, ArchiveFormatter.MODE_TIMELINE_PACKETS, false, testResolver, false
        );
        assertTrue(packetOut.contains("抓包 #1"));
        assertTrue(packetOut.contains("微信 (com.tencent.mm)"));
        assertTrue(packetOut.contains(longText));

        String dbOut = ArchiveFormatter.formatFullArchive(
                records, ArchiveFormatter.MODE_DB_RECORDS, true, testResolver, false
        );
        assertTrue(dbOut.contains("[#11]"));
        assertTrue(dbOut.contains("[#10]"));
        assertTrue(dbOut.contains("com.tencent.mm:id/msg"));
        assertTrue(dbOut.contains(longText));

        String plainOut = ArchiveFormatter.formatFullArchive(
                records, ArchiveFormatter.MODE_PLAIN_TEXT, false, testResolver, false
        );
        assertEquals(longText + "\n另一条短文本", plainOut);
    }

    @Test
    public void formatCsv_includesUtf8BomAndEscapesQuotesAndNewlines() {
        CaptureRecord r = new CaptureRecord(
                7,
                "第一行 \"引用\"\n第二行",
                "com.tencent.mm",
                "com.tencent.mm:id/content",
                1700000000123L
        );
        String csv = ArchiveFormatter.formatCsv(Collections.singletonList(r), testResolver);

        assertTrue(csv.startsWith("\uFEFFID,抓取时间,时间戳ms,应用名称,无障碍包名,控件ID,文本内容\n"));
        assertTrue(csv.contains("\"微信 (com.tencent.mm)\""));
        assertTrue(csv.contains("\"com.tencent.mm\""));
        assertTrue(csv.contains("\"第一行 \"\"引用\"\"\n第二行\""));
    }

    @Test
    public void screenTextExtractor_buildPromptWithPrefix_handlesDefaultAndBlank() {
        String extracted = "  标题文本\n第一段内容\n第二段内容  ";
        String withDefault = ScreenTextExtractor.buildPromptWithPrefix(
                ScreenTextExtractor.DEFAULT_PREFIX,
                extracted
        );
        assertEquals("详细总结\n标题文本\n第一段内容\n第二段内容", withDefault);
        assertTrue(withDefault.startsWith("详细总结\n"));

        String noPrefix = ScreenTextExtractor.buildPromptWithPrefix("   ", extracted);
        assertEquals("标题文本\n第一段内容\n第二段内容", noPrefix);

        assertEquals("", ScreenTextExtractor.buildPromptWithPrefix("详细总结", "   \n  "));
        assertEquals("", ScreenTextExtractor.buildPromptWithPrefix("详细总结", null));
    }

    @Test
    public void screenTextExtractor_appendCandidateLine_dedupsLongLinesAndKeepsShortLabels() {
        List<String> lines = new ArrayList<>();
        Set<String> seenLong = new HashSet<>();

        ScreenTextExtractor.appendCandidateLine("这是一段较长的重复段落文字", lines, seenLong);
        ScreenTextExtractor.appendCandidateLine("这是一段较长的重复段落文字", lines, seenLong);
        ScreenTextExtractor.appendCandidateLine("A", lines, seenLong);
        ScreenTextExtractor.appendCandidateLine("B", lines, seenLong);
        ScreenTextExtractor.appendCandidateLine("这是一段较长的重复段落文字", lines, seenLong);
        ScreenTextExtractor.appendCandidateLine("A", lines, seenLong);
        ScreenTextExtractor.appendCandidateLine("   ", lines, seenLong);

        assertEquals(Arrays.asList("这是一段较长的重复段落文字", "A", "B", "A"), lines);
    }

    @Test
    public void dedupSingleSnapshot_preservesRepeatedOptionsAndMediumAnswers_whileCollapsingAdjacentAndLongDuplicates() {
        String longQ1 = "第一题题干：请从以下四个选项中选择完全正确的一组答案（超过二十四个字符的长段落）";
        String longQ2 = "第二题题干：这是第二道不同的题目正文内容描述，同样超过二十四个字符用于验证长段去重";

        List<ScreenTextExtractor.NodeLine> raw = Arrays.asList(
                new ScreenTextExtractor.NodeLine("id/q1", longQ1),
                new ScreenTextExtractor.NodeLine("id/q1_dup", longQ1),
                new ScreenTextExtractor.NodeLine("id/opt", "A"),
                new ScreenTextExtractor.NodeLine("id/opt", "B"),
                new ScreenTextExtractor.NodeLine("id/ans", "正确答案：C"),
                new ScreenTextExtractor.NodeLine("id/q2", longQ2),
                new ScreenTextExtractor.NodeLine("id/opt", "A"),
                new ScreenTextExtractor.NodeLine("id/opt", "B"),
                new ScreenTextExtractor.NodeLine("id/ans", "正确答案：C"),
                new ScreenTextExtractor.NodeLine("id/card_repeat", longQ1),
                new ScreenTextExtractor.NodeLine("", "   ")
        );

        List<ScreenTextExtractor.NodeLine> cleaned = ScreenTextExtractor.dedupSingleSnapshot(raw);
        List<String> texts = new ArrayList<>();
        for (ScreenTextExtractor.NodeLine line : cleaned) {
            texts.add(line.text);
        }

        assertEquals(
                Arrays.asList(longQ1, "A", "B", "正确答案：C", longQ2, "A", "B", "正确答案：C"),
                texts
        );
    }

    @Test
    public void mergeSnapshots_combinesMultiPassDelayedHiddenNodes_withoutDuplicatingExistingLines() {
        String offscreenLong = "屏幕外未滚动到底部的长篇参考文献说明段落，长度超过二十四个字符";

        List<ScreenTextExtractor.NodeLine> pass0 = Arrays.asList(
                new ScreenTextExtractor.NodeLine("id/title", "当前页面标题"),
                new ScreenTextExtractor.NodeLine("id/opt", "A"),
                new ScreenTextExtractor.NodeLine("id/opt", "B"),
                new ScreenTextExtractor.NodeLine("id/opt", "A")
        );
        List<ScreenTextExtractor.NodeLine> pass1 = Arrays.asList(
                new ScreenTextExtractor.NodeLine("id/title", "当前页面标题"),
                new ScreenTextExtractor.NodeLine("id/opt", "A"),
                new ScreenTextExtractor.NodeLine("id/opt", "B"),
                new ScreenTextExtractor.NodeLine("id/opt", "A"),
                new ScreenTextExtractor.NodeLine("id/hidden_parse", "异步加载隐藏解析内容"),
                new ScreenTextExtractor.NodeLine("id/offscreen_footer", offscreenLong)
        );
        List<ScreenTextExtractor.NodeLine> pass2 = Arrays.asList(
                new ScreenTextExtractor.NodeLine("id/title", "当前页面标题"),
                new ScreenTextExtractor.NodeLine("id/opt", "A"),
                new ScreenTextExtractor.NodeLine("id/opt", "B"),
                new ScreenTextExtractor.NodeLine("id/opt", "A"),
                new ScreenTextExtractor.NodeLine("id/hidden_parse", "异步加载隐藏解析内容"),
                new ScreenTextExtractor.NodeLine("id/offscreen_footer", offscreenLong),
                new ScreenTextExtractor.NodeLine("id/delayed_note", "延迟才出现的第二段隐藏补充说明")
        );

        List<String> merged = ScreenTextExtractor.mergeSnapshots(Arrays.asList(pass0, pass1, pass2));
        assertEquals(
                Arrays.asList(
                        "当前页面标题",
                        "A",
                        "B",
                        "A",
                        "异步加载隐藏解析内容",
                        offscreenLong,
                        "延迟才出现的第二段隐藏补充说明"
                ),
                merged
        );
    }

    @Test
    public void pageTextBuffer_accumulatesPageAndScroll_andResetsOnActivityOrSpaChange() {
        PageTextBuffer buffer = new PageTextBuffer("com.screen.textgrabber");

        // 忽略本应用、系统UI与输入法
        buffer.onWindowStateChanged("com.screen.textgrabber", "com.screen.textgrabber.MainActivity", 500L);
        buffer.recordSnapshot(
                "com.google.android.inputmethod.latin",
                Collections.singletonList(new ScreenTextExtractor.NodeLine("ime", "键盘按键")),
                true,
                600L
        );
        assertEquals("", buffer.getLastForegroundExternalPkg());

        // 进入外部页面并累加内容
        String quizPkg = "com.example.quiz";
        buffer.onWindowStateChanged(quizPkg, "com.example.quiz.QuestionActivity", 1000L);
        buffer.recordSnapshot(
                quizPkg,
                Arrays.asList(
                        new ScreenTextExtractor.NodeLine("q1", "题目1"),
                        new ScreenTextExtractor.NodeLine("opt", "选项A")
                ),
                true,
                1100L
        );

        // Toast 和 AlertDialog 不得清空当前页缓存
        buffer.onWindowStateChanged(quizPkg, "android.widget.Toast$TN", 1300L);
        buffer.onWindowStateChanged(quizPkg, "androidx.appcompat.app.AlertDialog", 1400L);
        buffer.recordSnapshot(
                quizPkg,
                Collections.singletonList(new ScreenTextExtractor.NodeLine("hidden", "弹窗后异步加载的解析")),
                false,
                1500L
        );
        buffer.onViewScrolled(quizPkg, 2000L);

        PageTextBuffer.PageSnapshot snap1 = buffer.getSnapshot(quizPkg);
        assertNotNull(snap1);
        assertTrue(snap1.scrolledOnPage);
        assertEquals(3, snap1.lines.size());
        assertEquals(quizPkg, buffer.getLastForegroundExternalPkg());

        // 切换到新 Activity 时自动重置
        buffer.onWindowStateChanged(quizPkg, "com.example.quiz.DetailActivity", 3000L);
        buffer.recordSnapshot(
                quizPkg,
                Collections.singletonList(new ScreenTextExtractor.NodeLine("detail", "详情页内容")),
                true,
                3100L
        );
        PageTextBuffer.PageSnapshot snap2 = buffer.getSnapshot(quizPkg);
        assertNotNull(snap2);
        assertFalse(snap2.scrolledOnPage);
        assertEquals(1, snap2.lines.size());
        assertEquals("详情页内容", snap2.lines.get(0).text);

        // 验证同 Activity 下未滚动发生整屏 SPA 路由切换时的自动重置
        String spaPkg = "com.example.spa";
        buffer.onWindowStateChanged(spaPkg, "com.example.spa.MainActivity", 4000L);
        List<ScreenTextExtractor.NodeLine> oldSpa = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            oldSpa.add(new ScreenTextExtractor.NodeLine("old_" + i, "SPA旧页行" + i));
        }
        buffer.recordSnapshot(spaPkg, oldSpa, true, 4100L);

        List<ScreenTextExtractor.NodeLine> newSpa = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            newSpa.add(new ScreenTextExtractor.NodeLine("new_" + i, "SPA新页项" + i));
        }
        buffer.recordSnapshot(spaPkg, newSpa, true, 7000L);

        PageTextBuffer.PageSnapshot spaSnap = buffer.getSnapshot(spaPkg);
        assertNotNull(spaSnap);
        assertEquals(12, spaSnap.lines.size());
        assertEquals("SPA新页项0", spaSnap.lines.get(0).text);

        buffer.clear();
        assertNull(buffer.getSnapshot(spaPkg));
        assertEquals("", buffer.getLastForegroundExternalPkg());
    }

    @Test
    public void mergePageBufferAndLivePasses_ordersCorrectlyAndPrependsSummaryPrefix() {
        // 情况 A：发生过滚动，先前滚出屏幕顶部的缓存行排在最前
        List<ScreenTextExtractor.NodeLine> scrolledBuffer = Arrays.asList(
                new ScreenTextExtractor.NodeLine("top1", "已滚出屏幕顶部的第1段"),
                new ScreenTextExtractor.NodeLine("mid", "中间当前可见第2段")
        );
        List<List<ScreenTextExtractor.NodeLine>> scrolledLive = Collections.singletonList(
                Arrays.asList(
                        new ScreenTextExtractor.NodeLine("mid", "中间当前可见第2段"),
                        new ScreenTextExtractor.NodeLine("btm", "屏幕外底部的隐藏第3段")
                )
        );

        List<String> scrolledMerged = ScreenTextExtractor.combineBufferAndPasses(scrolledBuffer, true, scrolledLive);
        assertEquals(
                Arrays.asList("已滚出屏幕顶部的第1段", "中间当前可见第2段", "屏幕外底部的隐藏第3段"),
                scrolledMerged
        );

        String finalWithPrefix = ScreenTextExtractor.buildPromptWithPrefix(
                ScreenTextExtractor.DEFAULT_PREFIX,
                "已滚出屏幕顶部的第1段\n中间当前可见第2段\n屏幕外底部的隐藏第3段"
        );
        assertEquals(
                "详细总结\n已滚出屏幕顶部的第1段\n中间当前可见第2段\n屏幕外底部的隐藏第3段",
                finalWithPrefix
        );

        // 情况 B：未滚动页，以当前实时全树为主顺序，事件流独有隐藏节点补在末尾
        List<List<ScreenTextExtractor.NodeLine>> staticLive = Collections.singletonList(
                Arrays.asList(
                        new ScreenTextExtractor.NodeLine("top", "页面顶部标题"),
                        new ScreenTextExtractor.NodeLine("mid", "主体段落内容")
                )
        );
        List<ScreenTextExtractor.NodeLine> staticBuffer = Arrays.asList(
                new ScreenTextExtractor.NodeLine("mid", "主体段落内容"),
                new ScreenTextExtractor.NodeLine("async", "事件流曾经捕获的隐藏补充文字")
        );

        List<String> staticMerged = ScreenTextExtractor.combineBufferAndPasses(staticBuffer, false, staticLive);
        assertEquals(
                Arrays.asList("页面顶部标题", "主体段落内容", "事件流曾经捕获的隐藏补充文字"),
                staticMerged
        );
    }
}
