package com.green1052.dcrefresher.mobile

import android.os.Bundle
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Properties

// region APK별 캐시를 가진 DexKit
object Dex {
    private var bridge: DexKitBridge? = null
    private lateinit var apkPath: String
    private lateinit var cacheFile: File
    private val cache = Properties()
    private var dirty = false

    /** 현재 [use] 중 DexKit을 열었는지, 캐시가 있으면 false로 남음. */
    var opened = false
        private set

    /** 지연 오픈: 캐시가 있으면 DexKit을 아예 로드하지 않음. */
    private val kit: DexKitBridge
        get() = bridge ?: run {
            System.loadLibrary("dexkit")
            opened = true
            DexKitBridge.create(apkPath).also { bridge = it }
        }

    fun <T> use(apk: String, cachePath: File, block: () -> T): T {
        apkPath = apk
        cacheFile = cachePath
        cache.clear()
        dirty = false
        opened = false
        // 호스트나 모듈 업데이트 시 무효화. 경로와 크기도 포함: OTA 업데이트된 시스템 앱은 이미지 타임스탬프를 유지.
        val id = File(apk).let { "${it.path}-${it.length()}-${it.lastModified()}" } +
            "-${File(xposed.moduleApplicationInfo.sourceDir).lastModified()}"
        runCatching { cacheFile.inputStream().use(cache::load) }
        if (cache.getProperty("#id") != id) {
            cache.clear()
            cache.setProperty("#id", id)
            dirty = true
        }
        try {
            return block()
        } finally {
            bridge?.close()
            bridge = null
            // 여러 호스트 프로세스가 동시에 쓸 수 있음: 옆에 쓰고 원자적으로 rename.
            if (dirty) {
                val tmp = File(cacheFile.path + ".${android.os.Process.myPid()}")
                runCatching {
                    tmp.outputStream().use { cache.store(it, null) }
                    check(tmp.renameTo(cacheFile)) { "rename failed" }
                }.onFailure { log("DexKit cache not saved: $cacheFile", it) }
                tmp.delete()
            }
        }
    }

    /**
     * DexKit으로 얻은 문자열(보통 descriptor)을 [key] 아래 캐시. 실패한 조회는 호스트나 모듈이 바뀌기 전까지
     * 계속 실패하므로 실패도 캐시("!key"): 망가진 패치가 매 실행마다 DexKit을 로드하지 않게 함.
     */
    fun cached(key: String, compute: DexKitBridge.() -> String): String {
        cache.getProperty(key)?.let { return it }
        cache.getProperty("!$key")?.let { error("$key: $it (cached)") }
        val k = kit // DexKit 오픈은 일시적으로 실패할 수 있음, 조회 자체의 실패만 캐시.
        val value = try {
            k.compute()
        } catch (e: Exception) {
            cache.setProperty("!$key", e.toString())
            dirty = true
            throw e
        }
        cache.setProperty(key, value)
        dirty = true
        return value
    }
}

/** [m]은 descriptor, Method 또는 Constructor. */
fun DexKitBridge.methodData(m: Any): MethodData = when (m) {
    is String -> getMethodData(m)
    is Method -> getMethodData(m)
    is Constructor<*> -> getMethodData(m)
    else -> null
} ?: error("no method data: $m")

fun <T> List<T>.one(key: String): T =
    singleOrNull() ?: error("$key: expected 1 match, found $size ${take(4)}")

/** Morphe는 DexKit이 여러 개를 반환할 때 dex 순(클래스, 이름)의 첫 매치를 취함. */
fun List<MethodData>.firstInDex(key: String): MethodData =
    minWithOrNull(compareBy({ it.className }, { it.name })) ?: error("$key: no match")

fun String.toMethod(): Method = DexMethod(this).getMethodInstance(classLoader).apply { isAccessible = true }
fun String.toConstructor(): Constructor<*> = DexMethod(this).getConstructorInstance(classLoader).apply { isAccessible = true }
fun String.toExecutable(): Executable = if (DexMethod(this).isConstructor) toConstructor() else toMethod()
fun String.toField(): Field = DexField(this).getFieldInstance(classLoader).apply { isAccessible = true }

fun findMethod(key: String, query: FindMethod.() -> Unit): Method =
    Dex.cached(key) { findMethod(query).one(key).descriptor }.toMethod()

fun findConstructor(key: String, query: FindMethod.() -> Unit): Constructor<*> =
    Dex.cached(key) { findMethod(query).one(key).descriptor }.toConstructor()

/** 문자열 목록(보통 descriptor들)을 [key] 아래 캐시. */
fun cachedLines(key: String, compute: DexKitBridge.() -> List<String>): List<String> =
    Dex.cached(key) { compute().joinToString("\n") }.lines().filter(String::isNotEmpty)

fun findMethods(key: String, query: FindMethod.() -> Unit): List<Method> =
    cachedLines(key) { findMethod(query).map { it.descriptor } }.map { it.toMethod() }

fun findClass(key: String, query: FindClass.() -> Unit): Class<*> =
    Dex.cached(key) { findClass(query).one(key).name }.toClass()

/** Firebase measurement의 수집 상태 메서드(0 = 활성)가 3을 반환하게 함, setAnalyticsCollectionEnabled(false) 이후처럼. */
fun disableAnalyticsCollection(key: String) = findMethod(key) {
    matcher {
        returnType("int")
        paramCount(0)
        usingStrings(listOf("firebase_analytics_collection_enabled"), StringMatchType.Equals)
    }
}.replace(3)

/** FirebaseAnalytics.logEvent(String, Bundle) 제거. R8이 이름을 유지했으면 이름으로, 아니면 유일한 인스턴스 void (String, Bundle) 메서드로 탐색. */
fun disableFirebaseLogEvent() {
    val analytics = "com.google.firebase.analytics.FirebaseAnalytics".toClass()
    val logEvent = runCatching { analytics.getMethod("logEvent", String::class.java, Bundle::class.java) }.getOrNull()
        ?: analytics.declaredMethods.single {
            !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(String::class.java, Bundle::class.java))
        }
    logEvent.replace(null)
}

/** Crashlytics 자동 수집 차단: isAutomaticDataCollectionEnabled가 false를 답해 저장된 리포트는 절대 전송되지 않음. 일부 앱에선 R8이 메서드를 리네임(쿠팡이츠 d()), 그럴 땐 유일한 synchronized boolean 시그니처로 폴백. */
fun disableCrashlyticsCollection() {
    val arbiter = "com.google.firebase.crashlytics.internal.common.DataCollectionArbiter".toClass()
    val enabled = runCatching { arbiter.getMethod("isAutomaticDataCollectionEnabled") }.getOrNull()
        ?: arbiter.declaredMethods.single {
            it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType && Modifier.isSynchronized(it.modifiers)
        }
    enabled.replace(false)
}

/** AppsFlyerLib의 유일한 구현체는 start/logEvent 이름을 유지, 둘 다 no-op. OneLink 딥링크가 이 콜백을 기다리므로 해당 호스트도 이 스위치에 속함. */
fun disableAppsFlyer() {
    val impl = findClass("appsflyer.impl") { matcher { superClass("com.appsflyer.AppsFlyerLib") } }
    impl.declaredMethods.filter { it.name == "start" || it.name == "logEvent" }
        .ifEmpty { error("AppsFlyer start/logEvent not found in $impl") }
        .hookAll { null }
}

// endregion
