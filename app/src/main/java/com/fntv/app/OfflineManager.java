package com.fntv.app;

import android.content.Context;
import android.net.Uri;
import android.os.StatFs;
import android.util.Log;

import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.ApiResponse;
import com.fntv.app.api.model.PlayInfoResponse;
import com.fntv.app.api.model.PlayLinkResponse;
import com.fntv.app.api.model.StreamResponse;
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider;
import com.google.android.exoplayer2.offline.Download;
import com.google.android.exoplayer2.offline.DownloadManager;
import com.google.android.exoplayer2.offline.DownloadRequest;
import com.google.android.exoplayer2.offline.DownloadService;
import com.google.android.exoplayer2.scheduler.Requirements;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.CacheKeyFactory;
import com.google.android.exoplayer2.upstream.cache.NoOpCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;
import com.google.gson.Gson;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 离线下载核心管理器（design D1~D6）。
 *
 * - SimpleCache 全局单例（NoOpCacheEvictor，不自动逐出；删除完全由用户控制）
 * - DownloadManager 串行下载（maxParallel=1 + 单线程 Executor）
 * - 任务持久化"取流参数"而非 URL：元数据存 itemGuid + 清晰度参数，
 *   每次下载会话重新取流（play/info → media/range 或 getStream → play/play）
 * - 原 画：Progressive 直链 /v/api/v1/media/range/{mediaGuid}，
 *   customCacheKey = "orig_" + mediaGuid，播放侧 CacheKeyFactory 用同一 key 命中
 * - 转 码：HLS playLink，缓存键 = 去掉 query 的规范化 URI（同一转码参数下分段内容一致）
 * - 弹幕：下载启动时由 DanmuOfflineFetcher 拉取落盘（失败不阻塞）
 *
 * 注意：getInstance() 必须先在主线程调用一次（DownloadManager 回调绑定构造线程的 Looper）；
 * 之后后台线程再访问是安全的。
 */
public class OfflineManager {

    private static final String TAG = "OfflineManager";
    /** 用户暂停的 stopReason */
    public static final int STOP_REASON_USER = 1;
    /** 缓存 key 前缀：原画 progressive 下载与播放共用 */
    public static final String ORIG_KEY_PREFIX = "orig_";
    /** 预计体积的空间余量 */
    private static final long SPACE_MARGIN = 200L * 1024 * 1024;

    public interface StateListener {
        /** 任一下载状态/进度变化（主线程回调） */
        void onOfflineStateChanged();
    }

    private static OfflineManager instance;

    public static synchronized OfflineManager getInstance(Context context) {
        if (instance == null) instance = new OfflineManager(context.getApplicationContext());
        return instance;
    }

    private final Context appContext;
    private final DownloadDbHelper db;
    private SimpleCache cache;
    private DownloadManager downloadManager;
    private StandaloneDatabaseProvider databaseProvider;
    private final List<StateListener> listeners = new java.util.ArrayList<>();
    private volatile boolean initialized;
    /** 当前实际使用的缓存目录（ensureInitialized 后可用） */
    private File cacheDir;

    private OfflineManager(Context appContext) {
        this.appContext = appContext;
        this.db = new DownloadDbHelper(appContext);
    }

    // ==================== 初始化（1.1 / 1.3） ====================

    /** 构建 SimpleCache 与 DownloadManager；重复调用幂等。需在主线程首次调用。 */
    public synchronized void ensureInitialized() {
        if (initialized) return;
        initialized = true;

        File chosenDir = pickCacheDir();
        cacheDir = chosenDir;
        databaseProvider = new StandaloneDatabaseProvider(appContext);
        try {
            cache = new SimpleCache(chosenDir, new NoOpCacheEvictor(), databaseProvider);
        } catch (Exception e) {
            // 外部目录不可用（被锁/权限异常）→ 回退内部存储
            Log.w(TAG, "SimpleCache 初始化失败，回退内部存储: " + e.getMessage());
            chosenDir = new File(appContext.getFilesDir(), "offline_cache");
            cacheDir = chosenDir;
            cache = new SimpleCache(chosenDir, new NoOpCacheEvictor(), databaseProvider);
        }
        Log.d(TAG, "离线缓存目录: " + chosenDir.getAbsolutePath());

        // 串行执行器（design D6）：同时仅 1 个下载任务
        Executor serialExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        // 上游数据源：复用带 token/Authx/UA 的流客户端（1.3，与 OkHttpExoDataSource 同源）
        DataSource.Factory upstreamFactory = () -> new OkHttpExoDataSource(FnApiManager.getInstance().getDownloadClient(), true);
        // DownloadManager 第 4 参是"上游工厂"：它内部会自建 CacheDataSource（默认键工厂=完整 URL）。
        // 传纯 OkHttp 上游，避免把带缓存的工厂再套一层导致双层缓存/键不一致。
        downloadManager = new DownloadManager(appContext, databaseProvider, cache, upstreamFactory, serialExecutor);
        downloadManager.setMaxParallelDownloads(1);
        downloadManager.setMinRetryCount(3);
        downloadManager.addListener(new DownloadManager.Listener() {
            @Override
            public void onDownloadChanged(DownloadManager dm, Download download, Exception finalException) {
                syncDownloadToDb(download, finalException);
                notifyStateChanged();
            }

            @Override
            public void onDownloadRemoved(DownloadManager dm, Download download) {
                deleteDanmuFile(download.request.id);
                liveProgress.remove(download.request.id);
                // 清理下载索引移除过程中被 syncDownloadToDb 补建的僵尸记录（见其 REMOVING 分支）
                db.delete(download.request.id);
                notifyStateChanged();
            }
        });
        applyWifiOnlyRequirement();
        // 关键：DownloadManager 构造后默认 downloadsPaused=true（ExoPlayer 2.18.7 字节码确认），
        // 只有 DownloadService 启动时才会在 onCreate 里自动 resumeDownloads()。App 冷启动若
        // 服务没拉起来，管理器会一直暂停——排队/下载中的任务静默不动。这里在进程内直接解除
        // 暂停，任务按 Requirements 立即续跑；用户单任务暂停走 stopReason，不受影响。
        try {
            downloadManager.resumeDownloads();
        } catch (Exception e) {
            Log.w(TAG, "resumeDownloads 失败: " + e.getMessage());
        }
        Log.d(TAG, "OfflineManager 初始化完成");
    }

    /** 缓存目录：用户指定优先，其次外部 App 专属目录，内部兜底（design D3） */
    private File pickCacheDir() {
        String chosen = appContext.getSharedPreferences("fntv_prefs", 0)
                .getString("offline_cache_dir", "");
        if (!chosen.isEmpty()) {
            File dir = new File(chosen, "offline_cache");
            if ((dir.exists() || dir.mkdirs()) && dir.canWrite()) return dir;
            Log.w(TAG, "用户指定缓存目录不可用，回退自动选择: " + dir.getAbsolutePath());
        }
        return autoCacheDir();
    }

    /** 自动选择：外部 App 专属目录（可用>500MB）优先，内部兜底 */
    private File autoCacheDir() {
        File external = appContext.getExternalFilesDir(null);
        if (external != null) {
            File dir = new File(external, "offline_cache");
            if (!dir.exists()) dir.mkdirs();
            try {
                StatFs st = new StatFs(dir.getAbsolutePath());
                if (st.getAvailableBytes() > 500L * 1024 * 1024) return dir;
            } catch (Exception ignored) {}
        }
        File internal = new File(appContext.getFilesDir(), "offline_cache");
        if (!internal.exists()) internal.mkdirs();
        return internal;
    }

    public SimpleCache getCache() {
        ensureInitialized();
        return cache;
    }

    public DownloadManager getDownloadManager() {
        ensureInitialized();
        return downloadManager;
    }

    public DownloadDbHelper getDb() {
        return db;
    }

    // ==================== 播放侧缓存命中（6.1 配套） ====================

    /** 缓存键与下载侧一致（关键，见注释） */
    public CacheKeyFactory playbackCacheKeyFactory() {
        return dataSpec -> {
            String key = dataSpec.key;
            if (key != null) return key;
            Uri u = dataSpec.uri;
            String path = u.getPath() == null ? "" : u.getPath();
            if (path.contains("/media/range/")) {
                return ORIG_KEY_PREFIX + path.substring(path.lastIndexOf('/') + 1);
            }
            // 完整 URL（含 query）作键：下载侧 DownloadManager 内部用默认键工厂（完整 URL），
            // 播放侧键必须与下载侧一致才能命中缓存。115 直链重签名不影响——离线播放读取
            // 的就是入队时落库的同一 URL 字符串。
            return u.toString();
        };
    }

    /** 离线播放专用 DataSource.Factory：只读缓存（无上游，命中即本地，未命中即错误） */
    public DataSource.Factory offlinePlaybackDataSourceFactory() {
        return new CacheDataSource.Factory()
                .setCache(getCache())
                .setCacheKeyFactory(playbackCacheKeyFactory())
                .setUpstreamDataSourceFactory(null)
                .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE);
    }

    /** 在线边下边播也可用（不强制）：缓存 + 现有流客户端上游 */
    public DataSource.Factory cachePlaybackDataSourceFactory() {
        return new CacheDataSource.Factory()
                .setCache(getCache())
                .setCacheKeyFactory(playbackCacheKeyFactory())
                .setUpstreamDataSourceFactory(() -> new OkHttpExoDataSource(FnApiManager.getInstance().getStreamClient()));
    }

    // ==================== 任务提交（2.2 / 2.5） ====================

    /** 清晰度参数（来自 QualitySelectHelper.QualityOption 的下载投影） */
    public static class QualityParams {
        public String label;
        public String resolution;
        public int bitrate;
        public boolean original;
        /** 云盘/strm 直链缓存：入队/续传时重新取流拿新签名直链 */
        public boolean cloud;
        /** 弹窗阶段探测到的直链（仅参考，实际入队以重新取流结果为准） */
        public String directUrl;
    }

    /**
     * 解析取流参数并入队（后台线程调用）。
     * 流程：play/info 拿 mediaGuid → 体积预估与空间预检 → 构造 DownloadRequest → sendAddDownload。
     *
     * @return null=成功；否则返回错误信息
     */
    public String resolveAndEnqueue(DownloadDbHelper.DownloadRecord rec, QualityParams qp, long durationSec) {
        return resolveAndEnqueue(rec, qp, durationSec, null);
    }

    /**
     * 同上，可传入弹窗阶段已解析的 mediaGuid（4.3，避免重复 play/info）。
     */
    public String resolveAndEnqueue(DownloadDbHelper.DownloadRecord rec, QualityParams qp,
                                    long durationSec, String preResolvedMediaGuid) {
        try {
            // 1. play/info 解析 mediaGuid
            String mediaGuid;
            if (preResolvedMediaGuid != null && !preResolvedMediaGuid.isEmpty()) {
                mediaGuid = preResolvedMediaGuid;
            } else {
                mediaGuid = resolveMediaGuid(rec.itemGuid);
                if (mediaGuid == null) return "play/info 失败";
            }
            rec.mediaGuid = mediaGuid;
            // 弹幕状态初始化为 pending（首次入队），下载中据此触发弹幕离线获取；
            // 重入队（恢复/重试）时保留已有 ok/failed 状态。
            if (rec.danmuState == null || rec.danmuState.isEmpty()) rec.danmuState = "pending";
            db.upsert(rec);

            String baseUrl = getBaseUrl();
            DownloadRequest request;

            if (qp.cloud) {
                // 2c. 云盘/strm：重新取流拿直链（签名有时效，逐集/续传都必须新鲜 URL）
                StreamResponse stream = fetchStream(mediaGuid);
                if (stream == null) return "stream 获取失败";
                StreamResponse.DirectLinkQuality match = matchDirectQuality(stream, qp);
                if (match == null) {
                    db.delete(rec.itemGuid);
                    return "无可用云盘直链（云盘授权可能已过期）";
                }
                String url = match.url.replace("\\u0026", "&");
                boolean hls = match.isM3u8 || url.contains(".m3u8");
                // 体积预估仅对渐进式直链可信。HLS（含 115 原画转封装流）实际体积与
                // 源文件大小、码率×时长无关（实测 115 原画 HLS ≈1.45GB，源 mkv 5.93GB），
                // 不写 estimated：下载中显示真实字节+速度，完成后以实际缓存字节为准。
                long total = 0;
                if (!hls) {
                    if ("原画".equals(qp.label) && stream.fileStream != null && stream.fileStream.size > 0) {
                        total = stream.fileStream.size;
                    }
                    if (total <= 0 && durationSec > 0 && match.bitrate > 0) {
                        total = match.bitrate / 8L * durationSec;
                    }
                    if (total <= 0) total = probeContentLength(url);
                }
                if (total > 0) {
                    db.updateEstimated(rec.itemGuid, total);
                    rec.estimated = total;
                    String spaceErr = checkSpace(total);
                    if (spaceErr != null) {
                        db.delete(rec.itemGuid);
                        return spaceErr;
                    }
                }
                db.updateStreamInfo(rec.itemGuid, mediaGuid, hls, url);
                rec.streamUri = url;
                rec.hls = hls ? 1 : 0;
                DownloadRequest.Builder rb = new DownloadRequest.Builder(rec.itemGuid, Uri.parse(url))
                        .setData(new Gson().toJson(qp).getBytes("UTF-8"));
                if (hls) rb.setMimeType(com.google.android.exoplayer2.util.MimeTypes.APPLICATION_M3U8);
                request = rb.build();
            } else if (qp.original) {
                // 2a. 原画：progressive 直链，HEAD 拿真实体积
                String url = baseUrl + "/v/api/v1/media/range/" + mediaGuid;
                long total = probeContentLength(url);
                if (total > 0) {
                    db.updateEstimated(rec.itemGuid, total);
                    rec.estimated = total;
                    // 2c. 空间预检
                    String spaceErr = checkSpace(total);
                    if (spaceErr != null) {
                        db.delete(rec.itemGuid);
                        return spaceErr;
                    }
                }
                db.updateStreamInfo(rec.itemGuid, mediaGuid, false, url);
                // 同步回内存 rec：结尾整行 upsert 会用 rec 字段覆盖 DB，
                // 不同步则 stream_uri/hls 被 null/-1 冲掉，导致离线播放判定失败。
                rec.streamUri = url;
                rec.hls = 0;
                request = new DownloadRequest.Builder(rec.itemGuid, Uri.parse(url))
                        .setCustomCacheKey(ORIG_KEY_PREFIX + mediaGuid)
                        .setData(new Gson().toJson(qp).getBytes("UTF-8"))
                        .build();
            } else {
                // 2b. 转码：getStream → play/play 取 playLink，体积按 bitrate×时长估算
                StreamResponse stream = fetchStream(mediaGuid);
                if (stream == null) return "stream 获取失败";
                String playLink = fetchPlayLink(mediaGuid, stream, qp);
                if (playLink == null) return "play/play 取流失败";
                boolean hls = playLink.contains(".m3u8");
                db.updateStreamInfo(rec.itemGuid, mediaGuid, hls, playLink);
                rec.streamUri = playLink;
                rec.hls = hls ? 1 : 0;
                long estimated = durationSec > 0 && qp.bitrate > 0
                        ? (long) qp.bitrate / 8L * durationSec : 0;
                if (estimated > 0) {
                    db.updateEstimated(rec.itemGuid, estimated);
                    rec.estimated = estimated;
                    String spaceErr = checkSpace(estimated);
                    if (spaceErr != null) {
                        db.delete(rec.itemGuid);
                        return spaceErr;
                    }
                }
                DownloadRequest.Builder rb = new DownloadRequest.Builder(rec.itemGuid, Uri.parse(playLink))
                        .setData(new Gson().toJson(qp).getBytes("UTF-8"));
                if (hls) rb.setMimeType(com.google.android.exoplayer2.util.MimeTypes.APPLICATION_M3U8);
                request = rb.build();
            }

            db.upsert(rec);
            ensureInitialized();
            DownloadService.sendAddDownload(appContext, OfflineDownloadService.class, request, false);
            Log.d(TAG, "入队: " + rec.itemGuid + " " + qp.label);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "入队失败: " + rec.itemGuid, e);
            return e.getMessage() == null ? "入队异常" : e.getMessage();
        }
    }

    // ==================== 任务操作（2.3 / 5.2 / 5.3） ====================

    /** 暂停单任务（用户暂停） */
    public void pause(String itemGuid) {
        ensureInitialized();
        downloadManager.setStopReason(itemGuid, STOP_REASON_USER);
    }

    /** 恢复单任务。转码/云直链任务恢复时重新取流（URL 时效，design D2）。 */
    public void resume(String itemGuid) {
        ensureInitialized();
        DownloadDbHelper.DownloadRecord rec = db.get(itemGuid);
        if (rec == null) return;
        QualityParams qp = null;
        try { qp = new Gson().fromJson(rec.qualityJson, QualityParams.class); } catch (Exception ignored) {}
        boolean cloud = qp != null && qp.cloud;
        boolean transcode = rec.qualityLabel != null && !"原画".equals(rec.qualityLabel);
        if (cloud) {
            // 云直链：重取流换新签名 URL。addDownload 对已有任务走 mergeRequest，
            // 缓存键去 query 规范化 → 已下载数据保留，从断点继续
            reenqueueWithFreshStream(rec, false);
        } else if (transcode && rec.mediaGuid != null) {
            // NAS 转码：HLS 会话路径可能变化，移除重建（旧分段作废）
            reenqueueWithFreshStream(rec, true);
        } else {
            downloadManager.setStopReason(itemGuid, 0);
        }
    }

    /** 失败重试：转码任务重新取流重建，原画任务直接恢复 */
    public void retry(String itemGuid) {
        resume(itemGuid);
    }

    /** 删除单任务：清下载索引、缓存数据、弹幕文件与元数据（5.3） */
    public void remove(String itemGuid) {
        ensureInitialized();
        DownloadService.sendRemoveDownload(appContext, OfflineDownloadService.class, itemGuid, false);
        db.delete(itemGuid);
        deleteDanmuFile(itemGuid);
        notifyStateChanged();
    }

    /** 删除某剧全部任务（5.3 整季） */
    public void removeByTvTitle(String tvTitle) {
        ensureInitialized();
        List<DownloadDbHelper.DownloadRecord> list = db.getByTvTitle(tvTitle);
        for (DownloadDbHelper.DownloadRecord r : list) remove(r.itemGuid);
    }

    /**
     * 重取流重建任务。
     * removeOld=true（NAS 转码）：会话路径可能变化，先移除旧任务（已下载分段作废）；
     * removeOld=false（云直链）：路径稳定仅签名 query 变化，直接 addDownload 合并新 URI，
     * 缓存键去 query 后不变，已下载数据保留续传。
     */
    private void reenqueueWithFreshStream(DownloadDbHelper.DownloadRecord rec, boolean removeOld) {
        new Thread(() -> {
            try {
                QualityParams qp = new Gson().fromJson(rec.qualityJson, QualityParams.class);
                if (qp == null) qp = new QualityParams();
                qp.label = rec.qualityLabel != null ? rec.qualityLabel : "转码";
                if (removeOld) downloadManager.removeDownload(rec.itemGuid);
                String err = resolveAndEnqueue(rec, qp, 0);
                Log.d(TAG, "重取流入队: " + rec.itemGuid + (err == null ? " ok" : " " + err));
            } catch (Exception e) {
                Log.e(TAG, "重取流失败", e);
            }
        }).start();
    }

    // ==================== 仅 WiFi（2.4） ====================

    /** 应用启动恢复未完成任务（spec 断点续传 / 7.2 杀进程续传）：ACTION_INIT → resumeDownloads()。
     *  仅在前台 UI 时机调用；服务空转时自行 stopSelf，不常驻。 */
    public void resumePendingDownloads() {
        try {
            DownloadService.start(appContext, OfflineDownloadService.class);
        } catch (Exception e) {
            Log.w(TAG, "恢复下载任务失败: " + e.getMessage());
        }
    }

    public boolean isWifiOnly() {
        return appContext.getSharedPreferences("fntv_prefs", 0).getBoolean("offline_wifi_only", true);
    }

    public void setWifiOnly(boolean wifiOnly) {
        appContext.getSharedPreferences("fntv_prefs", 0)
                .edit().putBoolean("offline_wifi_only", wifiOnly).apply();
        applyWifiOnlyRequirement();
        notifyStateChanged();
    }

    private void applyWifiOnlyRequirement() {
        if (downloadManager == null) return;
        Requirements req = isWifiOnly()
                ? new Requirements(Requirements.NETWORK_UNMETERED)
                : new Requirements(0);
        downloadManager.setRequirements(req);
    }

    // ==================== 实时进度轮询（页面共享） ====================

    /** 每任务进度快照 {bytes, 时间戳ms, 速度B/s} */
    private final java.util.LinkedHashMap<String, long[]> liveProgress = new java.util.LinkedHashMap<>();

    /**
     * 轮询下载引擎实时进度并合入元数据库。ExoPlayer 监听器可能在整块完成时才回调，
     * 只靠 DB 展示会让进度/速度看起来"卡住"；各页面按需 500ms 调一次本方法。
     *
     * @return guid → [bytes, speedB/s]，仅含进行中的任务
     */
    public java.util.Map<String, long[]> pollLiveProgress() {
        java.util.Map<String, long[]> out = new java.util.LinkedHashMap<>();
        try {
            long now = System.currentTimeMillis();
            for (Download d : downloadManager.getCurrentDownloads()) {
                String id = d.request.id;
                long bytes = d.getBytesDownloaded();
                long[] prev = liveProgress.get(id);
                long speed;
                if (prev == null || now <= prev[1]) {
                    speed = prev != null ? prev[2] : 0;
                } else if (bytes > prev[0]) {
                    speed = (bytes - prev[0]) * 1000 / (now - prev[1]);
                } else {
                    speed = (now - prev[1] < 3000 && prev[2] > 0) ? prev[2] : 0; // 3 秒无增量归 0
                }
                liveProgress.put(id, new long[]{bytes, now, speed});
                out.put(id, new long[]{bytes, speed});
                DownloadDbHelper.DownloadRecord rec = db.get(id);
                if (rec != null) {
                    // 字节单调：App 重启后 DownloadManager 从 ExoPlayer 自身索引恢复，
                    // 其 bytes_downloaded 检查点滞后于实际缓存，禁止用旧值回写覆盖新值
                    long nb = Math.max(bytes, rec.bytes);
                    if (nb > rec.bytes || rec.state != d.state) {
                        db.updateState(id, d.state, nb);
                    }
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ==================== 缓存目录选择 ====================

    /** 缓存目录候选项（设置弹窗用） */
    public static class CacheDirOption {
        public final String label;
        /** 该卷的 App 专属 files 目录；"" 表示自动选择 */
        public final String filesDir;
        public final long freeBytes;
        public final long totalBytes;
        public final boolean current;

        public CacheDirOption(String label, String filesDir, long freeBytes, long totalBytes, boolean current) {
            this.label = label;
            this.filesDir = filesDir;
            this.freeBytes = freeBytes;
            this.totalBytes = totalBytes;
            this.current = current;
        }
    }

    /** 列出可用存储卷（内部存储 + 已挂载外置卡）及剩余空间 */
    public List<CacheDirOption> listCacheDirOptions() {
        ensureInitialized();
        List<CacheDirOption> result = new java.util.ArrayList<>();
        String pref = appContext.getSharedPreferences("fntv_prefs", 0)
                .getString("offline_cache_dir", "");
        File curParent = cacheDir != null ? cacheDir.getParentFile() : null;
        result.add(new CacheDirOption("自动（默认）", "", 0, 0, pref.isEmpty()));
        File[] dirs = androidx.core.content.ContextCompat.getExternalFilesDirs(appContext, null);
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (File f : dirs) {
            if (f == null) continue;
            String path = f.getAbsolutePath();
            if (!seen.add(path)) continue;
            String label;
            if (path.contains("/storage/emulated/")) {
                label = "内部存储";
            } else {
                String seg = path;
                int idx = seg.indexOf("/storage/");
                if (idx >= 0) {
                    seg = seg.substring(idx + "/storage/".length());
                    int slash = seg.indexOf('/');
                    if (slash > 0) seg = seg.substring(0, slash);
                }
                label = "外置存储 " + seg;
            }
            long free = 0, total = 0;
            try {
                StatFs st = new StatFs(path);
                free = st.getAvailableBytes();
                total = st.getTotalBytes();
            } catch (Exception ignored) {}
            boolean current = !pref.isEmpty() && curParent != null && curParent.getAbsolutePath().equals(path);
            result.add(new CacheDirOption(label, path, free, total, current));
        }
        return result;
    }

    /** 当前缓存目录（UI 展示用） */
    public File getCacheDir() {
        ensureInitialized();
        return cacheDir;
    }

    /** 选项对应的具体缓存路径（含 offline_cache 子目录），展示用——让用户知道文件实际落在哪 */
    public String cacheDirPathFor(CacheDirOption o) {
        try {
            File base = o == null || o.filesDir.isEmpty() ? autoCacheDir() : new File(o.filesDir);
            return new File(base, "offline_cache").getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 切换缓存目录（后台线程调用）。newFilesDir="" 表示恢复自动选择。
     * 有排队/下载中任务时拒绝；已暂停/已完成任务的缓存数据随目录迁移。
     *
     * @return null=成功；否则错误信息
     */
    public synchronized String switchCacheDir(String newFilesDir) {
        ensureInitialized();
        String pref = newFilesDir == null ? "" : newFilesDir;
        // 1. 活动任务：自动暂停并等待停稳后再迁移（不再硬性拒绝，
        //    避免一个卡住/排队的任务把目录切换永久锁死）
        List<String> toPause = new java.util.ArrayList<>();
        for (DownloadDbHelper.DownloadRecord r : db.getAll()) {
            if (r.state == Download.STATE_QUEUED || r.state == Download.STATE_DOWNLOADING
                    || r.state == Download.STATE_RESTARTING) {
                toPause.add(r.itemGuid);
            }
        }
        if (downloadManager != null) {
            for (String id : toPause) downloadManager.setStopReason(id, STOP_REASON_USER);
            // 等待下载/移除真正停稳（最多 8s），确保迁移期间没有缓存写入
            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline) {
                boolean busy = false;
                for (Download d : downloadManager.getCurrentDownloads()) {
                    if (d.state == Download.STATE_DOWNLOADING || d.state == Download.STATE_RESTARTING
                            || d.state == Download.STATE_REMOVING) { busy = true; break; }
                }
                if (!busy) break;
                try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        // 2. 目标目录
        File target = pref.isEmpty() ? autoCacheDir() : new File(pref, "offline_cache");
        if (!(target.exists() || target.mkdirs()) || !target.canWrite()) {
            return "目标目录不可写: " + target.getAbsolutePath();
        }
        if (cacheDir != null && cacheDir.getAbsolutePath().equals(target.getAbsolutePath())) {
            appContext.getSharedPreferences("fntv_prefs", 0)
                    .edit().putString("offline_cache_dir", pref).apply();
            return null;
        }
        // 3. 停服务并释放旧实例（SimpleCache 持有文件锁，必须先释放再迁移）
        try {
            appContext.stopService(new android.content.Intent(appContext, OfflineDownloadService.class));
        } catch (Exception ignored) {}
        File oldDir = cacheDir;
        try {
            if (downloadManager != null) downloadManager.release();
            if (cache != null) cache.release();
        } catch (Exception e) {
            Log.w(TAG, "释放下载实例异常: " + e.getMessage());
        }
        downloadManager = null;
        cache = null;
        initialized = false;
        // 4. 迁移已有缓存
        if (oldDir != null && oldDir.exists() && !oldDir.getAbsolutePath().equals(target.getAbsolutePath())) {
            boolean moved = oldDir.renameTo(target);
            if (!moved) {
                try {
                    copyDir(oldDir, target);
                    deleteDir(oldDir);
                    moved = true;
                } catch (Exception e) {
                    Log.e(TAG, "缓存迁移失败", e);
                    ensureInitialized(); // 回退旧目录
                    return "缓存迁移失败: " + e.getMessage();
                }
            }
        }
        // 5. 持久化并重建
        appContext.getSharedPreferences("fntv_prefs", 0)
                .edit().putString("offline_cache_dir", pref).apply();
        ensureInitialized();
        notifyStateChanged();
        Log.d(TAG, "缓存目录已切换: " + (cacheDir != null ? cacheDir.getAbsolutePath() : "?"));
        return null;
    }

    private static void copyDir(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) throw new java.io.IOException("无法创建目录 " + dst);
            File[] children = src.listFiles();
            if (children != null) for (File c : children) copyDir(c, new File(dst, c.getName()));
        } else {
            java.io.InputStream in = new java.io.FileInputStream(src);
            try {
                java.io.OutputStream out = new java.io.FileOutputStream(dst);
                try {
                    byte[] buf = new byte[1024 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally { out.close(); }
            } finally { in.close(); }
        }
    }

    private static void deleteDir(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteDir(c);
        }
        f.delete();
    }

    // ==================== 状态查询（5.1 / 4.1 配套） ====================

    /** 某集的缓存状态：DB 镜像优先，miss 时查下载索引 */
    public DownloadDbHelper.DownloadRecord getRecord(String itemGuid) {
        return db.get(itemGuid);
    }

    public boolean isCached(String itemGuid) {
        DownloadDbHelper.DownloadRecord r = db.get(itemGuid);
        return r != null && r.state == Download.STATE_COMPLETED;
    }

    /** 是否存在排队中/下载中/已暂停的任务（4.2 批量缓存时跳过） */
    public boolean hasActiveTask(String itemGuid) {
        DownloadDbHelper.DownloadRecord r = db.get(itemGuid);
        if (r == null) return false;
        switch (r.state) {
            case Download.STATE_QUEUED:
            case Download.STATE_DOWNLOADING:
            case Download.STATE_STOPPED:
            case Download.STATE_REMOVING:
            case Download.STATE_RESTARTING:
                return true;
            default:
                return false;
        }
    }

    /**
     * 离线自动连播：取同剧下一已完成集（7.1）。
     * 同季取集号更大的下一集；没有则跨到更大的季取第一集；都没有返回 null。
     */
    public DownloadDbHelper.DownloadRecord nextCachedEpisode(String currentGuid, String tvTitle) {
        if (tvTitle == null || tvTitle.isEmpty()) return null;
        DownloadDbHelper.DownloadRecord cur = currentGuid != null ? db.get(currentGuid) : null;
        int season = cur != null ? cur.seasonNum : 0;
        int ep = cur != null ? cur.episodeNum : 0;
        List<DownloadDbHelper.DownloadRecord> list = db.getByTvTitle(tvTitle);
        DownloadDbHelper.DownloadRecord nextSeasonFirst = null;
        for (DownloadDbHelper.DownloadRecord r : list) {
            if (r.state != Download.STATE_COMPLETED) continue;
            if (cur == null) return r;
            if (r.seasonNum == season && r.episodeNum > ep) return r;
            if (r.seasonNum > season && nextSeasonFirst == null) nextSeasonFirst = r;
        }
        return nextSeasonFirst;
    }

    /** strm/云盘内容识别（此类内容仅在有 directLinkQualities 直链时可缓存） */
    public static boolean isStrmContent(StreamResponse stream) {
        if (stream.cloudStorageInfo != null) return true;
        StreamResponse.FileStreamInfo fs = stream.fileStream;
        return fs != null && fs.path != null && fs.path.toLowerCase().endsWith(".strm");
    }

    /** 按清晰度参数匹配云直链档位：分辨率+码率精确 → 仅分辨率 → 首个可用直链 */
    private static StreamResponse.DirectLinkQuality matchDirectQuality(StreamResponse stream, QualityParams qp) {
        if (stream.directLinkQualities == null || stream.directLinkQualities.isEmpty()) return null;
        StreamResponse.DirectLinkQuality resMatch = null;
        StreamResponse.DirectLinkQuality first = null;
        for (StreamResponse.DirectLinkQuality q : stream.directLinkQualities) {
            if (q == null || q.url == null || q.url.isEmpty()) continue;
            if (first == null) first = q;
            boolean resEq = qp.resolution != null && qp.resolution.equals(q.resolution);
            if (resEq && q.bitrate == qp.bitrate) return q;
            if (resEq && resMatch == null) resMatch = q;
        }
        return resMatch != null ? resMatch : first;
    }

    /** play/info 同步解析 mediaGuid（后台线程调用），失败返回 null */
    public String resolveMediaGuid(String itemGuid) {
        try {
            Map<String, String> b = new HashMap<>();
            b.put("item_guid", itemGuid);
            retrofit2.Response<ApiResponse<PlayInfoResponse>> infoResp =
                    FnApiManager.getInstance().getApi().getPlayInfo(b).execute();
            if (infoResp.isSuccessful() && infoResp.body() != null && infoResp.body().code == 0
                    && infoResp.body().data != null && infoResp.body().data.mediaGuid != null) {
                return infoResp.body().data.mediaGuid;
            }
        } catch (Exception e) {
            Log.w(TAG, "play/info 失败: " + e.getMessage());
        }
        return null;
    }

    /** 空间预检（2.5）：可用空间须大于 预计体积 + 200MB（按实际缓存目录所在卷） */
    public String checkSpace(long estimatedBytes) {
        File probe = cacheDir != null ? cacheDir : appContext.getExternalFilesDir(null);
        if (probe == null) probe = appContext.getFilesDir();
        try {
            StatFs st = new StatFs(probe.getAbsolutePath());
            long available = st.getAvailableBytes();
            if (available < estimatedBytes + SPACE_MARGIN) {
                return "存储空间不足（需要约 " + formatSize(estimatedBytes + SPACE_MARGIN)
                        + "，可用 " + formatSize(available) + "）";
            }
        } catch (Exception e) {
            Log.w(TAG, "空间检查失败: " + e.getMessage());
        }
        return null;
    }

    // ==================== 监听 ====================

    public void addListener(StateListener l) {
        synchronized (listeners) {
            if (!listeners.contains(l)) listeners.add(l);
        }
    }

    public void removeListener(StateListener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    private void notifyStateChanged() {
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(() -> {
            synchronized (listeners) {
                for (StateListener l : listeners) {
                    try {
                        l.onOfflineStateChanged();
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    // ==================== 内部工具 ====================

    /** Download 状态同步到元数据库 */
    private void syncDownloadToDb(Download download, Exception finalException) {
        String id = download.request.id;
        DownloadDbHelper.DownloadRecord rec = db.get(id);
        if (rec == null) {
            // 元数据已删（删除流程竞态）且索引进入移除中：不补记录，等 onDownloadRemoved 收尾。
            // 仅非移除态（如卸载残留索引）才补一条最简记录
            if (download.state == Download.STATE_REMOVING) return;
            rec = new DownloadDbHelper.DownloadRecord();
            rec.itemGuid = id;
            rec.state = download.state;
            rec.bytes = download.getBytesDownloaded();
            db.upsert(rec);
            return;
        }
        // 字节单调（同 pollLiveProgress）：监听器回调的字节不小于库中已有值才整体更新
        long nb = Math.max(download.getBytesDownloaded(), rec.bytes);
        db.updateState(id, download.state, nb);

        // 失败原因落库（列表展示用）；回到非失败态时清除
        if (download.state == Download.STATE_FAILED) {
            String reason = finalException != null && finalException.getMessage() != null
                    ? finalException.getMessage()
                    : ("失败码 " + download.failureReason);
            db.updateLastError(id, reason);
            Log.w(TAG, "下载失败: " + id + " reason=" + download.failureReason
                    + (finalException != null ? " exc=" + finalException.getMessage() : ""));
        } else if (download.state == Download.STATE_DOWNLOADING
                || download.state == Download.STATE_COMPLETED
                || download.state == Download.STATE_QUEUED) {
            db.updateLastError(id, "");
        }

        // 首次进入下载中 → 触发弹幕离线获取（3.1，失败不阻塞）
        if (download.state == Download.STATE_DOWNLOADING && rec.danmuState != null
                && "pending".equals(rec.danmuState)) {
            fetchDanmuAsync(rec);
        }
    }

    private void fetchDanmuAsync(DownloadDbHelper.DownloadRecord rec) {
        new Thread(() -> {
            try {
                // 与播放页 matchName 构造一致：剧名 + SxxEyy
                String matchTitle = rec.tvTitle;
                if (rec.episodeNum > 0 && matchTitle != null && !matchTitle.isEmpty()) {
                    matchTitle = matchTitle + " S" + String.format("%02d", rec.seasonNum > 0 ? rec.seasonNum : 1)
                            + "E" + String.format("%02d", rec.episodeNum);
                }
                android.content.SharedPreferences prefs =
                        appContext.getSharedPreferences("fntv_prefs", 0);
                DanmuOfflineFetcher.Result r = DanmuOfflineFetcher.fetch(prefs, matchTitle, rec.tvTitle);
                if (r.success) {
                    File dir = new File(appContext.getFilesDir(), "danmu");
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, rec.itemGuid + ".danmu.json");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                    fos.write(r.rawJson.getBytes("UTF-8"));
                    fos.close();
                    db.updateDanmu(rec.itemGuid, f.getAbsolutePath(), "ok");
                    Log.d(TAG, "弹幕已缓存: " + rec.itemGuid + " -> " + f.getAbsolutePath());
                } else {
                    db.updateDanmu(rec.itemGuid, null, "failed");
                    Log.w(TAG, "弹幕获取失败(不阻塞): " + rec.itemGuid + " " + r.error);
                }
                notifyStateChanged();
            } catch (Exception e) {
                Log.e(TAG, "弹幕离线获取异常", e);
                db.updateDanmu(rec.itemGuid, null, "failed");
            }
        }).start();
    }

    private void deleteDanmuFile(String itemGuid) {
        try {
            File f = new File(new File(appContext.getFilesDir(), "danmu"), itemGuid + ".danmu.json");
            if (f.exists()) f.delete();
        } catch (Exception ignored) {}
    }

    /** HEAD 探测原画 Content-Length（2.5 / 4.3 体积预估）。
     *  必须带有限超时：streamClient 全程 0 超时，服务端不回包时会永久挂起入队线程。 */
    public long probeContentLength(String url) {
        Response resp = null;
        try {
            OkHttpClient client = FnApiManager.getInstance().getStreamClient().newBuilder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .callTimeout(15, TimeUnit.SECONDS)
                    .build();
            Request req = new Request.Builder().url(url).head().build();
            resp = client.newCall(req).execute();
            if (resp.isSuccessful()) {
                String len = resp.header("Content-Length");
                if (len != null) return Long.parseLong(len);
                String cr = resp.header("Content-Range");
                if (cr != null && cr.contains("/")) {
                    String total = cr.substring(cr.lastIndexOf('/') + 1);
                    if (!"*".equals(total)) return Long.parseLong(total);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "HEAD 探测失败: " + e.getMessage());
        } finally {
            if (resp != null) resp.close();
        }
        return 0;
    }

    /** getStream 同步调用（转码档信息 / 4.3 弹窗选项） */
    public StreamResponse fetchStream(String mediaGuid) throws Exception {
        Map<String, Object> body = new HashMap<>();
        Map<String, Object> header = new HashMap<>();
        header.put("User-Agent", new String[]{"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"});
        body.put("header", header);
        body.put("media_guid", mediaGuid);
        body.put("level", 1);
        android.content.SharedPreferences prefs = appContext.getSharedPreferences("fntv_prefs", 0);
        String account = prefs.getString("user", "video");
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(account.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte d : digest) sb.append(String.format("%02x", d & 0xff));
            body.put("ip", sb.toString());
        } catch (Exception e) {
            body.put("ip", "");
        }
        retrofit2.Response<ApiResponse<StreamResponse>> resp =
                FnApiManager.getInstance().getApi().getStream(body).execute();
        if (resp.isSuccessful() && resp.body() != null && resp.body().code == 0 && resp.body().data != null) {
            return resp.body().data;
        }
        return null;
    }

    /** play/play 同步取转码 playLink（与 QualitySelectHelper.switchToQuality 同参） */
    private String fetchPlayLink(String mediaGuid, StreamResponse stream, QualityParams qp) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("media_guid", mediaGuid);
        if (stream.videoStream != null) {
            body.put("video_guid", stream.videoStream.guid != null ? stream.videoStream.guid : "");
            body.put("video_encoder", stream.videoStream.codecName != null ? stream.videoStream.codecName : "");
        }
        body.put("resolution", qp.resolution);
        body.put("bitrate", qp.bitrate);
        body.put("startTimestamp", 0L);
        if (stream.audioStreams != null && !stream.audioStreams.isEmpty()) {
            StreamResponse.AudioStreamInfo a = stream.audioStreams.get(0);
            body.put("audio_encoder", a.codecName != null ? a.codecName : "");
            body.put("audio_guid", a.guid != null ? a.guid : "");
            body.put("channels", a.channels);
        }
        if (stream.subtitleStreams != null && !stream.subtitleStreams.isEmpty()) {
            body.put("subtitle_guid", stream.subtitleStreams.get(0).guid != null
                    ? stream.subtitleStreams.get(0).guid : "");
        }
        retrofit2.Response<ApiResponse<PlayLinkResponse>> resp =
                FnApiManager.getInstance().getApi().getPlayLink(body).execute();
        if (resp.isSuccessful() && resp.body() != null && resp.body().code == 0
                && resp.body().data != null && resp.body().data.playLink != null
                && !resp.body().data.playLink.isEmpty()) {
            return resp.body().data.playLink;
        }
        return null;
    }

    private String getBaseUrl() {
        return appContext.getSharedPreferences("fntv_prefs", 0)
                .getString("host", "").replaceAll("/+$", "");
    }

    public static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format("%.2f GB", bytes / 1073741824f);
        if (bytes >= 1024 * 1024) return String.format("%.1f MB", bytes / 1048576f);
        return String.format("%.0f KB", bytes / 1024f);
    }
}
