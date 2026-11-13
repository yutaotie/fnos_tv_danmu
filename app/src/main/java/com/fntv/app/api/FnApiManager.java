package com.fntv.app.api;

import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.HostnameVerifier;

public class FnApiManager {
    private static FnApiManager instance;
    private FnApiService apiService;
    private final AuthInterceptor authInterceptor;
    private OkHttpClient okHttpClient; // 供 API 和图片使用
    private OkHttpClient streamClient; // 专供视频流使用（独立连接池）
    private OkHttpClient downloadClient; // 离线下载专用（有限超时，避免卡死在速度0）
    private static final HostnameVerifier TRUST_ALL_HOSTS = (hostname, session) -> true;

    private FnApiManager() {
        authInterceptor = new AuthInterceptor();
    }

    public static synchronized FnApiManager getInstance() {
        if (instance == null) instance = new FnApiManager();
        return instance;
    }

    private OkHttpClient buildClient(boolean streaming, boolean finiteTimeouts) {
        try {
            X509TrustManager trustAll = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAll}, new java.security.SecureRandom());

            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .addInterceptor(authInterceptor)
                    .sslSocketFactory(sslContext.getSocketFactory(), trustAll)
                    .hostnameVerifier(TRUST_ALL_HOSTS);
            if (streaming) {
                // 视频流：播放不设超时；下载用有限超时，卡住时能失败重试而非永远停在速度0
                long t = finiteTimeouts ? 30000 : 0;
                long rt = finiteTimeouts ? 60000 : 0;
                builder.connectTimeout(t, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .readTimeout(rt, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .writeTimeout(t, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .retryOnConnectionFailure(true)
                        .connectionPool(new okhttp3.ConnectionPool(4, 30, java.util.concurrent.TimeUnit.SECONDS));
            } else {
                // API/图片：标准超时
                builder.connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .connectionPool(new okhttp3.ConnectionPool(2, 10, java.util.concurrent.TimeUnit.SECONDS));
            }
            return builder.build();
        } catch (Exception e) {
            OkHttpClient.Builder builder = new OkHttpClient.Builder().addInterceptor(authInterceptor)
                    .hostnameVerifier(TRUST_ALL_HOSTS);
            if (streaming) {
                long t = finiteTimeouts ? 30000 : 0;
                long rt = finiteTimeouts ? 60000 : 0;
                builder.connectTimeout(t, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .readTimeout(rt, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            try {
                X509TrustManager fallbackTrust = new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                };
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, new TrustManager[]{fallbackTrust}, new java.security.SecureRandom());
                builder.sslSocketFactory(sc.getSocketFactory(), fallbackTrust);
            } catch (Exception ignored) {}
            return builder.build();
        }
    }

    public void updateBaseUrl(String baseUrl) {
        String effectiveBaseUrl = baseUrl;
        if (effectiveBaseUrl.endsWith("/")) {
            effectiveBaseUrl = effectiveBaseUrl.substring(0, effectiveBaseUrl.length() - 1);
        }
        int vIndex = effectiveBaseUrl.indexOf("/v");
        if (vIndex != -1) {
            effectiveBaseUrl = effectiveBaseUrl.substring(0, vIndex);
        }

        okHttpClient = buildClient(false, false);
        streamClient = buildClient(true, false);
        downloadClient = buildClient(true, true);

        // 记录 NAS host，供 AuthInterceptor 区分 NAS 请求与云盘直链请求
        try {
            String host = android.net.Uri.parse(effectiveBaseUrl).getHost();
            if (host != null && !host.isEmpty()) {
                authInterceptor.setNasHost(host);
            }
        } catch (Exception ignored) {}

        retrofit2.Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(effectiveBaseUrl + "/v/")
                .client(okHttpClient)
                .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
                .build();
        apiService = retrofit.create(FnApiService.class);
    }

    public void setToken(String token) { authInterceptor.setToken(token); }
    public String getToken() { return authInterceptor.getToken(); }
    public void setReferer(String referer) { authInterceptor.setReferer(referer); }
    public FnApiService getApi() { return apiService; }

    /** 获取 OkHttpClient（API/图片） */
    public OkHttpClient getClient() { return okHttpClient; }

    /** 获取视频流专用 OkHttpClient（无超时，独立连接池） */
    public OkHttpClient getStreamClient() { return streamClient; }

    /** 获取离线下载专用 OkHttpClient（有限超时，独立连接池） */
    public OkHttpClient getDownloadClient() { return downloadClient; }
}
