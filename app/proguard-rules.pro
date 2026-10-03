# 模块入口由 java_init.list 按全限定名反射加载，必须保留。
-keep class io.github.jjmj.douyinunlimit.xposed.HookEntry { *; }
-keep class io.github.jjmj.douyinunlimit.App { *; }

# libxposed 运行时用到的接口
-keep class io.github.libxposed.** { *; }
