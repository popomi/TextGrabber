package com.screen.textgrabber;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * 抓取存档列表适配器
 * 支持三种视图模式（按时间抓包、数据库无障碍包、纯文本全文），完整展示全部内容不截断。
 */
public class ArchiveAdapter extends RecyclerView.Adapter<ArchiveAdapter.ViewHolder> {

    public interface OnCopyListener {
        void onCopyText(String text, boolean isFullMetadata);
    }

    private final ArchiveFormatter.AppLabelResolver labelResolver;
    private final OnCopyListener copyListener;

    private int currentMode = ArchiveFormatter.MODE_TIMELINE_PACKETS;
    private List<CapturePacket> packets = new ArrayList<>();
    private List<CaptureRecord> records = new ArrayList<>();

    public ArchiveAdapter(ArchiveFormatter.AppLabelResolver labelResolver, OnCopyListener copyListener) {
        this.labelResolver = labelResolver;
        this.copyListener = copyListener;
    }

    public void submitData(int mode, List<CapturePacket> newPackets, List<CaptureRecord> newRecords) {
        this.currentMode = mode;
        this.packets = newPackets != null ? new ArrayList<>(newPackets) : new ArrayList<CapturePacket>();
        this.records = newRecords != null ? new ArrayList<>(newRecords) : new ArrayList<CaptureRecord>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_archive_entry, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        if (currentMode == ArchiveFormatter.MODE_TIMELINE_PACKETS) {
            final CapturePacket packet = packets.get(position);
            String header = "抓包 #" + packet.packetNumber
                    + "  ·  " + ArchiveFormatter.formatShortTime(packet.startTime)
                    + "  ·  共 " + packet.records.size() + " 条";
            String meta = "应用/无障碍包: " + labelResolver.resolveLabel(packet.pkgName);
            final String content = packet.getJoinedContent();

            holder.tvHeader.setText(header);
            holder.tvMeta.setVisibility(View.VISIBLE);
            holder.tvMeta.setText(meta);
            bindSelectableText(holder.tvContent, content);

            holder.btnCopy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (copyListener != null) {
                        copyListener.onCopyText(content, false);
                    }
                }
            });
            holder.btnCopy.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (copyListener != null) {
                        copyListener.onCopyText(ArchiveFormatter.formatPacketForCopy(packet, labelResolver), true);
                    }
                    return true;
                }
            });
        } else if (currentMode == ArchiveFormatter.MODE_DB_RECORDS) {
            final CaptureRecord record = records.get(position);
            String header = "#" + record.id + "  ·  " + ArchiveFormatter.formatTime(record.captureTime, true);
            String shortViewId = record.getShortViewId();
            String viewLabel = shortViewId.isEmpty() ? "无" : shortViewId;
            String meta = "包名: " + labelResolver.resolveLabel(record.pkgName) + "  |  控件: " + viewLabel;
            final String content = record.content;

            holder.tvHeader.setText(header);
            holder.tvMeta.setVisibility(View.VISIBLE);
            holder.tvMeta.setText(meta);
            bindSelectableText(holder.tvContent, content);

            holder.btnCopy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (copyListener != null) {
                        copyListener.onCopyText(content, false);
                    }
                }
            });
            holder.btnCopy.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (copyListener != null) {
                        copyListener.onCopyText(
                                ArchiveFormatter.formatDbRecordForCopy(record, labelResolver, true),
                                true
                        );
                    }
                    return true;
                }
            });
        } else {
            final CaptureRecord record = records.get(position);
            String header = "#" + record.id + "  ·  " + ArchiveFormatter.formatShortTime(record.captureTime);
            final String content = record.content;

            holder.tvHeader.setText(header);
            holder.tvMeta.setVisibility(View.GONE);
            bindSelectableText(holder.tvContent, content);

            holder.btnCopy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (copyListener != null) {
                        copyListener.onCopyText(content, false);
                    }
                }
            });
            holder.btnCopy.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (copyListener != null) {
                        copyListener.onCopyText(
                                ArchiveFormatter.formatDbRecordForCopy(record, labelResolver, true),
                                true
                        );
                    }
                    return true;
                }
            });
        }
    }

    private void bindSelectableText(TextView textView, String text) {
        // 先关闭再开启 textIsSelectable，规避 RecyclerView 复用时旧选区索引越界问题
        textView.setTextIsSelectable(false);
        textView.setText(text);
        textView.setTextIsSelectable(true);
    }

    @Override
    public int getItemCount() {
        if (currentMode == ArchiveFormatter.MODE_TIMELINE_PACKETS) {
            return packets.size();
        }
        return records.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvHeader;
        final TextView tvMeta;
        final TextView tvContent;
        final MaterialButton btnCopy;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvHeader = itemView.findViewById(R.id.tv_item_header);
            tvMeta = itemView.findViewById(R.id.tv_item_meta);
            tvContent = itemView.findViewById(R.id.tv_item_content);
            btnCopy = itemView.findViewById(R.id.btn_item_copy);
        }
    }
}
