package com.screen.textgrabber;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 抓取存档文本、CSV 与复制内容格式化工具（纯 Java 逻辑，便于单元测试）
 */
public final class ArchiveFormatter {

    public static final int MODE_TIMELINE_PACKETS = 0;
    public static final int MODE_DB_RECORDS = 1;
    public static final int MODE_PLAIN_TEXT = 2;

    public interface AppLabelResolver {
        String resolveLabel(String pkgName);
    }

    private static final AppLabelResolver DEFAULT_RESOLVER = new AppLabelResolver() {
        @Override
        public String resolveLabel(String pkgName) {
            return (pkgName == null || pkgName.trim().isEmpty()) ? "未知包名" : pkgName;
        }
    };

    private ArchiveFormatter() {
    }

    public static String formatTime(long millis, boolean withMillis) {
        String pattern = withMillis ? "yyyy-MM-dd HH:mm:ss.SSS" : "yyyy-MM-dd HH:mm:ss";
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(millis));
    }

    public static String formatShortTime(long millis) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(millis));
    }

    private static AppLabelResolver safeResolver(AppLabelResolver resolver) {
        return resolver != null ? resolver : DEFAULT_RESOLVER;
    }

    public static String formatPacketForCopy(CapturePacket packet, AppLabelResolver resolver) {
        AppLabelResolver r = safeResolver(resolver);
        String pkgLabel = r.resolveLabel(packet.pkgName);
        StringBuilder sb = new StringBuilder();
        sb.append("=== [抓包 #").append(packet.packetNumber)
                .append(" | ").append(formatTime(packet.startTime, false))
                .append(" | ").append(pkgLabel)
                .append(" | 共").append(packet.records.size()).append("条] ===\n");
        sb.append(packet.getJoinedContent());
        return sb.toString();
    }

    public static String formatDbRecordForCopy(CaptureRecord record, AppLabelResolver resolver, boolean includeMetadata) {
        if (!includeMetadata) {
            return record.content;
        }
        AppLabelResolver r = safeResolver(resolver);
        String pkgLabel = r.resolveLabel(record.pkgName);
        String viewIdText = record.viewId.isEmpty() ? "无" : record.viewId;
        StringBuilder sb = new StringBuilder();
        sb.append("[#").append(record.id).append("] ")
                .append("[").append(formatTime(record.captureTime, true)).append("] ")
                .append("[包名: ").append(pkgLabel).append("] ")
                .append("[控件: ").append(viewIdText).append("]\n")
                .append(record.content);
        return sb.toString();
    }

    public static String getModeName(int mode) {
        if (mode == MODE_DB_RECORDS) {
            return "数据库无障碍包";
        }
        if (mode == MODE_PLAIN_TEXT) {
            return "纯文本全文";
        }
        return "按时间抓包";
    }

    public static String formatFullArchive(
            List<CaptureRecord> records,
            int mode,
            boolean newestFirst,
            AppLabelResolver resolver,
            boolean withExportHeader
    ) {
        AppLabelResolver r = safeResolver(resolver);
        List<CaptureRecord> safeList = records != null ? records : Collections.<CaptureRecord>emptyList();
        StringBuilder sb = new StringBuilder();

        if (withExportHeader) {
            sb.append("TextGrabber 抓包数据导出\n");
            sb.append("导出时间: ").append(formatTime(System.currentTimeMillis(), false)).append("\n");
            sb.append("视图模式: ").append(getModeName(mode)).append("\n");
            sb.append("排序方式: ").append(newestFirst ? "最新优先" : "时间正序").append("\n");
            sb.append("记录总数: ").append(safeList.size()).append(" 条\n");
            sb.append("==================================================\n\n");
        }

        if (safeList.isEmpty()) {
            sb.append("(暂无数据)");
            return sb.toString();
        }

        if (mode == MODE_TIMELINE_PACKETS) {
            List<CapturePacket> packets = CapturePacket.buildPackets(safeList, newestFirst, CapturePacket.DEFAULT_WINDOW_MS);
            for (int i = 0; i < packets.size(); i++) {
                if (i > 0) {
                    sb.append("\n\n");
                }
                sb.append(formatPacketForCopy(packets.get(i), r));
            }
            return sb.toString();
        }

        List<CaptureRecord> ordered = sortRecords(safeList, newestFirst);
        if (mode == MODE_DB_RECORDS) {
            for (int i = 0; i < ordered.size(); i++) {
                if (i > 0) {
                    sb.append("\n\n");
                }
                sb.append(formatDbRecordForCopy(ordered.get(i), r, true));
            }
            return sb.toString();
        }

        // MODE_PLAIN_TEXT
        for (int i = 0; i < ordered.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(ordered.get(i).content);
        }
        return sb.toString();
    }

    public static String formatPureText(List<CaptureRecord> records) {
        if (records == null || records.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(records.get(i).content);
        }
        return sb.toString();
    }

    public static String formatCsv(List<CaptureRecord> records, AppLabelResolver resolver) {
        AppLabelResolver r = safeResolver(resolver);
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF'); // UTF-8 BOM for Excel
        sb.append("ID,抓取时间,时间戳ms,应用名称,无障碍包名,控件ID,文本内容\n");
        if (records == null) {
            return sb.toString();
        }
        for (CaptureRecord record : records) {
            String resolved = r.resolveLabel(record.pkgName);
            sb.append(record.id).append(',')
                    .append(escapeCsvCell(formatTime(record.captureTime, true))).append(',')
                    .append(record.captureTime).append(',')
                    .append(escapeCsvCell(resolved)).append(',')
                    .append(escapeCsvCell(record.pkgName)).append(',')
                    .append(escapeCsvCell(record.viewId)).append(',')
                    .append(escapeCsvCell(record.content)).append('\n');
        }
        return sb.toString();
    }

    public static List<CaptureRecord> sortRecords(List<CaptureRecord> records, final boolean newestFirst) {
        List<CaptureRecord> copy = new ArrayList<>(records);
        Collections.sort(copy, new Comparator<CaptureRecord>() {
            @Override
            public int compare(CaptureRecord a, CaptureRecord b) {
                int timeCmp = Long.compare(a.captureTime, b.captureTime);
                int cmp = timeCmp != 0 ? timeCmp : Long.compare(a.id, b.id);
                return newestFirst ? -cmp : cmp;
            }
        });
        return copy;
    }

    private static String escapeCsvCell(String value) {
        String safe = value != null ? value : "";
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
