package io.github.jjmj.douyinunlimit.xposed

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 诊断日志。同时写 logcat 和文件。
 *
 * ## 为什么所有诊断都必须走这里
 *
 * `XposedModule.log()` 走的是框架自己的日志通道，**不会出现在 logcat，也不会进文件**。
 * 之前好几个 guard 的安装结果用的就是 `module.log`，导致文件里只剩一行
 * 「模块已注入」，完全看不出 hook 到底挂上没有。所以诊断统一走本类。
 *
 * ## 缓冲
 *
 * guard 是在 `onPackageReady` 里安装的，而那时 `Application` 还没创建，
 * 拿不到 Context 也就解析不出文件路径。所以文件不可用时的日志先存内存，
 * 等 `Application.onCreate` 里 [startSession] 时再一次性落盘——
 * 这样启动阶段的完整顺序都能看到。
 *
 * 文件位置（MT 用 root 可直接打开）：
 *   /storage/emulated/0/Android/data/com.ss.android.ugc.aweme/files/unlimit-diag.log
 */
internal object Diag {

    const val TAG = "DouyinUnlimit"

    private const val LIMIT = 600
    private const val PENDING_MAX = 200
    private const val FILE_NAME = "unlimit-diag.log"

    private val total = AtomicInteger(0)
    private val pending = ArrayDeque<String>()

    @Volatile
    private var file: File? = null

    /** 进程就绪时调用。此时 Application 已创建，能正常解析路径。 */
    fun startSession(label: String) {
        val target = resolve() ?: return
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText("=== $label @ ${now()} ===\n\n")
            synchronized(pending) {
                while (pending.isNotEmpty()) {
                    target.appendText("${now()} ${pending.removeFirst()}\n")
                }
            }
        }
        Log.i(TAG, "diag file: ${target.absolutePath}")
    }

    fun log(key: String, message: String) {
        if (total.incrementAndGet() > LIMIT) return
        val line = "[$key] $message"
        Log.i(TAG, line)

        val target = resolve()
        if (target == null) {
            // Application 还没创建，先存内存，等 startSession 一起落盘
            synchronized(pending) {
                if (pending.size < PENDING_MAX) pending.addLast(line)
            }
            return
        }
        runCatching { target.appendText("${now()} $line\n") }
    }

    fun log(message: String) = log("diag", message)

    /** 只有成功才缓存；失败留到下次重试（早期解析失败不能缓存，否则日志永远丢失）。 */
    private fun resolve(): File? {
        file?.let { return it }
        val resolved = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val app = activityThread.getMethod("currentApplication").invoke(null) as? Context
                ?: return null
            val dir = app.getExternalFilesDir(null) ?: app.filesDir ?: return null
            File(dir, FILE_NAME)
        }.getOrNull()
        if (resolved != null) file = resolved
        return resolved
    }

    private fun now(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
}
