package com.fntv.app;

import androidx.multidex.MultiDexApplication;

/** 应用入口：提供全局 Context，供 OfflineManager 等单例使用 */
public class FntvApp extends MultiDexApplication {

    private static FntvApp instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    public static FntvApp get() {
        return instance;
    }
}
