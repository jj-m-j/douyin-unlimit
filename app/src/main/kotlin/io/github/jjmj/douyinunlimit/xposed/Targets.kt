package io.github.jjmj.douyinunlimit.xposed

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 结构定位工具 —— 按「形状」找类 / 方法 / 字段，而不是写死名字。
 *
 * ## 为什么需要它
 *
 * 抖音每个版本都会重新混淆一批名字：
 *
 * | 目标 | 40.2.0 里的叫法 | 下个版本 |
 * |---|---|---|
 * | 发送状态的基类 | `LX/179c` | 混淆名，**必变** |
 * | 显示 / 隐藏方法 | `LIZ` / `LJI` / `LJJJJZ` … | 混淆名，**会变** |
 * | 图标 / 文字字段 | `b` / `f` | 混淆名，**会变** |
 * | 业务类名 | `FeedDiggPresenter`、`StatusIconWithText` | R8 保留下来的可读名，**基本不变** |
 *
 * 写死前三行的代码换版本就全废，而且废得很难查（日志里只有一句「找不到」）。
 * 但它们的**形状**是稳定的，所以：
 *
 *   - 基类再怎么改名，它仍然是 `StatusIconWithText` 的**父类** -> 用 `superclass` 拿
 *   - 状态图标再怎么改名，它仍然是那个类里的 **`ImageView` 字段** -> 按类型找
 *   - 回滚方法再怎么改名，它仍然**收一个 `Exception`** -> 按参数类型找
 *   - 负责显示的方法再怎么改名，它仍然是**无参 void** -> 按形状找
 *
 * 一句话：**名字会变的，一律按形状找；只写死真正稳定的业务类名。**
 *
 * ## 定位结果都要进日志
 *
 * 所有 `load` / `find*` 的结果都由调用方写进诊断日志。这样换版本之后，
 * 一份日志就能看出「哪个目标没找到」，而不是只看到「功能失效」。
 */
internal object Targets {

    /**
     * 生命周期 / 框架回调。这些名字不会被混淆（因为要覆写系统方法），
     * 但把它们拦掉会破坏原有状态机，所以一律排除。
     */
    private val CALLBACK_NAMES = setOf(
        "onResume", "onPause", "onStart", "onStop", "onDestroy", "onCreate",
        "onAttachedToWindow", "onDetachedFromWindow",
        "onViewAttachedToWindow", "onViewDetachedFromWindow",
        "onChanged", "onBootFinished",
    )

    /** 继承链最多往上找几层，防止意外的深继承把开销放大。 */
    private const val MAX_SUPER_DEPTH = 8

    /**
     * 聊天里那条「发送失败」的红色叹号。
     *
     * 真机视图树（用户提供，直接读出来的）：
     * ```
     * com.ss.android.ugc.exview.ImImageView{... #7f0ab151 app:id/04_ ...}
     * ```
     *
     * ## 为什么这里必须回到「按资源 id」—— 和别处的原则不冲突
     *
     * 别处一律不写死 id，因为这个 id 会随抖音版本重新分配。但这一条没有别的选择：
     * 它所在的组件（`StatusIconWithText`）里那些方法都只有十几个指令，
     * **会被 ART 内联**，hook 挂上去也永远不会被调用（libxposed 文档明确警告过这点，
     * 真机日志也证实了：连「进入」都没有）。而控件本身是聊天 cell 布局里就有的，
     * 除了 id 和类名没有任何稳定特征。
     *
     * 所以这里按「类名 + id」双重匹配，并且安装时会把 id 当前对应什么写进日志 ——
     * 一旦抖音改了资源分配，日志里能立刻看出来，而不是功能默默失灵。
     * 重新取值的方法：打开详细日志，点一下那个图标，点击探针会写出它的 `#0x...`。
     */
    const val SEND_FAIL_ICON_ID = 0x7f0ab151

    const val SEND_FAIL_ICON_CLASS = "com.ss.android.ugc.exview.ImImageView"

    /** 按候选名依次尝试加载，返回第一个成功的。 */
    fun load(loader: ClassLoader, vararg names: String): Class<*>? {
        for (name in names) {
            val clazz = runCatching { Class.forName(name, false, loader) }.getOrNull()
            if (clazz != null) return clazz
        }
        return null
    }

    /** 类**及其父类**上所有非静态字段。 */
    fun fieldsOfType(clazz: Class<*>, type: Class<*>): List<Field> {
        val out = mutableListOf<Field>()
        walk(clazz) { current ->
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                if (!type.isAssignableFrom(field.type)) continue
                runCatching { field.isAccessible = true }
                out += field
            }
        }
        return out
    }

    /** 单参、且参数类型可赋值给 [param] 的实例方法。 */
    fun methodsTaking(clazz: Class<*>, param: Class<*>): List<Method> =
        clazz.declaredMethods.filter {
            !Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 1 &&
                param.isAssignableFrom(it.parameterTypes[0])
        }

    /** 参数个数为 [count]、且第 [index] 个参数可赋值给 [type] 的实例方法。 */
    fun methodsWithParamAt(
        clazz: Class<*>,
        index: Int,
        count: Int,
        type: Class<*>,
    ): List<Method> = clazz.declaredMethods.filter {
        !Modifier.isStatic(it.modifiers) &&
            it.parameterCount == count &&
            type.isAssignableFrom(it.parameterTypes[index])
    }

    /**
     * 无参、返回 void 的实例方法，排除生命周期回调和编译器生成的方法。
     *
     * 用于「整个类只服务一个组件」的场景（`ChatBanTipsLogic` 就是这样）：
     * 不需要知道哪个方法负责显示，把这个类所有「动作」方法都拦掉即可。
     */
    fun zeroArgVoidActions(clazz: Class<*>): List<Method> =
        clazz.declaredMethods.filter {
            !Modifier.isStatic(it.modifiers) &&
                !it.isSynthetic &&
                it.parameterCount == 0 &&
                it.returnType == Void.TYPE &&
                it.name !in CALLBACK_NAMES
        }

    /** 所有构造方法（含被混淆的参数列表）。 */
    fun constructorsOf(clazz: Class<*>): List<Constructor<*>> =
        clazz.declaredConstructors.toList()

    private inline fun walk(start: Class<*>, action: (Class<*>) -> Unit) {
        var current: Class<*>? = start
        var depth = 0
        while (depth++ < MAX_SUPER_DEPTH) {
            val c = current ?: return
            if (c == Any::class.java) return
            action(c)
            current = c.superclass
        }
    }
}
