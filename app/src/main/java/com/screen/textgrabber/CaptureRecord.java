package com.screen.textgrabber;

/**
 * 单条抓取文本数据库记录实体
 */
public class CaptureRecord {

    public final long id;
    public final String content;
    public final String pkgName;
    public final String viewId;
    public final long captureTime;

    public CaptureRecord(long id, String content, String pkgName, String viewId, long captureTime) {
        this.id = id;
        this.content = content != null ? content : "";
        this.pkgName = pkgName != null ? pkgName : "";
        this.viewId = viewId != null ? viewId : "";
        this.captureTime = captureTime;
    }

    /**
     * 获取简化后的控件 ID（去掉包名前缀），若无则返回空字符串
     */
    public String getShortViewId() {
        if (viewId.isEmpty()) {
            return "";
        }
        int idx = viewId.indexOf(":id/");
        if (idx >= 0 && idx + 1 < viewId.length()) {
            return viewId.substring(idx + 1);
        }
        return viewId;
    }
}
