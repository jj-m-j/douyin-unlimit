package io.github.jjmj.douyinunlimit

import android.app.Application
import io.github.jjmj.douyinunlimit.data.SettingsBridge

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 向 Xposed 框架注册，拿到 XposedService 后即可读写远程配置
        SettingsBridge.register()
    }
}
