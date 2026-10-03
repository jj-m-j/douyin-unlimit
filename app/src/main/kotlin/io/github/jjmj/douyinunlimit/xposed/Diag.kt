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
 * ## 两级日志
 *
 *  - [log]   基础日志：安装结果、错误、关键命中。始终写入（有总量上限）。
 *  - [debug] 详细日志：每次点击的控件类名/id/祖先链、每个请求 URL、字段定位过程等。
 *            只有用户在模块里打开「详细调试日志」才会写，避免平时白耗电。
 *
 * ## 只追加，永不截断
 *
 * 早期版本用 `writeText` 写文件头 = 清空重写，而抖音多进程 + Hive 插件框架会触发
 * 多次 `Application.onCreate`，后一次会把前面的日志全抹掉（表现为「文件里只剩两行」）。
 * 现在纯追加，每次会话写带进程名的分隔行，谁也盖不掉谁。
 *
 * ## 缓冲
 *
 * guard 在 `onPackageReady` 安装，那时 `Application` 还没创建、拿不到 Context，
 * 解析不出文件路径。早期日志先存内存，等 Application 就绪再落盘，启动顺序完整可见。
 *
 * 文件位置（MT 用 root 可直接打开）：
 *   /storage/emulated/0/Android/data/com.ss.android.ugc.aweme/files/unlimit-diag.log
 */
internal object Diag {

    const val TAG = "DouyinUnlimit"

    private const val LIMIT = 1500
    private const val PENDING_MAX = 300
    private const val FILE_NAME = "unlimit-diag.log"

    private val total = AtomicInteger(0)
    private val pending = ArrayDeque<String>()

    @Volatile
    private var file: File? = null

    @Volatile
    private var processTag: String? = null

    /** 由 HookEntry 注入：问一下「详细调试日志」开着没。 */
    @Volatile
    private var verboseProvider: (() -> Boolean)? = null

    fun setVerboseProvider(provider: () -> Boolean) {
        verboseProvider = provider
    }

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

    /** 基础日志：始终记录。 */
    fun log(key: String, message: String) = write(key, message)

    fun log(message: String) = write("diag", message)

    /** 详细日志：只有开启「详细调试日志」才写。 */
    fun debug(key: String, message: String) {
        if (verboseProvider?.invoke() == true) write("$key*", message)
    }

    private fun write(key: String, message: String) {
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
        val short = name.substringAfterLast(':').takeIf { name.contains(':') } ?: name
        val result = "[${short.ifEmpty { "main" }}]"
        processTag = result
        return result
    }

    private fun now(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
}
