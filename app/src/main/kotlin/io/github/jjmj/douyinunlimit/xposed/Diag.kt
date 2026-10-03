package io.github.jjmj.douyinunlimit.xposed

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 诊断日志。同时写 logcat 和文件。
 *
 * 写文件的原因：logcat 环形缓冲太小，抖音启动后很快滚掉；`-s TAG` 过滤也容易踩坑。
 *
 * 文件位置（抖音的外部私有目录，MT 用 root 能直接打开）：
 *   /storage/emulated/0/Android/data/com.ss.android.ugc.aweme/files/unlimit-diag.log
 *
 * 注意：解析文件路径**失败时不能缓存失败结果**——注入早期 Application 还没创建，
 * `ActivityThread.currentApplication()` 会返回 null；如果那时把「已解析」置位，
 * 后面就永远拿不到文件了（上一版就是这么把日志弄丢的）。
 */
internal object Diag {

    const val TAG = "DouyinUnlimit"

    private const val LIMIT = 400
    private const val FILE_NAME = "unlimit-diag.log"

    private val total = AtomicInteger(0)

    @Volatile
    private var file: File? = null

    @Volatile
    private var truncated = false

    /** 进程就绪时调用：写文件头。此时 Application 已创建，能正常解析路径。 */
    fun startSession(label: String) {
        val target = resolve() ?: return
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText("=== $label @ ${now()} ===\n\n")
        }
        truncated = true
        Log.i(TAG, "diag file: ${target.absolutePath}")
    }

    fun log(key: String, message: String) {
        if (total.incrementAndGet() > LIMIT) return
        val line = "[$key] $message"
        Log.i(TAG, line)
        append(line)
    }

    fun log(message: String) = log("diag", message)

    private fun append(line: String) {
        val target = resolve() ?: return
        runCatching {
            if (!truncated) {
                target.parentFile?.mkdirs()
                target.writeText("=== session @ ${now()} ===\n\n")
                truncated = true
            }
            target.appendText("${now()} $line\n")
        }
    }

    /** 只有成功才缓存；失败留到下次重试。 */
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
