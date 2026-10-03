package io.github.jjmj.douyinunlimit.xposed

import android.view.View
import java.util.Collections
import java.util.WeakHashMap

/**
 * 被判定为「要隐藏」的控件登记表。
 *
 * 为什么需要它：文字是在 `setText` 里被识别的，但调用方经常在 `setText` **之后**
 * 再调一次 `setVisibility(VISIBLE)` 把它显示回来。例如：
 *
 *   LJI():
 *       textView.setText(...)          // 这里我们识别到并标记
 *       textView.setVisibility(VISIBLE) // 紧接着又被显示回来 ← 单靠 setText 拦不住
 *
 * 所以 setText 只负责「标记」，真正的压制交给 setVisibility：
 * 只要这个控件在登记表里，任何「显示」请求都会被改写成 GONE。
 *
 * 用 WeakHashMap 存，控件被回收后自动出表，不会把 View 泄漏住。
 * View 没有重写 equals/hashCode，所以这里等价于身份比较。
 */
internal object BlockedViews {

    private val views: MutableSet<View> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<View, Boolean>()),
    )

    fun mark(view: View) {
        views.add(view)
    }

    fun isBlocked(view: View): Boolean = views.contains(view)
}
