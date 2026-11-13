package com.fntv.app;

import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线弹幕取数器（无 UI，供下载流程在后台线程调用）。
 * 匹配逻辑与 DanmuManager.loadDanmu / startAutoMatch 保持一致：
 * 1. 标题提取集数（[Ee](\d+)）
 * 2. danmu_match_cache 命中 → /api/v2/bangumi/{aid} 找对应集
 * 3. 否则 POST /api/v2/match {fileName} → 校验集数
 * 4. /api/v2/comment/{epId}?format=json 拉取弹幕原始 JSON
 * 成功后回写 danmu_match_cache，供播放时在线匹配复用。
 */
public class DanmuOfflineFetcher {

    private static final String TAG = "OfflineDanmu";

    public static class Result {
        public boolean success;
        public String rawJson;    // 弹幕原始 JSON（comments 数组）
        public String label;      // 匹配名（番剧名 第N集）
        public String error;

        static Result ok(String raw, String label) {
            Result r = new Result();
            r.success = true;
            r.rawJson = raw;
            r.label = label;
            return r;
        }

        static Result fail(String err) {
            Result r = new Result();
            r.success = false;
            r.error = err;
            return r;
        }
    }

    /**
     * 同步获取弹幕（必须在后台线程调用）。
     *
     * @param prefs    应用 SharedPreferences（danmu_url / danmu_match_cache）
     * @param matchTitle 匹配名，与播放页一致：剧名 + " S01E05"
     * @param tvTitle    剧名（match_cache 的 key）
     */
    public static Result fetch(SharedPreferences prefs, String matchTitle, String tvTitle) {
        try {
            String danmuUrl = resolveDanmuUrl(prefs);
            if (danmuUrl.isEmpty() || matchTitle == null || matchTitle.isEmpty()) {
                return Result.fail("弹幕服务器未配置");
            }
            int targetEp = 0;
            Matcher m = Pattern.compile("[Ee](\\d+)").matcher(matchTitle);
            if (m.find()) targetEp = Integer.parseInt(m.group(1));

            // 1. match_cache 命中路径
            if (tvTitle != null && !tvTitle.isEmpty() && targetEp > 0) {
                try {
                    JSONObject cache = new JSONObject(prefs.getString("danmu_match_cache", "{}"));
                    if (cache.has(tvTitle)) {
                        String[] parts = cache.getString(tvTitle).split("\\|", 2);
                        if (parts.length == 2) {
                            int aid = Integer.parseInt(parts[0]);
                            String name = parts[1];
                            int epId = findEpisodeInBangumi(danmuUrl, aid, targetEp);
                            if (epId > 0) {
                                String raw = fetchComments(danmuUrl, epId);
                                if (raw != null) {
                                    return Result.ok(raw, name + " 第" + targetEp + "集");
                                }
                            }
                            Log.d(TAG, "缓存路径未取到第" + targetEp + "集，走自动匹配");
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "match_cache 路径失败: " + e.getMessage());
                }
            }

            // 2. 自动匹配路径
            int[] matchRet = autoMatch(danmuUrl, matchTitle, targetEp); // [epId, animeId]
            int epId = matchRet[0];
            if (epId <= 0) return Result.fail("未匹配到弹幕剧集");
            String raw = fetchComments(danmuUrl, epId);
            if (raw == null) return Result.fail("弹幕数据获取失败");

            // 3. 回写 match cache（与 DanmuManager 语义一致：aid|名称）
            if (tvTitle != null && !tvTitle.isEmpty() && matchRet[1] > 0) {
                try {
                    JSONObject cache = new JSONObject(prefs.getString("danmu_match_cache", "{}"));
                    cache.put(tvTitle, matchRet[1] + "|" + tvTitle);
                    prefs.edit().putString("danmu_match_cache", cache.toString()).apply();
                } catch (Exception ignored) {}
            }
            return Result.ok(raw, tvTitle != null ? tvTitle + " 第" + targetEp + "集" : matchTitle);
        } catch (Exception e) {
            Log.e(TAG, "弹幕离线获取异常", e);
            return Result.fail(e.getMessage());
        }
    }

    /** 弹幕服务器地址（与 DanmuManager 构造逻辑一致） */
    public static String resolveDanmuUrl(SharedPreferences prefs) {
        String savedUrl = prefs.getString("danmu_url", "");
        if (savedUrl.isEmpty()) {
            String host = prefs.getString("host", "");
            host = host.replaceAll("^https?://", "").replaceAll("/.*$", "").replaceAll(":\\d+$", "");
            savedUrl = "http://" + host + ":9321";
        }
        return savedUrl;
    }

    /** 在番剧分集列表中找目标集，返回 episodeId */
    private static int findEpisodeInBangumi(String danmuUrl, int aid, int targetEp) throws Exception {
        URL u = new URL(danmuUrl + "/api/v2/bangumi/" + aid);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.connect();
        String body = readAll(c);
        if (body == null) return 0;
        JSONObject j = new JSONObject(body);
        JSONArray eps = null;
        if (j.has("bangumi") && j.getJSONObject("bangumi").has("episodes"))
            eps = j.getJSONObject("bangumi").getJSONArray("episodes");
        else if (j.has("episodes")) eps = j.getJSONArray("episodes");
        else if (j.has("data") && j.getJSONObject("data").has("episodes"))
            eps = j.getJSONObject("data").getJSONArray("episodes");
        if (eps != null) {
            for (int i = 0; i < eps.length(); i++) {
                JSONObject ep = eps.getJSONObject(i);
                if (ep.optInt("episodeNumber", 0) == targetEp) {
                    int epId = ep.optInt("episodeId", 0);
                    if (epId > 0) return epId;
                }
            }
        }
        return 0;
    }

    /** POST /api/v2/match，返回 [episodeId, animeId]，匹配集数校验失败时 episodeId=0 */
    private static int[] autoMatch(String danmuUrl, String title, int targetEp) {
        int episodeId = 0, animeId = 0;
        // match 失败重试一次（与 startAutoMatch 一致）
        for (int attempt = 0; attempt < 2 && episodeId <= 0; attempt++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(danmuUrl + "/api/v2/match");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);
                // 与 DanmuManager 一致：fileName 原文写入（服务器按文件名模糊匹配）
                String body = "{\"fileName\":\"" + title + "\"}";
                conn.getOutputStream().write(body.getBytes("UTF-8"));
                conn.connect();
                if (conn.getResponseCode() == 200) {
                    JSONObject j = new JSONObject(readAll(conn));
                    JSONArray matches = j.optJSONArray("matches");
                    if (matches != null && matches.length() > 0) {
                        JSONObject first = matches.getJSONObject(0);
                        episodeId = first.optInt("episodeId", 0);
                        animeId = first.optInt("animeId", 0);
                        String matchEp = first.optString("episodeTitle", "");
                        int matchedEpNum = 0;
                        Matcher mEp = Pattern.compile("[第](\\d+)[集]").matcher(matchEp);
                        if (mEp.find()) matchedEpNum = Integer.parseInt(mEp.group(1));
                        if (targetEp > 0 && matchedEpNum > 0 && matchedEpNum != targetEp) {
                            Log.d(TAG, "match 集数不符(匹配" + matchedEpNum + "/需" + targetEp + ")，丢弃");
                            episodeId = 0;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "match 请求失败: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return new int[]{episodeId, animeId};
    }

    /** GET /api/v2/comment/{epId}?format=json */
    private static String fetchComments(String danmuUrl, int epId) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(danmuUrl + "/api/v2/comment/" + epId + "?format=json");
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            c.connect();
            String raw = readAll(c);
            if (raw != null && !raw.trim().isEmpty()) {
                // 校验可解析（comments/data/裸数组均可，与 DanmuManager.loadDanmuByEp 一致）
                String t = raw.trim();
                if (t.startsWith("[") || t.startsWith("{")) return raw;
            }
            return null;
        } catch (Exception e) {
            Log.w(TAG, "comment 获取失败: " + e.getMessage());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(HttpURLConnection c) throws Exception {
        if (c.getResponseCode() != 200) return null;
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = r.readLine()) != null) sb.append(l);
        r.close();
        return sb.toString();
    }
}
