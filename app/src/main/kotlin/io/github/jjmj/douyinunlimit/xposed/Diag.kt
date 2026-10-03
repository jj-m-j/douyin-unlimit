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
 * 诊断日志。
 *
 * ## 只追加，永不截断
 *
 * 上一版 `startSession` 用 `writeText` 写文件头，等于**把文件清空重写**。
 * 抖音有多个进程，加上 Hive 插件框架可能触发不止一次 `Application.onCreate`，
 * 后一次就会把前面所有进程的日志抹掉——表现就是「文件里只剩最后两行」。
 * 现在改成纯追加，并且每次会话写一条带**进程名**的分隔行，谁也盖不掉谁。
 *
 * ## 为什么要缓冲
 *
 * guard 在 `onPackageReady` 里安装，那时 `Application` 还没创建、拿不到 Context，
 * 解析不出文件路径。所以文件不可用时的日志先存内存，等 Application 就绪再落盘，
 * 这样启动阶段的完整顺序都能保留。
 *
 * ## 为什么不用 XposedModule.log
 *
 * 那个走框架自己的通道，既不进 logcat 也不进文件。所有诊断必须走本类。
 *
 * 文件位置（MT 用 root 可直接打开）：
 *   /storage/emulated/0/Android/data/com.ss.android.ugc.aweme/files/unlimit-diag.log
 */
internal object Diag {

    const val TAG = "DouyinUnlimit"

    private const val LIMIT = 800
    private const val PENDING_MAX = 300
    private const val FILE_NAME = "unlimit-diag.log"

    private val total = AtomicInteger(0)
    private val pending = ArrayDeque<String>()

    @Volatile
    private var file: File? = null

    @Volatile
    private var processTag: String? = null

    /** 进程就绪时调用：追加一条分隔行，并把之前缓冲的日志落盘。 */
    fun startSession(label: String) {
        val target = resolve() ?: return
        val tag = processTag()
        runCatching {
            target.parentFile?.mkdirs()
            target.appendText("\n===== $label | $tag @ ${now()} =====\n")
            synchronized(pending) {
                while (pending.isNotEmpty()) {
                    target.appendText("${now()} $tag ${pending.removeFirst()}\n")
                }
            }
        }
        Log.i(TAG, "diag file: ${target.absolutePath} ($tag)")
    }

    fun log(key: String, message: String) {
        if (total.incrementAndGet() > LIMIT) return
        val line = "[$key] $message"
        Log.i(TAG, "$processTag() $line")

        val target = resolve()
        if (target == null) {
            synchronized(pending) {
                if (pending.size < PENDING_MAX) pending.addLast(line)
            }
            return
        }
        runCatching { target.appendText("${now()} ${processTag()} $line\n") }
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

    /** 进程名。多进程下必须区分，否则日志会互相污染。 */
    private fun processTag(): String {
        processTag?.let { return it }
        val name = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentProcessName")
                .invoke(null) as? String
        }.getOrNull() ?: "?"
        // com.ss.android.ugc.aweme:push -> :push
        val short = name.substringAfterLast(':').takeIf { name.contains(':') } ?: name
        val result = "[${short.ifEmpty { "main" }}]"
        processTag = result
        return result
    }

    private fun now(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
}
