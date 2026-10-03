package io.github.jjmj.douyinunlimit.xposed

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 诊断日志。上限封死，避免滚 feed 时把 logcat 刷爆。
 *
 * on: su -c "logcat -d -s DouyinUnlimit"
 */
internal object Diag {

    const val TAG = "DouyinUnlimit"

    private const val LIMIT = 60

    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    fun log(key: String, message: String) {
        val counter = counters.getOrPut(key) { AtomicInteger(0) }
        val n = counter.incrementAndGet()
        if (n <= LIMIT) {
            Log.i(TAG, "$message   [#$n]")
        } else if (n == LIMIT + 1) {
            Log.i(TAG, "$key 日志已达上限 $LIMIT 条，后续不再打印")
        }
    }
}
