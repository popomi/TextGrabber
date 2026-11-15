package com.screen.textgrabber;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * 文件导出与系统分享辅助类
 * 支持将全部抓包文本、纯文本、CSV 明细和原始 SQLite 数据库导出到 Download/TextGrabber/ 目录，并生成 FileProvider 分享副本。
 */
public final class ExportHelper {

    public static class ExportResult {
        public final boolean success;
        public final String displayPath;
        public final File shareCacheFile;
        public final String mimeType;
        public final String errorMessage;

        public ExportResult(boolean success, String displayPath, File shareCacheFile, String mimeType, String errorMessage) {
            this.success = success;
            this.displayPath = displayPath;
            this.shareCacheFile = shareCacheFile;
            this.mimeType = mimeType;
            this.errorMessage = errorMessage;
        }
    }

    private ExportHelper() {
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
    }

    public static ExportResult exportArchiveText(Context context, String text) {
        String fileName = "TextGrabber_抓包存档_" + timestamp() + ".txt";
        byte[] data = (text != null ? text : "").getBytes(StandardCharsets.UTF_8);
        return saveBytes(context, fileName, "text/plain", data);
    }

    public static ExportResult exportPureText(Context context, String text) {
        String fileName = "TextGrabber_纯文本_" + timestamp() + ".txt";
        byte[] data = (text != null ? text : "").getBytes(StandardCharsets.UTF_8);
        return saveBytes(context, fileName, "text/plain", data);
    }

    public static ExportResult exportCsv(Context context, String csvText) {
        String fileName = "TextGrabber_数据库明细_" + timestamp() + ".csv";
        byte[] data = (csvText != null ? csvText : "").getBytes(StandardCharsets.UTF_8);
        return saveBytes(context, fileName, "text/csv", data);
    }

    public static ExportResult exportDatabaseFile(Context context, DBManager dbManager) {
        if (dbManager == null) {
            return new ExportResult(false, "", null, "application/octet-stream", "数据库实例未初始化");
        }
        dbManager.checkpointWal();
        File dbFile = context.getDatabasePath(dbManager.getDatabaseName());
        if (dbFile == null || !dbFile.exists()) {
            return new ExportResult(false, "", null, "application/octet-stream", "未找到数据库文件");
        }
        String fileName = "TextGrabber_数据库_" + timestamp() + ".db";
        try (FileInputStream fis = new FileInputStream(dbFile)) {
            byte[] bytes = readAllBytes(fis);
            return saveBytes(context, fileName, "application/octet-stream", bytes);
        } catch (IOException e) {
            return new ExportResult(false, "", null, "application/octet-stream", e.getMessage());
        }
    }

    public static void shareFile(Context context, File file, String mimeType) {
        if (context == null || file == null || !file.exists()) {
            return;
        }
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(mimeType != null ? mimeType : "*/*");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(intent, context.getString(R.string.share_chooser_title));
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(chooser);
    }

    private static ExportResult saveBytes(Context context, String fileName, String mimeType, byte[] data) {
        File cacheFile = writeShareCache(context, fileName, data);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentResolver resolver = context.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TextGrabber");
                values.put(MediaStore.Downloads.IS_PENDING, 1);

                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream os = resolver.openOutputStream(uri)) {
                        if (os == null) {
                            throw new IOException("无法打开 MediaStore 输出流");
                        }
                        os.write(data);
                        os.flush();
                    }
                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    resolver.update(uri, values, null, null);
                    return new ExportResult(true, "Download/TextGrabber/" + fileName, cacheFile, mimeType, null);
                }
            } catch (Exception e) {
                // 回退到应用外部专属下载目录
                ExportResult fallback = saveToAppExternalDir(context, fileName, mimeType, data, cacheFile);
                if (fallback.success) {
                    return fallback;
                }
                return new ExportResult(false, "", cacheFile, mimeType, e.getMessage());
            }
        }

        // Android 7.0 - 9.0: 先尝试公共 Downloads 目录，失败则回退到应用外部下载目录
        try {
            File pubDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TextGrabber");
            if (!pubDir.exists() && !pubDir.mkdirs()) {
                throw new IOException("无法创建公共下载目录");
            }
            File target = new File(pubDir, fileName);
            writeStreamToFile(new ByteArrayInputStream(data), target);
            return new ExportResult(true, target.getAbsolutePath(), cacheFile, mimeType, null);
        } catch (Exception e) {
            ExportResult fallback = saveToAppExternalDir(context, fileName, mimeType, data, cacheFile);
            if (fallback.success) {
                return fallback;
            }
            return new ExportResult(false, "", cacheFile, mimeType, e.getMessage());
        }
    }

    private static ExportResult saveToAppExternalDir(
            Context context,
            String fileName,
            String mimeType,
            byte[] data,
            File cacheFile
    ) {
        try {
            File baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (baseDir == null) {
                baseDir = context.getFilesDir();
            }
            File dir = new File(baseDir, "TextGrabber");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("无法创建应用导出目录");
            }
            File target = new File(dir, fileName);
            writeStreamToFile(new ByteArrayInputStream(data), target);
            return new ExportResult(true, target.getAbsolutePath(), cacheFile, mimeType, null);
        } catch (Exception e) {
            return new ExportResult(false, "", cacheFile, mimeType, e.getMessage());
        }
    }

    private static File writeShareCache(Context context, String fileName, byte[] data) {
        try {
            File cacheDir = new File(context.getCacheDir(), "exports");
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            pruneOldCacheFiles(cacheDir, 5);
            File cacheFile = new File(cacheDir, fileName);
            writeStreamToFile(new ByteArrayInputStream(data), cacheFile);
            return cacheFile;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void pruneOldCacheFiles(File dir, int keepMax) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= keepMax) {
            return;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        int toDelete = files.length - keepMax;
        for (int i = 0; i < toDelete; i++) {
            files[i].delete();
        }
    }

    private static void writeStreamToFile(InputStream in, File target) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
            fos.flush();
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        int read;
        while ((read = in.read(buffer)) != -1) {
            baos.write(buffer, 0, read);
        }
        return baos.toByteArray();
    }
}
