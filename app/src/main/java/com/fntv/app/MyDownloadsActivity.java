package com.fntv.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.fntv.app.api.FnApiManager;
import com.fntv.app.util.SimpleImageLoader;
import com.google.android.exoplayer2.offline.Download;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 我的下载页（5.1~5.3）：
 * - 按剧名分组展示全部缓存任务：海报/集数/清晰度/实时进度/状态
 * - 单集操作：暂停/继续/失败重试/播放/删除（清缓存+弹幕+元数据）
 * - 整季操作：删除整季
 * - 进度实时刷新：OfflineManager 状态回调（带节流）
 */
public class MyDownloadsActivity extends AppCompatActivity {

    private static final String TAG = "MyDownloads";

    private LinearLayout listContainer;
    private TextView tvEmpty, tvWifiOnly, tvCacheDir, tvCachePath;
    private OfflineManager om;
    private OfflineManager.StateListener listener;
    private FnApiManager apiManager;
    private String baseUrl = "";

    private long lastRenderAt = 0;
    /** 本轮渲染的实时进度 guid → [bytes, speedB/s]（OfflineManager.pollLiveProgress） */
    private Map<String, long[]> liveProgress = java.util.Collections.emptyMap();
    /** 进度轮询：DownloadManager 监听器可能在整块完成时才回调，轮询兜底让字节/百分比持续走动 */
    private final android.os.Handler uiHandler = new android.os.Handler(
            android.os.Looper.getMainLooper());
    private final Runnable progressPoller = new Runnable() {
        @Override public void run() {
            // 任何异常都不允许终止轮询（否则 postDelayed 失联，页面永久冻结）
            try {
                pollAndRefresh();
            } catch (Exception e) {
                android.util.Log.e("MyDL", "pollAndRefresh error", e);
            }
            uiHandler.postDelayed(this, 500);
        }
    };

    /** 结构签名（guid+状态+弹幕态+清晰度）：字节/速度变化不触发重建 */
    private String lastStructSig = "";
    /** guid → 状态行 TextView（轮询就地刷新用） */
    private final Map<String, TextView> statusViews = new LinkedHashMap<>();
    /** 剧名 → "N 集 · 体积" TextView（轮询就地刷新用） */
    private final Map<String, TextView> groupSubViews = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my_downloads);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
        }

        SharedPreferences prefs = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        baseUrl = prefs.getString("host", "").replaceAll("/+$", "");
        apiManager = FnApiManager.getInstance();

        // 主线程首调（DownloadManager 回调绑定主线程 Looper）
        om = OfflineManager.getInstance(this);
        om.ensureInitialized();

        listContainer = findViewById(R.id.listContainer);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvWifiOnly = findViewById(R.id.tvWifiOnly);
        Button btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        tvWifiOnly.setOnClickListener(v -> {
            boolean next = !om.isWifiOnly();
            om.setWifiOnly(next);
            Toast.makeText(this, next ? "仅 WiFi 下载已开启" : "允许移动网络下载（注意流量）",
                    Toast.LENGTH_SHORT).show();
            updateWifiOnlyLabel();
        });
        updateWifiOnlyLabel();

        tvCacheDir = findViewById(R.id.tvCacheDir);
        tvCacheDir.setOnClickListener(v -> showCacheDirDialog());
        tvCachePath = findViewById(R.id.tvCachePath);
        tvCachePath.setOnClickListener(v -> showCacheDirDialog());
        updateCacheDirLabel();

        listContainer.setFocusable(true);
        listContainer.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
    }

    private void updateWifiOnlyLabel() {
        boolean on = om.isWifiOnly();
        tvWifiOnly.setText(on ? "仅WiFi: 开" : "仅WiFi: 关");
        tvWifiOnly.setTextColor(on ? 0xFF81C784 : 0xFFFFB74D);
    }

    private void updateCacheDirLabel() {
        String label = "自动";
        try {
            for (OfflineManager.CacheDirOption o : om.listCacheDirOptions()) {
                if (o.current) { label = o.filesDir.isEmpty() ? "自动" : o.label; break; }
            }
        } catch (Exception ignored) {}
        tvCacheDir.setText("缓存目录:" + label);
        // 常显当前真实落盘路径
        try {
            tvCachePath.setText("存储位置: " + om.getCacheDir().getAbsolutePath());
        } catch (Exception e) {
            tvCachePath.setText("存储位置: 未知");
        }
    }

    /** 缓存目录选择：列出各存储卷剩余空间与真实路径；切换时迁移已有缓存（活动任务会被拦截） */
    private void showCacheDirDialog() {
        final List<OfflineManager.CacheDirOption> opts = om.listCacheDirOptions();
        String[] items = new String[opts.size()];
        for (int i = 0; i < opts.size(); i++) {
            OfflineManager.CacheDirOption o = opts.get(i);
            StringBuilder sb = new StringBuilder(o.label);
            if (!o.filesDir.isEmpty()) {
                sb.append("  可用 ").append(OfflineManager.formatSize(o.freeBytes));
                if (o.totalBytes > 0) sb.append(" / ").append(OfflineManager.formatSize(o.totalBytes));
            }
            if (o.current) sb.append("  ✓当前");
            // 第二行显示真实落盘路径——"内部存储"这类标签对用户等于没说
            String path = om.cacheDirPathFor(o);
            if (!path.isEmpty()) sb.append("\n").append(path);
            items[i] = sb.toString();
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("缓存目录")
                .setItems(items, (dialog, which) -> {
                    if (which < 0 || which >= opts.size()) return;
                    final OfflineManager.CacheDirOption sel = opts.get(which);
                    if (sel.current) return;
                    Toast.makeText(this, "正在切换缓存目录…", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        final String err = om.switchCacheDir(sel.filesDir);
                        runOnUiThread(() -> {
                            Toast.makeText(this, err == null ? "缓存目录已切换" : err,
                                    Toast.LENGTH_LONG).show();
                            updateCacheDirLabel();
                        });
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            render();
        } catch (Exception e) {
            android.util.Log.e("MyDL", "onResume render error", e);
        }
        updateCacheDirLabel();
        listener = this::renderThrottled;
        om.addListener(listener);
        uiHandler.removeCallbacks(progressPoller);
        uiHandler.postDelayed(progressPoller, 500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(progressPoller);
        if (listener != null) {
            om.removeListener(listener);
            listener = null;
        }
    }

    /** 进度回调较频繁，节流 500ms 全量重绘 */
    private void renderThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastRenderAt < 500) return;
        lastRenderAt = now;
        runOnUiThread(() -> {
            try {
                render();
            } catch (Exception e) {
                android.util.Log.e("MyDL", "render error", e);
            }
        });
    }

    private void render() {
        statusViews.clear();
        groupSubViews.clear();
        listContainer.removeAllViews();
        List<DownloadDbHelper.DownloadRecord> all = om.getDb().getAll();
        if (all.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        tvEmpty.setVisibility(View.GONE);

        // 实时进度兜底：轮询下载引擎字节/速度并同步进 DB（监听器可能到块完成才回调）
        liveProgress = om.pollLiveProgress();

        // 按剧名分组（getAll 按 created_at 倒序，组序随最新任务）
        Map<String, List<DownloadDbHelper.DownloadRecord>> groups = new LinkedHashMap<>();
        for (DownloadDbHelper.DownloadRecord r : all) {
            String key = r.tvTitle != null && !r.tvTitle.isEmpty() ? r.tvTitle : r.title;
            if (!groups.containsKey(key)) groups.put(key, new ArrayList<>());
            groups.get(key).add(r);
        }

        for (Map.Entry<String, List<DownloadDbHelper.DownloadRecord>> e : groups.entrySet()) {
            List<DownloadDbHelper.DownloadRecord> eps = e.getValue();
            listContainer.addView(makeGroupHeader(e.getKey(), eps));
            for (DownloadDbHelper.DownloadRecord r : eps) {
                listContainer.addView(makeEpisodeRow(r));
            }
            listContainer.addView(makeSpacer(12));
        }
        lastStructSig = buildStructSig();
    }

    /** 轮询入口：结构未变只就地刷新文字，避免 500ms 全量重建把用户点击吞掉 */
    private void pollAndRefresh() {
        liveProgress = om.pollLiveProgress();
        if (!buildStructSig().equals(lastStructSig)) {
            render();
            return;
        }
        refreshTextsInPlace();
    }

    /** 结构签名：新增/删除/状态迁移/弹幕态变化才整体重建 */
    private String buildStructSig() {
        StringBuilder sb = new StringBuilder();
        try {
            for (DownloadDbHelper.DownloadRecord r : om.getDb().getAll()) {
                sb.append(r.itemGuid).append(':').append(r.state).append(':')
                        .append(r.danmuState == null ? "" : r.danmuState).append(':')
                        .append(r.qualityLabel == null ? "" : r.qualityLabel).append(';');
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    /** 就地刷新行状态文字与分组合计（不重建视图，点击不受影响） */
    private void refreshTextsInPlace() {
        if (statusViews.isEmpty() && groupSubViews.isEmpty()) return;
        try {
            Map<String, List<DownloadDbHelper.DownloadRecord>> groups = new LinkedHashMap<>();
            for (DownloadDbHelper.DownloadRecord r : om.getDb().getAll()) {
                String key = r.tvTitle != null && !r.tvTitle.isEmpty() ? r.tvTitle : r.title;
                if (!groups.containsKey(key)) groups.put(key, new ArrayList<>());
                groups.get(key).add(r);
                TextView st = statusViews.get(r.itemGuid);
                if (st != null) st.setText(stateText(r));
            }
            for (Map.Entry<String, List<DownloadDbHelper.DownloadRecord>> e : groups.entrySet()) {
                TextView sub = groupSubViews.get(e.getKey());
                if (sub != null) sub.setText(e.getValue().size() + " 集 · "
                        + OfflineManager.formatSize(totalBytes(e.getValue())));
            }
        } catch (Exception ignored) {}
    }

    // ==================== 分组头 ====================

    private View makeGroupHeader(String tvTitle, List<DownloadDbHelper.DownloadRecord> eps) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundResource(R.drawable.bg_media_card);
        header.setPadding(12, 10, 12, 10);
        header.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 海报
        String posterPath = null;
        for (DownloadDbHelper.DownloadRecord r : eps) {
            if (r.poster != null && !r.poster.isEmpty()) { posterPath = r.poster; break; }
        }
        RoundedImageView poster = new RoundedImageView(this);
        int px = (int) (44 * getResources().getDisplayMetrics().density);
        poster.setLayoutParams(new LinearLayout.LayoutParams((int) (px * 0.72f), px));
        poster.setScaleType(ImageView.ScaleType.FIT_XY);
        poster.setBackgroundColor(0xFF1A1A1A);
        poster.setCornerRadius(6);
        if (posterPath != null) {
            String url = baseUrl + "/v/api/v1/sys/img"
                    + (posterPath.startsWith("/") ? posterPath : "/" + posterPath) + "?w=200";
            SimpleImageLoader.load(url, poster, apiManager.getClient());
        }
        header.addView(poster);

        LinearLayout info = new LinearLayout(this);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(12, 0, 0, 0);
        TextView tvName = new TextView(this);
        tvName.setTextSize(15);
        tvName.setTextColor(0xFFEEEEEE);
        tvName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvName.setSingleLine(true);
        tvName.setEllipsize(TextUtils.TruncateAt.END);
        int seasonNum = eps.get(0).seasonNum;
        tvName.setText(tvTitle + (seasonNum > 0 ? "  第" + seasonNum + "季" : ""));
        info.addView(tvName);
        TextView sub = new TextView(this);
        sub.setTextSize(12);
        sub.setTextColor(0xFF808080);
        sub.setText(eps.size() + " 集 · " + OfflineManager.formatSize(totalBytes(eps)));
        info.addView(sub);
        groupSubViews.put(tvTitle, sub);
        header.addView(info);

        // 删除整季（5.3）
        Button btnDelSeason = new Button(this);
        btnDelSeason.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 64));
        btnDelSeason.setBackgroundResource(R.drawable.bg_input);
        btnDelSeason.setText("删除整季");
        btnDelSeason.setTextColor(0xFFE57373);
        btnDelSeason.setTextSize(12);
        btnDelSeason.setFocusable(true);
        btnDelSeason.setPadding(14, 0, 14, 0);
        btnDelSeason.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle("删除整季")
                .setMessage("删除《" + tvTitle + "》的 " + eps.size()
                        + " 集缓存（含弹幕文件与下载记录）？")
                .setPositiveButton("删除", (d, w) -> om.removeByTvTitle(tvTitle))
                .setNegativeButton("取消", null)
                .show());
        header.addView(btnDelSeason);
        return header;
    }

    private long totalBytes(List<DownloadDbHelper.DownloadRecord> eps) {
        // 全部用实际字节：completed 的 bytes 是下载完成时的真实缓存量；
        // 进行中/暂停的 bytes 是已下载量（不混入不可靠的预估体积）
        long n = 0;
        for (DownloadDbHelper.DownloadRecord r : eps) {
            n += r.bytes;
        }
        return n;
    }

    // ==================== 单集行 ====================

    private View makeEpisodeRow(DownloadDbHelper.DownloadRecord r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundResource(R.drawable.bg_media_card);
        row.setPadding(16, 12, 12, 12);
        row.setFocusable(true);
        row.setMinimumHeight(56);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 4;
        row.setLayoutParams(lp);

        LinearLayout info = new LinearLayout(this);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(0, 0, 8, 0);

        TextView title = new TextView(this);
        title.setTextSize(14);
        title.setTextColor(0xFFEEEEEE);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText("EP" + (r.episodeNum > 0 ? r.episodeNum : "?") + "  "
                + (r.title != null ? r.title : "") + "  ·  " + r.qualityLabel);
        info.addView(title);

        TextView status = new TextView(this);
        status.setTextSize(12);
        status.setText(stateText(r));
        statusViews.put(r.itemGuid, status);
        switch (r.state) {
            case Download.STATE_DOWNLOADING:
            case Download.STATE_QUEUED:
                status.setTextColor(0xFF64B5F6);
                break;
            case Download.STATE_COMPLETED:
                status.setTextColor(0xFF81C784);
                break;
            case Download.STATE_FAILED:
                status.setTextColor(0xFFE57373);
                break;
            default:
                status.setTextColor(0xFF808080);
                break;
        }
        info.addView(status);
        row.addView(info);

        // 主操作：暂停/继续/重试/播放（5.2）
        Button btnAction = new Button(this);
        btnAction.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 64));
        btnAction.setBackgroundResource(R.drawable.bg_btn_primary);
        btnAction.setTextColor(0xFFEEEEEE);
        btnAction.setTextSize(12);
        btnAction.setFocusable(true);
        btnAction.setPadding(14, 0, 14, 0);
        switch (r.state) {
            case Download.STATE_DOWNLOADING:
            case Download.STATE_QUEUED:
                btnAction.setText("暂停");
                btnAction.setOnClickListener(v -> om.pause(r.itemGuid));
                break;
            case Download.STATE_STOPPED:
                btnAction.setText("继续");
                btnAction.setOnClickListener(v -> {
                    Toast.makeText(this, "继续下载…", Toast.LENGTH_SHORT).show();
                    om.resume(r.itemGuid);
                });
                break;
            case Download.STATE_FAILED:
                btnAction.setText("重试");
                btnAction.setOnClickListener(v -> {
                    Toast.makeText(this, "重试中…", Toast.LENGTH_SHORT).show();
                    om.retry(r.itemGuid);
                });
                break;
            case Download.STATE_COMPLETED:
                btnAction.setText("播放");
                btnAction.setOnClickListener(v -> launchOffline(r));
                break;
            default:
                btnAction.setText("…");
                btnAction.setEnabled(false);
                break;
        }
        row.addView(btnAction);

        // 删除单集（5.3）
        Button btnDel = new Button(this);
        btnDel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 64));
        btnDel.setBackgroundResource(R.drawable.bg_input);
        btnDel.setText("删除");
        btnDel.setTextColor(0xFFE57373);
        btnDel.setTextSize(12);
        btnDel.setFocusable(true);
        btnDel.setPadding(14, 0, 14, 0);
        btnDel.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle("删除缓存")
                .setMessage("删除 " + (r.title != null ? r.title : "该集")
                        + " 的缓存数据、弹幕文件与下载记录？")
                .setPositiveButton("删除", (d, w) -> om.remove(r.itemGuid))
                .setNegativeButton("取消", null)
                .show());
        row.addView(btnDel);
        return row;
    }

    private void launchOffline(DownloadDbHelper.DownloadRecord r) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("offline", true);
        intent.putExtra("guid", r.itemGuid);
        intent.putExtra("title", r.title);
        intent.putExtra("tv_title", r.tvTitle != null ? r.tvTitle : "");
        intent.putExtra("episode_number", r.episodeNum);
        intent.putExtra("poster", r.poster);
        startActivity(intent);
    }

    private String stateText(DownloadDbHelper.DownloadRecord r) {
        switch (r.state) {
            case Download.STATE_QUEUED:
                return "排队中" + (om.isWifiOnly() && !isWifiConnected() ? "（等待 WiFi）" : "…");
            case Download.STATE_DOWNLOADING: {
                StringBuilder sb = new StringBuilder("下载中");
                long[] lp = liveProgress.get(r.itemGuid);
                long speed = lp == null ? 0 : lp[1];
                if (r.estimated > 0) {
                    // 0 字节也显示百分比与预计体积（否则用户感知不到任务在跑）
                    int pct = Math.min(99, (int) (r.bytes * 100 / r.estimated));
                    sb.append(" ").append(pct).append("%  ")
                            .append(OfflineManager.formatSize(r.bytes)).append("/")
                            .append(OfflineManager.formatSize(r.estimated));
                } else {
                    sb.append(" 已下载 ").append(OfflineManager.formatSize(r.bytes));
                }
                if (speed > 0) sb.append(" · ").append(OfflineManager.formatSize(speed)).append("/s");
                else sb.append(" · 等待数据…");
                if ("ok".equals(r.danmuState)) sb.append(" · 弹幕已缓存");
                return sb.toString();
            }
            case Download.STATE_STOPPED:
                return "已暂停 · " + OfflineManager.formatSize(r.bytes);
            case Download.STATE_COMPLETED:
                // bytes 是完成时落库的真实缓存量；estimated 对 HLS 不可靠（源文件大小≠HLS 流大小）
                return "已完成 · " + OfflineManager.formatSize(r.bytes)
                        + ("ok".equals(r.danmuState) ? " · 弹幕已缓存"
                        : "failed".equals(r.danmuState) ? " · 弹幕未缓存" : "");
            case Download.STATE_FAILED:
                return "下载失败 · " + OfflineManager.formatSize(r.bytes) + failureSuffix(r);
            default:
                return "处理中…";
        }
    }

    /** 失败行附带落库的失败原因（可读性截断 60 字） */
    private String failureSuffix(DownloadDbHelper.DownloadRecord r) {
        String err = r.lastError == null ? "" : r.lastError.replace("\n", " ").trim();
        if (err.isEmpty()) {
            return "failed".equals(r.danmuState) ? " · 弹幕未缓存" : "";
        }
        if (err.length() > 60) err = err.substring(0, 60) + "…";
        return " · " + err;
    }

    /** 粗略判断当前是否 WiFi（仅用于等待中的提示文案，不做下载控制依据） */
    private boolean isWifiConnected() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.getType() == android.net.ConnectivityManager.TYPE_WIFI;
        } catch (Exception e) {
            return true;
        }
    }

    private View makeSpacer(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, (int) (dp * getResources().getDisplayMetrics().density)));
        return v;
    }
}
