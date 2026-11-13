package com.fntv.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 离线下载元数据存储（design D4）。
 *
 * 表结构：downloads
 * - item_guid     TEXT PRIMARY KEY  剧集 guid（与一集一一对应，也作为 DownloadRequest id）
 * - media_guid    TEXT              媒体 guid（play/info 解析后回填）
 * - tv_title      TEXT              剧名
 * - title         TEXT              集标题
 * - season_num    INTEGER           季号
 * - episode_num   INTEGER           集号
 * - poster        TEXT              海报路径
 * - quality_label TEXT              清晰度标签（"原画"/"1080p"...）
 * - quality_json  TEXT              清晰度参数 JSON（resolution/bitrate/progressive/isM3u8）
 * - hls           INTEGER           取流后是否为 HLS（1/0，未解析为 -1）
 * - stream_uri    TEXT              取流 URL（原画 range 直链 / 转码 playLink），离线播放用（design D2）
 * - state         INTEGER           ExoPlayer Download 状态镜像
 * - bytes         INTEGER           已下载字节
 * - estimated     INTEGER           预计字节
 * - danmu_path    TEXT              本地弹幕文件路径（空=未缓存）
 * - danmu_state   TEXT              弹幕状态 pending/ok/failed
 * - created_at    INTEGER           创建时间戳 ms
 * - updated_at    INTEGER           更新时间戳 ms
 * - last_error    TEXT              最近一次失败原因（v2 新增，失败时落库供列表展示）
 */
public class DownloadDbHelper extends SQLiteOpenHelper {

    private static final String TAG = "OfflineStore";
    private static final String DB_NAME = "offline_downloads.db";
    private static final int DB_VERSION = 2;
    private static final String TABLE = "downloads";

    public static class DownloadRecord {
        public String itemGuid;
        public String mediaGuid;
        public String tvTitle;
        public String title;
        public int seasonNum;
        public int episodeNum;
        public String poster;
        public String qualityLabel;
        public String qualityJson;
        public int hls = -1;
        public String streamUri;
        public int state;
        public long bytes;
        public long estimated;
        public String danmuPath;
        public String danmuState;
        public String lastError;
        public long createdAt;
        public long updatedAt;
    }

    public DownloadDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "item_guid TEXT PRIMARY KEY NOT NULL,"
                + "media_guid TEXT, tv_title TEXT, title TEXT,"
                + "season_num INTEGER DEFAULT 0, episode_num INTEGER DEFAULT 0,"
                + "poster TEXT, quality_label TEXT, quality_json TEXT,"
                + "hls INTEGER DEFAULT -1, stream_uri TEXT, state INTEGER DEFAULT 0,"
                + "bytes INTEGER DEFAULT 0, estimated INTEGER DEFAULT 0,"
                + "danmu_path TEXT, danmu_state TEXT DEFAULT 'pending',"
                + "created_at INTEGER, updated_at INTEGER, last_error TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v2：新增失败原因列。此后升级采用增量迁移，不再 DROP（否则升级即清空用户下载记录）
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN last_error TEXT");
            } catch (Exception e) {
                Log.w(TAG, "last_error 列已存在: " + e.getMessage());
            }
        }
    }

    private static DownloadRecord fromCursor(Cursor c) {
        DownloadRecord r = new DownloadRecord();
        r.itemGuid = c.getString(c.getColumnIndexOrThrow("item_guid"));
        r.mediaGuid = c.getString(c.getColumnIndexOrThrow("media_guid"));
        r.tvTitle = c.getString(c.getColumnIndexOrThrow("tv_title"));
        r.title = c.getString(c.getColumnIndexOrThrow("title"));
        r.seasonNum = c.getInt(c.getColumnIndexOrThrow("season_num"));
        r.episodeNum = c.getInt(c.getColumnIndexOrThrow("episode_num"));
        r.poster = c.getString(c.getColumnIndexOrThrow("poster"));
        r.qualityLabel = c.getString(c.getColumnIndexOrThrow("quality_label"));
        r.qualityJson = c.getString(c.getColumnIndexOrThrow("quality_json"));
        r.hls = c.getInt(c.getColumnIndexOrThrow("hls"));
        r.streamUri = c.getString(c.getColumnIndexOrThrow("stream_uri"));
        r.state = c.getInt(c.getColumnIndexOrThrow("state"));
        r.bytes = c.getLong(c.getColumnIndexOrThrow("bytes"));
        r.estimated = c.getLong(c.getColumnIndexOrThrow("estimated"));
        r.danmuPath = c.getString(c.getColumnIndexOrThrow("danmu_path"));
        r.danmuState = c.getString(c.getColumnIndexOrThrow("danmu_state"));
        int errIdx = c.getColumnIndex("last_error");
        r.lastError = errIdx >= 0 ? c.getString(errIdx) : null;
        r.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        r.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        return r;
    }

    private static ContentValues toValues(DownloadRecord r) {
        ContentValues v = new ContentValues();
        v.put("item_guid", r.itemGuid);
        v.put("media_guid", r.mediaGuid);
        v.put("tv_title", r.tvTitle);
        v.put("title", r.title);
        v.put("season_num", r.seasonNum);
        v.put("episode_num", r.episodeNum);
        v.put("poster", r.poster);
        v.put("quality_label", r.qualityLabel);
        v.put("quality_json", r.qualityJson);
        v.put("hls", r.hls);
        v.put("stream_uri", r.streamUri);
        v.put("state", r.state);
        v.put("bytes", r.bytes);
        v.put("estimated", r.estimated);
        v.put("danmu_path", r.danmuPath);
        v.put("danmu_state", r.danmuState);
        v.put("last_error", r.lastError);
        v.put("created_at", r.createdAt);
        v.put("updated_at", r.updatedAt);
        return v;
    }

    /** 插入或替换（按 item_guid 主键） */
    public void upsert(DownloadRecord r) {
        r.updatedAt = System.currentTimeMillis();
        getWritableDatabase().insertWithOnConflict(TABLE, null, toValues(r),
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 按 item_guid 查询，不存在返回 null */
    public DownloadRecord get(String itemGuid) {
        Cursor c = getReadableDatabase().query(TABLE, null, "item_guid=?",
                new String[]{itemGuid}, null, null, null);
        try {
            return c.moveToFirst() ? fromCursor(c) : null;
        } finally {
            c.close();
        }
    }

    /** 全量列表（按创建时间倒序） */
    public List<DownloadRecord> getAll() {
        return query(null, null, "created_at DESC");
    }

    /** 按剧名分组查询某一剧的全部记录 */
    public List<DownloadRecord> getByTvTitle(String tvTitle) {
        return query("tv_title=?", new String[]{tvTitle}, "episode_num ASC");
    }

    private List<DownloadRecord> query(String where, String[] args, String order) {
        List<DownloadRecord> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE, null, where, args, null, null, order);
        try {
            while (c.moveToNext()) out.add(fromCursor(c));
        } finally {
            c.close();
        }
        return out;
    }

    /** 部分字段更新（null 字段跳过） */
    public void updatePartial(String itemGuid, ContentValues patch) {
        patch.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update(TABLE, patch, "item_guid=?",
                new String[]{itemGuid});
    }

    public void updateState(String itemGuid, int state, long bytes) {
        ContentValues v = new ContentValues();
        v.put("state", state);
        v.put("bytes", bytes);
        updatePartial(itemGuid, v);
    }

    /** 记录/清除失败原因（"" 表示清除） */
    public void updateLastError(String itemGuid, String err) {
        ContentValues v = new ContentValues();
        if (err == null || err.isEmpty()) v.putNull("last_error");
        else v.put("last_error", err);
        updatePartial(itemGuid, v);
    }

    public void updateDanmu(String itemGuid, String path, String danmuState) {
        ContentValues v = new ContentValues();
        v.put("danmu_path", path);
        v.put("danmu_state", danmuState);
        updatePartial(itemGuid, v);
    }

    public void updateStreamInfo(String itemGuid, String mediaGuid, boolean hls, String streamUri) {
        ContentValues v = new ContentValues();
        v.put("media_guid", mediaGuid);
        v.put("hls", hls ? 1 : 0);
        v.put("stream_uri", streamUri);
        updatePartial(itemGuid, v);
    }

    public void updateEstimated(String itemGuid, long estimated) {
        ContentValues v = new ContentValues();
        v.put("estimated", estimated);
        updatePartial(itemGuid, v);
    }

    public void delete(String itemGuid) {
        getWritableDatabase().delete(TABLE, "item_guid=?", new String[]{itemGuid});
    }

    public void deleteByTvTitle(String tvTitle) {
        getWritableDatabase().delete(TABLE, "tv_title=?", new String[]{tvTitle});
    }
}
