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
 * 为什么要写文件：logcat 的环形缓冲很小，抖音启动后很快就滚掉了；
 * 用 `-s TAG` 过滤也容易因为参数写错而一无所获。写文件可以事后慢慢看。
 *
 * 文件位置（抖音的外部私有目录，MT 用 root 能直接打开）：
 *   /storage/emulated/0/Android/data/com.ss.android.ugc.aweme/files/unlimit-diag.log
 *
 * 总条数上限封死，避免滚 feed 时把文件写爆。
 */
internal object Diag {

    const val TAG = "DouyinUnlimit"

    private const val LIMIT = 400
    private const val FILE_NAME = "unlimit-diag.log"

    private val total = AtomicInteger(0)

    @Volatile
    private var file: File? = null

    @Volatile
    private var resolved = false

    /** 进程注入时调用一次：清空上次内容，写下文件位置方便定位。 */
    fun startSession(label: String) {
        val target = resolveFile() ?: return
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText("=== $label @ ${now()} ===\n\n")
        }
    }

    fun log(key: String, message: String) {
        val n = total.incrementAndGet()
        if (n > LIMIT) return
        val line = "[$key] $message"
        Log.i(TAG, line)
        val target = resolveFile() ?: return
        runCatching { target.appendText("${now()} $line\n") }
    }

    fun log(message: String) = log("diag", message)

    /** 把日志文件路径也打出来，方便用户直接去拿。 */
    fun logFileLocation() {
        Log.i(TAG, "diag file: ${file?.absolutePath ?: "unavailable"}")
    }

    private fun resolveFile(): File? {
        if (resolved) return file
        resolved = true
        file = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val app = activityThread.getMethod("currentApplication").invoke(null) as? Context
                ?: return null
            val dir = app.getExternalFilesDir(null) ?: app.filesDir ?: return null
            File(dir, FILE_NAME)
        }.getOrNull()
        return file
    }

    private fun now(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
}
