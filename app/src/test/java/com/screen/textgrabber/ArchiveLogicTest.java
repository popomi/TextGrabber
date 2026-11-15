package com.screen.textgrabber;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
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
}
