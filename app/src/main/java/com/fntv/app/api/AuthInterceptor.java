package com.fntv.app.api;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okio.Buffer;
import java.io.IOException;
import java.nio.charset.Charset;

/**
 * 核心拦截器：注入签名
 */
public class AuthInterceptor implements Interceptor {
    private String token;
    private String referer;
    /** NAS host（登录时由 FnApiManager 注入）；非该 host 的请求不注入任何 NAS 鉴权头 */
    private String nasHost;

    public void setToken(String token) {
        this.token = token;
    }

    public String getToken() {
        return token;
    }

    public void setReferer(String referer) {
        this.referer = referer;
    }

    public void setNasHost(String host) {
        this.nasHost = host == null ? null : host.toLowerCase();
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();

        // 云盘直链（115/优酷等）与 NAS 不同源：不注入 Authx/Authorization/
        // Cookie mode=relay 等 NAS 鉴权头。此前无差别注入会把 NAS 凭据泄露给
        // 第三方 CDN，并覆盖数据源为云盘设置的 Cookie，还强制 JSON Content-Type，
        // 是云盘内容下载卡 0 字节的隐患。
        if (nasHost != null && !nasHost.equals(original.url().host().toLowerCase())) {
            android.util.Log.d("AuthInterceptor", "非 NAS 请求，跳过鉴权头: " + original.url().host());
            return chain.proceed(original);
        }

        String urlPath = original.url().encodedPath();
        String jsonBody = null;
        if (original.body() != null) {
            Buffer buffer = new Buffer();
            original.body().writeTo(buffer);
            jsonBody = buffer.readString(Charset.forName("UTF-8"));
        } else {
            String query = original.url().query();
            if (query != null && !query.isEmpty()) {
                jsonBody = query;
            }
        }

        String authx = FnAuthUtils.genAuthx(urlPath, jsonBody);

        Request.Builder requestBuilder = original.newBuilder()
                .header("Content-Type", "application/json")
                .header("Authx", authx)
                .header("Cookie", "mode=relay")
                .header("x-trim-client", "web")
                .header("x-trim-client-version", "608");

        if (token != null) {
            requestBuilder.header("Authorization", token);
        }

        if (referer != null && !referer.isEmpty() && urlPath.contains("play/record")) {
            requestBuilder.header("Referer", referer);
        }

        return chain.proceed(requestBuilder.build());
    }
}
