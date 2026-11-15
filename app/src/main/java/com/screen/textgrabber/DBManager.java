package com.screen.textgrabber;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据库管理类
 * 负责 SQLite 数据库的创建、升级、查询、清空及 WAL 检查点管理。
 */
public class DBManager extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "text_grabber.db";
    private static final int DATABASE_VERSION = 2;
    private static final String TABLE_NAME = "captured_text";

    private static final String COLUMN_ID = "_id";
    private static final String COLUMN_CONTENT = "content";
    private static final String COLUMN_PKG_NAME = "pkg_name";
    private static final String COLUMN_VIEW_ID = "view_id";
    private static final String COLUMN_CAPTURE_TIME = "capture_time";
    private static final String COLUMN_HASH_KEY = "hash_key";

    /**
     * 按无障碍包名分组统计实体
     */
    public static class PackageStat {
        public final String pkgName;
        public final int count;

        public PackageStat(String pkgName, int count) {
            this.pkgName = pkgName != null ? pkgName : "";
            this.count = count;
        }
    }

    public DBManager(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTable = "CREATE TABLE " + TABLE_NAME + " (" +
                COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COLUMN_CONTENT + " TEXT, " +
                COLUMN_PKG_NAME + " TEXT, " +
                COLUMN_VIEW_ID + " TEXT, " +
                COLUMN_CAPTURE_TIME + " INTEGER, " +
                COLUMN_HASH_KEY + " TEXT UNIQUE)";
        db.execSQL(createTable);
        ensureIndexes(db);
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        if (!db.isReadOnly()) {
            ensureIndexes(db);
        }
    }

    private void ensureIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_capture_time ON " + TABLE_NAME + "(" + COLUMN_CAPTURE_TIME + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pkg_name ON " + TABLE_NAME + "(" + COLUMN_PKG_NAME + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        onCreate(db);
    }

    /**
     * 插入一条抓取的文字记录
     *
     * @return true 表示成功写入新记录，false 表示重复忽略
     */
    public boolean insertText(String content, String pkgName, String viewId, long captureTime, String hashKey) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_CONTENT, content);
        values.put(COLUMN_PKG_NAME, pkgName);
        values.put(COLUMN_VIEW_ID, viewId);
        values.put(COLUMN_CAPTURE_TIME, captureTime);
        values.put(COLUMN_HASH_KEY, hashKey);

        long rowId = db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        return rowId != -1L;
    }

    /**
     * 查询数据库记录总数与最大主键 ID，用于轻量级变化检测
     */
    public long[] getDbSignature() {
        long count = 0L;
        long maxId = 0L;
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT COUNT(*), COALESCE(MAX(" + COLUMN_ID + "), 0) FROM " + TABLE_NAME,
                null
        );
        if (cursor.moveToFirst()) {
            count = cursor.getLong(0);
            maxId = cursor.getLong(1);
        }
        cursor.close();
        return new long[]{count, maxId};
    }

    /**
     * 获取数据库中全部无障碍包名及各自抓包条数（按条数降序）
     */
    public List<PackageStat> getPackageStats() {
        List<PackageStat> stats = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT COALESCE(" + COLUMN_PKG_NAME + ", ''), COUNT(*) FROM " + TABLE_NAME +
                        " GROUP BY COALESCE(" + COLUMN_PKG_NAME + ", '') ORDER BY COUNT(*) DESC",
                null
        );
        if (cursor.moveToFirst()) {
            do {
                stats.add(new PackageStat(cursor.getString(0), cursor.getInt(1)));
            } while (cursor.moveToNext());
        }
        cursor.close();
        return stats;
    }

    /**
     * 查询全部抓取记录（不限制条数、不截断文本），支持按无障碍包名和关键词筛选
     */
    public List<CaptureRecord> queryRecords(String pkgFilter, String keyword, boolean ascending) {
        List<CaptureRecord> records = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();

        StringBuilder where = new StringBuilder();
        List<String> args = new ArrayList<>();

        if (pkgFilter != null) {
            where.append("COALESCE(").append(COLUMN_PKG_NAME).append(", '') = ?");
            args.add(pkgFilter);
        }

        if (keyword != null && !keyword.trim().isEmpty()) {
            if (where.length() > 0) {
                where.append(" AND ");
            }
            String pattern = "%" + keyword.trim() + "%";
            where.append("(")
                    .append(COLUMN_CONTENT).append(" LIKE ? OR ")
                    .append("COALESCE(").append(COLUMN_PKG_NAME).append(", '') LIKE ? OR ")
                    .append("COALESCE(").append(COLUMN_VIEW_ID).append(", '') LIKE ?")
                    .append(")");
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }

        String selection = where.length() > 0 ? where.toString() : null;
        String[] selectionArgs = args.isEmpty() ? null : args.toArray(new String[0]);
        String orderBy = ascending
                ? COLUMN_CAPTURE_TIME + " ASC, " + COLUMN_ID + " ASC"
                : COLUMN_CAPTURE_TIME + " DESC, " + COLUMN_ID + " DESC";

        Cursor cursor = db.query(
                TABLE_NAME,
                new String[]{COLUMN_ID, COLUMN_CONTENT, COLUMN_PKG_NAME, COLUMN_VIEW_ID, COLUMN_CAPTURE_TIME},
                selection,
                selectionArgs,
                null,
                null,
                orderBy
        );

        if (cursor.moveToFirst()) {
            do {
                long id = cursor.getLong(0);
                String content = cursor.getString(1);
                String pkgName = cursor.getString(2);
                String viewId = cursor.getString(3);
                long captureTime = cursor.getLong(4);
                records.add(new CaptureRecord(id, content, pkgName, viewId, captureTime));
            } while (cursor.moveToNext());
        }
        cursor.close();
        return records;
    }

    /**
     * 刷新 WAL 日志到主数据库文件，确保导出 .db 文件时数据完整
     */
    public void checkpointWal() {
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            Cursor c = db.rawQuery("PRAGMA wal_checkpoint(FULL)", null);
            if (c != null) {
                c.close();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 一键清空数据库表、重置自增序列并压缩回收空间
     */
    public synchronized void clearAllData() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_NAME, null, null);
        try {
            db.execSQL("DELETE FROM sqlite_sequence WHERE name='" + TABLE_NAME + "'");
        } catch (Exception ignored) {
        }
        checkpointWal();
        try {
            db.execSQL("VACUUM");
        } catch (Exception ignored) {
        }
    }

    /**
     * 兼容方法：获取最近保存的文本记录
     */
    public List<String> getRecentTexts(int limit) {
        List<String> texts = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();

        Cursor cursor = db.query(
                TABLE_NAME,
                new String[]{COLUMN_CONTENT},
                null,
                null,
                null,
                null,
                COLUMN_CAPTURE_TIME + " DESC",
                String.valueOf(limit)
        );

        if (cursor.moveToFirst()) {
            do {
                texts.add(cursor.getString(0));
            } while (cursor.moveToNext());
        }

        cursor.close();
        return texts;
    }

    /**
     * 兼容方法：按 content 列模糊搜索文本记录
     */
    public List<String> searchByContent(String keyword, int limit) {
        List<String> texts = new ArrayList<>();
        if (keyword == null || keyword.trim().isEmpty()) {
            return texts;
        }

        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(
                TABLE_NAME,
                new String[]{COLUMN_CONTENT},
                COLUMN_CONTENT + " LIKE ?",
                new String[]{"%" + keyword.trim() + "%"},
                null,
                null,
                COLUMN_CAPTURE_TIME + " DESC",
                String.valueOf(limit)
        );

        if (cursor.moveToFirst()) {
            do {
                texts.add(cursor.getString(0));
            } while (cursor.moveToNext());
        }

        cursor.close();
        return texts;
    }
}
