package com.screen.textgrabber;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 按时间与无障碍包名聚合后的抓包数据包
 */
public class CapturePacket {

    public static final long DEFAULT_WINDOW_MS = 2000L;

    public final int packetNumber;
    public final String pkgName;
    public final long startTime;
    public final long endTime;
    public final List<CaptureRecord> records;

    public CapturePacket(int packetNumber, String pkgName, long startTime, long endTime, List<CaptureRecord> records) {
        this.packetNumber = packetNumber;
        this.pkgName = pkgName != null ? pkgName : "";
        this.startTime = startTime;
        this.endTime = endTime;
        this.records = Collections.unmodifiableList(new ArrayList<>(records));
    }

    /**
     * 拼接包内所有节点文本（保持自然抓包从上到下的顺序）
     */
    public String getJoinedContent() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(records.get(i).content);
        }
        return sb.toString();
    }

    /**
     * 将原始数据库记录按相同包名与连续时间窗口聚合为抓包块。
     * 无论外层是否按最新优先排列，单个数据包内部的文本均保持按抓取正序排列。
     */
    public static List<CapturePacket> buildPackets(List<CaptureRecord> input, boolean newestFirst, long windowMs) {
        List<CapturePacket> packets = new ArrayList<>();
        if (input == null || input.isEmpty()) {
            return packets;
        }

        List<CaptureRecord> sorted = new ArrayList<>(input);
        Collections.sort(sorted, new Comparator<CaptureRecord>() {
            @Override
            public int compare(CaptureRecord a, CaptureRecord b) {
                int timeCmp = Long.compare(a.captureTime, b.captureTime);
                if (timeCmp != 0) {
                    return timeCmp;
                }
                return Long.compare(a.id, b.id);
            }
        });

        long effectiveWindow = windowMs > 0 ? windowMs : DEFAULT_WINDOW_MS;
        List<CaptureRecord> currentGroup = new ArrayList<>();
        CaptureRecord prev = null;
        int packetNumber = 1;

        for (CaptureRecord curr : sorted) {
            if (prev == null) {
                currentGroup.add(curr);
            } else {
                boolean samePkg = Objects.equals(curr.pkgName, prev.pkgName);
                long delta = curr.captureTime - prev.captureTime;
                if (samePkg && delta >= 0 && delta <= effectiveWindow) {
                    currentGroup.add(curr);
                } else {
                    packets.add(createPacket(packetNumber++, currentGroup));
                    currentGroup = new ArrayList<>();
                    currentGroup.add(curr);
                }
            }
            prev = curr;
        }

        if (!currentGroup.isEmpty()) {
            packets.add(createPacket(packetNumber, currentGroup));
        }

        if (newestFirst) {
            Collections.reverse(packets);
        }

        return packets;
    }

    private static CapturePacket createPacket(int packetNumber, List<CaptureRecord> group) {
        CaptureRecord first = group.get(0);
        CaptureRecord last = group.get(group.size() - 1);
        return new CapturePacket(packetNumber, first.pkgName, first.captureTime, last.captureTime, group);
    }
}
