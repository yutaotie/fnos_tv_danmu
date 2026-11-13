package com.fntv.app;

import android.app.Activity;
import android.util.Log;
import android.widget.Toast;

import com.fntv.app.api.model.PlayListItem;
import com.fntv.app.api.model.StreamResponse;
import com.google.android.exoplayer2.offline.Download;
import com.google.gson.Gson;

import java.util.Collections;
import java.util.List;

/**
 * 缓存清晰度选择弹窗（4.3）：单集缓存 / 整季批量缓存（4.2）共用。
 *
 * 流程（后台线程）：
 * 1. play/info 解析 mediaGuid（单集模式结果直接传给入队，避免重复解析）
 * 2. getStream → strm/云盘检测（不支持缓存则提示并退出，Non-Goal）
 * 3. QualitySelectHelper.buildOptions 生成 原画+转码 档位；
 *    原画单集 HEAD 实测体积，转码按 bitrate×片长估算
 * 4. 主线程弹窗，选中后批量入队（跳过队列中/已缓存/已暂停的集）
 */
public class CacheDialogHelper {

    private static final String TAG = "CacheDialog";

    /** 单集缓存入口（剧集列表"缓存"按钮） */
    public static void showForEpisode(Activity act, PlayListItem ep) {
        OfflineManager om = OfflineManager.getInstance(act);
        toast(act, "正在获取可缓存清晰度…");
        new Thread(() -> {
            try {
                String mediaGuid = om.resolveMediaGuid(ep.guid);
                if (mediaGuid == null) { toast(act, "获取媒体信息失败"); return; }
                StreamResponse stream = om.fetchStream(mediaGuid);
                if (stream == null) { toast(act, "获取流信息失败"); return; }
                boolean cloud = stream.directLinkQualities != null && !stream.directLinkQualities.isEmpty();
                if (!cloud && OfflineManager.isStrmContent(stream)) {
                    toast(act, "strm/云盘内容无可用直链，无法缓存");
                    return;
                }
                List<QualitySelectHelper.QualityOption> opts = cloud
                        ? QualitySelectHelper.buildCloudOptions(stream)
                        : QualitySelectHelper.buildOptions(stream.qualities);
                if (opts.isEmpty()) { toast(act, "无可用清晰度"); return; }
                long origSize = 0;
                if (cloud) {
                    // 云盘原画体积 = 文件真实大小（stream 已返回，无需 HEAD）
                    if (stream.fileStream != null) origSize = stream.fileStream.size;
                } else if (hasOriginal(opts)) {
                    String url = getBaseUrl(act) + "/v/api/v1/media/range/" + mediaGuid;
                    origSize = om.probeContentLength(url);
                }
                final long finalOrig = origSize;
                final String preGuid = mediaGuid;
                act.runOnUiThread(() -> {
                    if (act.isFinishing()) return;
                    showOptionsDialog(act, opts, finalOrig,
                            ep.duration > 0 ? ep.duration : 0,
                            Collections.singletonList(ep), preGuid);
                });
            } catch (Exception e) {
                Log.e(TAG, "缓存弹窗失败", e);
                toast(act, "获取缓存信息失败: " + e.getMessage());
            }
        }).start();
    }

    /** 整季批量缓存入口（剧集列表页"缓存整季"按钮）。eps 为该季全部集。 */
    public static void showForSeason(Activity act, List<PlayListItem> eps) {
        if (eps == null || eps.isEmpty()) return;
        OfflineManager om = OfflineManager.getInstance(act);
        toast(act, "正在获取可缓存清晰度…");
        new Thread(() -> {
            try {
                // 用首个未缓存集探测可缓存性与档位（同一媒体库转码档一致）
                PlayListItem probe = null;
                for (PlayListItem e : eps) {
                    if (!om.hasActiveTask(e.guid) && !om.isCached(e.guid)) { probe = e; break; }
                }
                if (probe == null) { toast(act, "该季已全部缓存"); return; }
                String mediaGuid = om.resolveMediaGuid(probe.guid);
                if (mediaGuid == null) { toast(act, "获取媒体信息失败"); return; }
                StreamResponse stream = om.fetchStream(mediaGuid);
                if (stream == null) { toast(act, "获取流信息失败"); return; }
                boolean cloud = stream.directLinkQualities != null && !stream.directLinkQualities.isEmpty();
                if (!cloud && OfflineManager.isStrmContent(stream)) {
                    toast(act, "strm/云盘内容无可用直链，无法缓存");
                    return;
                }
                List<QualitySelectHelper.QualityOption> opts = cloud
                        ? QualitySelectHelper.buildCloudOptions(stream)
                        : QualitySelectHelper.buildOptions(stream.qualities);
                if (opts.isEmpty()) { toast(act, "无可用清晰度"); return; }
                // 整季总时长（秒）用于转码档体积估算；原画逐集 HEAD 太重，显示按实际大小
                long totalSec = 0;
                for (PlayListItem e : eps) if (e.duration > 0) totalSec += e.duration;
                final long total = totalSec;
                act.runOnUiThread(() -> {
                    if (act.isFinishing()) return;
                    showOptionsDialog(act, opts, 0, total, eps, null);
                });
            } catch (Exception e) {
                Log.e(TAG, "缓存弹窗失败", e);
                toast(act, "获取缓存信息失败: " + e.getMessage());
            }
        }).start();
    }

    private static boolean hasOriginal(List<QualitySelectHelper.QualityOption> opts) {
        for (QualitySelectHelper.QualityOption o : opts) if (o.isOriginal) return true;
        return false;
    }

    /**
     * 弹窗展示：档位 + 预计体积。
     *
     * @param origSize        原画实测字节数（0=未知，整季模式不探测）
     * @param durationSec     用于转码估算的片长（单集=单集片长，整季=总片长）
     * @param eps             待入队剧集
     * @param episodeMediaGuid 单集模式已解析的 mediaGuid（整季为 null，逐集解析）
     */
    private static void showOptionsDialog(Activity act,
                                          List<QualitySelectHelper.QualityOption> opts,
                                          long origSize, long durationSec,
                                          List<PlayListItem> eps, String episodeMediaGuid) {
        String[] items = new String[opts.size()];
        for (int i = 0; i < opts.size(); i++) {
            QualitySelectHelper.QualityOption o = opts.get(i);
            String size;
            if (o.isM3u8) {
                // HLS 转封装流体积与源文件/码率估算差距大（115 原画 HLS 实测 ≈1.45GB，源 mkv 5.93GB），
                // 不报"约 X GB"，完成后以实际缓存字节为准
                size = "按实际大小";
            } else if (o.isOriginal || "原画".equals(o.label)) {
                size = origSize > 0 ? "约 " + OfflineManager.formatSize(origSize)
                        : (durationSec > 0 ? "按实际大小（逐集检测）" : "按实际大小");
            } else {
                size = durationSec > 0 && o.bitrate > 0
                        ? "约 " + OfflineManager.formatSize(o.bitrate / 8L * durationSec)
                        : "大小未知";
            }
            items[i] = o.label + "  (" + size + ")";
        }
        new android.app.AlertDialog.Builder(act)
                .setTitle(eps.size() > 1 ? "缓存整季（" + eps.size() + " 集）" : "缓存清晰度")
                .setItems(items, (dialog, which) -> {
                    if (which < 0 || which >= opts.size()) return;
                    QualitySelectHelper.QualityOption o = opts.get(which);
                    OfflineManager.QualityParams qp = new OfflineManager.QualityParams();
                    qp.label = o.label;
                    qp.resolution = o.resolution;
                    qp.bitrate = o.bitrate;
                    qp.original = o.isOriginal;
                    qp.cloud = o.directUrl != null && !o.directUrl.isEmpty();
                    qp.directUrl = o.directUrl;
                    enqueueBatch(act, eps, qp, episodeMediaGuid);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 批量入队：跳过队列中/已缓存/已暂停的集（4.2），逐条汇总结果 */
    private static void enqueueBatch(Activity act, List<PlayListItem> eps,
                                     OfflineManager.QualityParams qp, String preMediaGuid) {
        OfflineManager om = OfflineManager.getInstance(act);
        toast(act, "正在加入缓存队列…");
        new Thread(() -> {
            int ok = 0, skip = 0, fail = 0;
            String firstErr = null;
            Gson gson = new Gson();
            for (PlayListItem ep : eps) {
                try {
                    if (om.hasActiveTask(ep.guid) || om.isCached(ep.guid)) { skip++; continue; }
                    DownloadDbHelper.DownloadRecord rec = new DownloadDbHelper.DownloadRecord();
                    rec.itemGuid = ep.guid;
                    rec.tvTitle = ep.tvTitle != null && !ep.tvTitle.isEmpty() ? ep.tvTitle : ep.title;
                    rec.title = ep.title;
                    rec.seasonNum = ep.seasonNumber > 0 ? ep.seasonNumber : 1;
                    rec.episodeNum = ep.episodeNumber;
                    rec.poster = ep.poster;
                    rec.qualityLabel = qp.label;
                    rec.qualityJson = gson.toJson(qp);
                    rec.state = Download.STATE_QUEUED;
                    rec.createdAt = System.currentTimeMillis();
                    String pre = eps.size() == 1 ? preMediaGuid : null;
                    String err = om.resolveAndEnqueue(rec, qp, ep.duration > 0 ? ep.duration : 0, pre);
                    if (err == null) {
                        ok++;
                    } else {
                        fail++;
                        if (firstErr == null) firstErr = ep.title + ": " + err;
                        Log.w(TAG, "入队失败: " + ep.guid + " " + err);
                    }
                } catch (Exception e) {
                    fail++;
                    if (firstErr == null) firstErr = ep.title + ": " + e.getMessage();
                    Log.e(TAG, "入队异常: " + ep.guid, e);
                }
            }
            StringBuilder msg = new StringBuilder();
            if (ok > 0) msg.append("已加入队列 ").append(ok).append(" 集");
            if (skip > 0) msg.append(ok > 0 ? "，跳过已缓存/队列中 " : "该季已全部在队列中或已缓存 ")
                    .append(skip).append(" 集");
            if (fail > 0) msg.append(ok + skip > 0 ? "，失败 " : "加入队列失败 ")
                    .append(fail).append(" 集");
            if (fail > 0 && firstErr != null) msg.append("（").append(firstErr).append("）");
            if (msg.length() == 0) msg.append("没有可缓存的集");
            final String finalMsg = msg.toString();
            act.runOnUiThread(() -> toast(act, finalMsg));
        }).start();
    }

    private static String getBaseUrl(Activity act) {
        return act.getSharedPreferences("fntv_prefs", 0)
                .getString("host", "").replaceAll("/+$", "");
    }

    private static void toast(Activity act, String msg) {
        if (act == null || act.isFinishing()) return;
        act.runOnUiThread(() -> Toast.makeText(act, msg, Toast.LENGTH_SHORT).show());
    }
}
