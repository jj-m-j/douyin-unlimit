# 入口类由 META-INF/xposed/java_init.list 按全限定名反射加载，必须保留原名。
-keep class io.github.jjmj.douyinunlimit.xposed.HookEntry { *; }
# Application 在清单里声明，正常会被 keep；显式写一行防止意外。
-keep class io.github.jjmj.douyinunlimit.App { *; }

# libxposed 运行时用到的接口（api 是 compileOnly，service 会打进包）
-keep class io.github.libxposed.** { *; }
-keep interface io.github.libxposed.** { *; }

# 反射调用的 JDK 方法（View#setVisibility 等）不需要规则，这里只是压掉无关告警
-dontwarn org.jetbrains.annotations.**
