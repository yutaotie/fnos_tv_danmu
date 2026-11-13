package com.fntv.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.google.android.exoplayer2.offline.Download;
import com.google.android.exoplayer2.offline.DownloadManager;
import com.google.android.exoplayer2.offline.DownloadService;
import com.google.android.exoplayer2.scheduler.PlatformScheduler;
import com.google.android.exoplayer2.scheduler.Scheduler;

import java.util.List;

/**
 * 离线下载前台服务（design D1 / task 2.1）。
 * 通知栏进度由 DownloadService 内置的 ForegroundNotificationUpdater 周期刷新，
 * 点击通知回到 MainActivity。
 */
public class OfflineDownloadService extends DownloadService {

    private static final int FOREGROUND_NOTIFICATION_ID = 4101;
    private static final long UPDATE_INTERVAL_MS = 1000;
    private static final String CHANNEL_ID = "offline_download";

    public OfflineDownloadService() {
        // 4 参构造：DownloadService 内部自动创建通知渠道（API 26+）
        super(FOREGROUND_NOTIFICATION_ID, UPDATE_INTERVAL_MS, CHANNEL_ID,
                R.string.offline_channel_name);
    }

    @Override
    protected DownloadManager getDownloadManager() {
        return OfflineManager.getInstance(this).getDownloadManager();
    }

    @Override
    protected Scheduler getScheduler() {
        // JobScheduler 仅 API 21+；4.4~5.0 上不调度重启（任务在下次打开应用时恢复）
        if (Build.VERSION.SDK_INT < 21) return null;
        return new PlatformScheduler(this, 4102);
    }

    @Override
    protected Notification getForegroundNotification(List<Download> downloads, int notMetRequirements) {
        int downloading = 0, queued = 0, completed = 0;
        long bytes = 0, total = 0;
        String firstTitle = null;
        for (Download d : downloads) {
            switch (d.state) {
                case Download.STATE_DOWNLOADING:
                    downloading++;
                    if (firstTitle == null) {
                        DownloadDbHelper.DownloadRecord r =
                                OfflineManager.getInstance(this).getDb().get(d.request.id);
                        if (r != null) {
                            firstTitle = (r.episodeNum > 0 ? "第" + r.episodeNum + "集 " : "")
                                    + (r.title != null ? r.title : "");
                        }
                    }
                    break;
                case Download.STATE_QUEUED:
                case Download.STATE_RESTARTING:
                    queued++;
                    break;
                case Download.STATE_COMPLETED:
                    completed++;
                    break;
                default:
                    break;
            }
            bytes += d.getBytesDownloaded();
            if (d.contentLength > 0) total += d.contentLength;
        }

        String host = getSharedPreferences("fntv_prefs", MODE_PRIVATE).getString("host", "");
        Intent intent = new Intent(this, MainActivity.class);
        intent.setPackage(getPackageName());
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23
                        ? PendingIntent.FLAG_IMMUTABLE : 0));

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(true);

        String waiting = notMetRequirements != 0 ? "（等待网络条件）" : "";
        if (downloading > 0) {
            b.setContentTitle("正在缓存 " + downloading + " 集" + waiting);
            String sub = (firstTitle != null ? firstTitle : "准备中…")
                    + (queued > 0 ? "，另有 " + queued + " 集排队" : "");
            b.setContentText(sub);
            if (total > 0) {
                b.setProgress(100, (int) Math.min(100, bytes * 100 / total), false);
            } else {
                b.setProgress(0, 0, true);
            }
        } else if (queued > 0) {
            b.setContentTitle("缓存等待中" + waiting)
                    .setContentText(queued + " 集排队，" + completed + " 集已完成");
            b.setProgress(0, 0, false);
        } else {
            b.setContentTitle("离线缓存")
                    .setContentText("下载服务运行中");
        }
        return b.build();
    }
}
