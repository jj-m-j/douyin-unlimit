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
 * ## 默认关闭，一行都不写
 *
 * 用户没有主动打开「记录详细日志」时，这里**不落盘、不写 logcat**：不做任何 I/O，
 * 不占空间也不耗电。日志是排障工具，不该是常驻开销。
 *
 * 但「装上了没」这件事必须留证据，否则打开开关之前发生的安装过程就永久丢失了
 * （用户要看到的正是「哪个 hook 没挂上」）。所以在关闭状态下日志只进**内存环形缓冲**
 * （有上限），等开关打开的那一刻一次性补写进文件。这样：
 *
 *   - 关闭时：零 I/O
 *   - 打开开关后不重启：立刻拿到本次会话从头开始的完整日志
 *   - 打开开关后重启：安装阶段就直写文件
 *
 * ## 只追加，永不截断
 *
 * 早期版本用 `writeText` 写文件头 = 清空重写，而抖音多进程 + Hive 插件框架会触发
 * 多次 `Application.onCreate`，后一次会把前面的日志全抹掉（表现为「文件里只剩两行」）。
 * 现在纯追加，每次会话写带进程名的分隔行，谁也盖不掉谁。
 *
 * ## 为什么必须有自建通道
 *
 * `XposedModule.log()` 既不进 logcat 也不进文件（走框架自己的日志通道）。
 * 所有诊断都必须走这里，否则会出现「hook 明明挂上了但日志里什么都没有」的假象。
 *
 * 文件位置（MT 用 root 可直接打开）：
 *   /storage/emulated/0/Android/data/com.ss.android.ugc.aweme/files/unlimit-diag.log
 */
internal object Diag {

    const val TAG = "DouyinUnlimit"

    /** 单次会话最多写多少行，防止刷屏把文件撑爆。 */
    private const val LIMIT = 2000

    /** 关闭状态下内存里最多留多少行。 */
    private const val BUFFER_MAX = 400

    private const val FILE_NAME = "unlimit-diag.log"

    private val total = AtomicInteger(0)
    private val pending = ArrayDeque<String>()

    @Volatile
    private var hasPending = false

    @Volatile
    private var file: File? = null

    @Volatile
    private var processTag: String? = null

    /** 由 HookEntry 注入：问一下「记录详细日志」开着没。 */
    @Volatile
    private var verboseProvider: (() -> Boolean)? = null

    fun setVerboseProvider(provider: () -> Boolean) {
        verboseProvider = provider
    }

    /** 日志总开关。打开时顺带把攒着的日志补写进文件。 */
    private fun verbose(): Boolean {
        val on = verboseProvider?.invoke() == true
        if (on) flush()
        return on
    }

    /** 会话分隔行：进程名 + 时间，多进程下谁也盖不掉谁。 */
    fun startSession(label: String) {
        val line = "===== $label | ${processTag()} @ ${now()} ====="
        if (!verbose()) {
            remember(line)
            return
        }
        runCatching {
            val target = resolve() ?: return remember(line)
            target.parentFile?.mkdirs()
            target.appendText("\n$line\n")
        }
    }

    /** 基础日志：安装结果、错误、关键命中。 */
    fun log(key: String, message: String) = emit(key, message)

    fun log(message: String) = emit("diag", message)

    /** 详细日志：每次点击的控件、点赞链路每一步等高频内容。 */
    fun debug(key: String, message: String) = emit("$key*", message)

    private fun emit(key: String, message: String) {
        val line = "[$key] $message"

        if (!verbose()) {
            remember(line)
            return
        }

        if (total.incrementAndGet() > LIMIT) return
        Log.i(TAG, "${processTag()} $line")

        val target = resolve()
        if (target == null) {
            remember(line)
            return
        }
        runCatching { target.appendText("${now()} ${processTag()} $line\n") }
    }

    // ---------------------------------------------------------------- 内存缓冲

    private fun remember(line: String) {
        synchronized(pending) {
            while (pending.size >= BUFFER_MAX) pending.removeFirst()
            pending.addLast(line)
            hasPending = true
        }
    }

    /** 把缓冲里的日志补写进文件。稳态下 `hasPending` 为 false，几乎零开销。 */
    private fun flush() {
        if (!hasPending) return
        val target = resolve() ?: return

        runCatching {
            target.parentFile?.mkdirs()
            synchronized(pending) {
                // 先全写完再清空：写失败时宁可下次重复，也不要丢日志
                for (line in pending) target.appendText("${now()} ${processTag()} $line\n")
                pending.clear()
                hasPending = false
            }
        }
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
