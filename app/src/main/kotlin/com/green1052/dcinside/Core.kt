package com.green1052.dcinside

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.annotation.StringRes
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.io.File
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Modifier

const val TAG = "DCRefresher"
const val PUBLIC_FINAL = Modifier.PUBLIC or Modifier.FINAL
const val PUBLIC_STATIC_FINAL = PUBLIC_FINAL or Modifier.STATIC
const val COROUTINE_RESUME = "call to 'resume' before 'invoke' with coroutine"

// MainHook(또는 검증 테스트)이 패치 대상 앱의 호스트 프로세스마다 한 번 설정.
lateinit var xposed: XposedInterface
lateinit var classLoader: ClassLoader
lateinit var prefs: SharedPreferences
/** 호스트의 데이터 디렉터리, 모듈 캐시용. */
lateinit var dataDir: File

private val currentApplication by lazy { Class.forName("android.app.ActivityThread").getMethod("currentApplication") }

/** 호스트의 Application, 호스트가 만들기 전엔 null. */
val appOrNull: Application? get() = currentApplication.invoke(null) as Application?

/** 호스트의 Application, 호스트가 생성된 후에만 유효. 즉 훅 콜백 안에서만. */
val app: Application get() = appOrNull ?: error("the host Application is not created yet")

/**
 * 모듈 자체 리소스, 호스트에 표시되는 모듈 UI 텍스트에 사용. 호스트의 PackageManager로 만드므로 호스트
 * Application이 있는 순간부터 유효: 패치 설치 시가 아니라 훅 콜백에서 읽을 것. 모듈 자신의 프로세스
 * (설정, 호스트 없는 테스트)에서는 app.resources.
 */
val moduleRes: Resources by lazy {
    if (::xposed.isInitialized) app.packageManager.getResourcesForApplication(xposed.moduleApplicationInfo) else app.resources
}

/** 모듈 문자열 [id]를 [args]로 포맷. */
fun str(@StringRes id: Int, vararg args: Any?): String = moduleRes.getString(id, *args)

/** 프레임워크 로그로 출력, 프레임워크가 없으면(테스트에서 헬퍼 실행 시) 일반 logcat. */
fun log(msg: String, t: Throwable? = null) {
    if (::xposed.isInitialized) xposed.log(Log.INFO, TAG, msg, t) else Log.i(TAG, msg, t)
}

// region 패치 모델
/** 패치 설정값. [color]가 true인 String 옵션은 색상 값을 담고([parseArgb] 형식) 색상 선택기를 얻음. */
class Option<T : Any>(val key: String, @StringRes val title: Int, val default: T, val color: Boolean = false) {
    @Suppress("UNCHECKED_CAST")
    val value: T
        get() = when (default) {
            is Boolean -> prefs.getBoolean(key, default)
            is String -> prefs.getString(key, default)
            else -> error("unsupported option type")
        } as T
}

/** "#RRGGBB", "#AARRGGBB", "0xAARRGGBB" 또는 10진수 int를 ARGB로 파싱, 실패 시 [fallback]. */
fun parseArgb(input: String, fallback: Int): Int = runCatching {
    val t = input.trim().replace("_", "")
    val v = when {
        t.startsWith("#") -> t.substring(1).let { h ->
            require(h.length == 6 || h.length == 8)
            h.toLong(16) or (if (h.length == 6) 0xFF000000L else 0L)
        }
        t.startsWith("0x", true) -> t.substring(2).toLong(16)
        t.startsWith("-0x", true) -> -t.substring(3).toLong(16)
        else -> t.toLong()
    }
    (v and 0xFFFFFFFFL).toInt()
}.getOrDefault(fallback)

class Patch(
    val key: String,
    @StringRes val title: Int,
    @StringRes val summary: Int = 0,
    val default: Boolean = true,
    val options: List<Option<*>> = emptyList(),
    val apply: () -> Unit,
) {
    val enabled get() = prefs.getBoolean(key, default)

    /** [apply]의 동작을 바꾸는 프리퍼런스 키들. */
    val keys get() = listOf(key) + options.map { it.key }

    /** 활성화일 때 패치 적용, 프로세스당 한 번. 설정 변경 시 앱이 종료되므로(MainHook 참고). */
    fun install() {
        if (enabled) apply()
    }
}

/** 제목 달린 설정 화면 섹션. */
class PatchGroup(@StringRes val title: Int, val patches: List<Patch>)

// endregion

// region 훅 DSL (libxposed API 101+ 인터셉터 체인)
/** 훅 예외는 삼켜짐(PROTECTIVE), [mode]가 PASSTHROUGH일 때만 아님. 일부러 예외를 던지는 훅에 필요. */
fun Executable.hook(mode: ExceptionMode = ExceptionMode.DEFAULT, hooker: (Chain) -> Any?): XposedInterface.HookHandle =
    xposed.hook(this).setExceptionMode(mode).intercept { hooker(it) }

fun Executable.before(f: (Chain) -> Unit) = hook { f(it); it.proceed() }

/** [f]는 원래 결과를 받아 (교체 가능한) 결과를 반환. */
fun Executable.after(f: (Chain, Any?) -> Any?) = hook { f(it, it.proceed()) }

fun Executable.replace(value: Any?) = hook { value }

fun Iterable<Executable>.hookAll(mode: ExceptionMode = ExceptionMode.DEFAULT, hooker: (Chain) -> Any?) =
    forEach { it.hook(mode, hooker) }

/** [index]번째 인자를 교체한 채 proceed. */
fun Chain.proceedWithArg(index: Int, value: Any?): Any? =
    proceed(args.toTypedArray().also { it[index] = value })

// endregion

// region 리플렉션
fun String.toClass(): Class<*> = classLoader.loadClass(this)

fun Class<*>.fieldDeep(name: String): Field {
    var c: Class<*>? = this
    while (c != null) {
        runCatching { return c.getDeclaredField(name).apply { isAccessible = true } }
        c = c.superclass
    }
    throw NoSuchFieldException("$name in $this")
}

fun Any.getField(name: String): Any? = javaClass.fieldDeep(name).get(this)

fun Any.call(name: String, vararg args: Any?): Any? {
    var c: Class<*>? = javaClass
    while (c != null) {
        c.declaredMethods.firstOrNull { it.name == name && it.parameterCount == args.size }?.let {
            it.isAccessible = true
            return it.invoke(this, *args)
        }
        c = c.superclass
    }
    throw NoSuchMethodException("$name/${args.size} in $javaClass")
}

// endregion

// region 호스트 도우미
/** 호스트의 kotlin.Unit / kotlin.Pair, 모듈이 자체 stdlib을 껴안고 있어 클래스가 다름. */
val hostUnit: Any by lazy { "kotlin.Unit".toClass().selfInstance() }

private val hostPairCtor by lazy { "kotlin.Pair".toClass().getConstructor(Any::class.java, Any::class.java) }

fun hostPair(first: Any?, second: Any?): Any = hostPairCtor.newInstance(first, second)

/** Kotlin object의 싱글턴을 담는 자기 타입 static 필드, R8이 이름을 바꿔도 무관. 클래스 초기화하지 않음. */
fun Class<*>.selfField(): Field =
    declaredFields.first { Modifier.isStatic(it.modifiers) && it.type == this }.apply { isAccessible = true }

fun Class<*>.selfInstance(): Any = selfField().get(null)!!

val mainHandler by lazy { Handler(Looper.getMainLooper()) }

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** JVM 기본 타입들의 DexKit 타입명("void" 제외). */
val PRIMITIVES = setOf("boolean", "byte", "char", "short", "int", "long", "float", "double")

/** JDK, Kotlin, Android 프레임워크 코드가 아님. */
fun String.isAppClass() = LIBRARY_PREFIXES.none(::startsWith)

private val LIBRARY_PREFIXES = listOf("java.", "javax.", "kotlin.", "android.", "androidx.")

/** [m]이 실제로 callee를 호출하게 해 ART가 인라인한 작은 callee의 훅도 발동. 실패는 로그만. */
fun deoptimize(m: Executable) {
    runCatching { xposed.deoptimize(m) }.onFailure { log("deoptimize $m failed", it) }
}

/** 이 메서드 실행 중 [local]이 [value]를 유지(기본: receiver, static이면 true), 안쪽 훅이 이 범위를 판별 가능. */
@Suppress("UNCHECKED_CAST")
fun <T> Executable.scope(local: ThreadLocal<T>, value: (Chain) -> T = { (it.thisObject ?: true) as T }) = hook { chain ->
    val prev = local.get()
    local.set(value(chain))
    try {
        chain.proceed()
    } finally {
        local.set(prev)
    }
}

private var itemViewField: Field? = null

/** RecyclerView.ViewHolder.itemView, R8이 이름을 바꿀 수 있어 타입으로 탐색. */
fun itemView(holder: Any): View {
    val f = itemViewField ?: generateSequence<Class<*>>(holder.javaClass) { it.superclass }
        .filter { it.name.startsWith("androidx.recyclerview.widget.RecyclerView$") }
        .flatMap { it.declaredFields.asSequence() }
        .first { it.type == View::class.java }
        .apply { isAccessible = true }
        .also { itemViewField = it }
    return f.get(holder) as View
}

/**
 * GONE 유지 대상 뷰들의 판별 함수, 전부 View.setVisibility 훅 하나가 처리(모든 뷰에 실행됨). 훅이 이터레이터를
 * 만들지 않도록 배열이고, 아무 스레드에서 실행되니 교체만 하고 변경은 하지 않음.
 */
@Volatile private var goneViews = emptyArray<(Any?) -> Boolean>()
private var goneHookInstalled = false

/** [except]를 제외한 [types]의 모든 인스턴스를 GONE 유지, 인스턴스 판별 함수 반환. */
fun keepGone(types: List<Class<*>>, except: Class<*>? = null): (Any?) -> Boolean {
    val classes = types.toTypedArray()
    val matches = { v: Any? -> classes.any { it.isInstance(v) } && except?.isInstance(v) != true }
    // 인플레이션은 setVisibility를 거치지 않고 초기 가시성을 정하므로, 생성 직후 숨김.
    types.flatMap { it.declaredConstructors.asList() }.hookAll { chain ->
        chain.proceed().also { if (matches(chain.thisObject)) (chain.thisObject as View).visibility = View.GONE }
    }
    if (!goneHookInstalled) {
        goneHookInstalled = true
        View::class.java.getMethod("setVisibility", Int::class.javaPrimitiveType).hook { chain ->
            val view = chain.thisObject
            if (goneViews.any { it(view) }) chain.proceedWithArg(0, View.GONE) else chain.proceed()
        }
    }
    goneViews = goneViews + matches
    return matches
}

/** View.setMeasuredDimension (protected), 한 번만 조회: 레이아웃 패스마다 onMeasure 훅에서 호출됨. */
private val setMeasuredDimension by lazy {
    View::class.java.getDeclaredMethod("setMeasuredDimension", Int::class.java, Int::class.java).apply { isAccessible = true }
}

/** 0x0으로 측정: RecyclerView는 GONE 항목도 측정 크기로 배치함. */
fun View.collapse() {
    setMeasuredDimension.invoke(this, 0, 0)
}

// endregion
