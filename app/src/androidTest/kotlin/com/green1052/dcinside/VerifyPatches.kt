package com.green1052.dcinside

import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.DexClassLoader
import io.github.libxposed.api.XposedInterface
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Executable
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * Xposed 없이 실제 base APK 대상으로 모든 패치(전부 켬) 설치: DexKit 조회와 리플렉션은 진짜, 훅은
 * 기록만. 각각 두 번 실행, 두 번째는 이후 실행처럼 DexKit 캐시만으로. cache/dcinside.apk에 APK를 넣어야
 * 함, 예:
 *   adb push base.apk /data/local/tmp/dcinside.apk
 *   adb shell run-as com.green1052.dcinside cp /data/local/tmp/dcinside.apk cache/dcinside.apk
 */
@RunWith(AndroidJUnit4::class)
class VerifyPatches {
    @Test
    fun allPatchesResolve() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val hooks = ArrayList<Executable>()
        xposed = fakeXposed(ctx.applicationInfo, hooks)
        dataDir = ctx.cacheDir

        val failures = ArrayList<String>()
        val apk = File(ctx.cacheDir, "$PREFS_KEY.apk").apply { setReadOnly() }
        assertTrue("cache/dcinside.apk not found (see class docs)", apk.exists())
        classLoader = DexClassLoader(apk.path, ctx.codeCacheDir.path, null, ClassLoader.getSystemClassLoader().parent)
        prefs = ctx.getSharedPreferences("verify_$PREFS_KEY", 0).apply {
            edit(commit = true) { clear(); allPatches.forEach { putBoolean(it.key, true) } }
        }
        val cache = dexCacheFile(ctx.cacheDir).apply { delete() }

        // 콜드(DexKit 조회) 후 웜(캐시만).
        for (run in listOf("cold", "warm")) {
            var failed = 0
            Dex.use(apk.path, cache) {
                for (patch in allPatches) {
                    val before = hooks.size
                    val error = runCatching(patch::install).exceptionOrNull()
                    val line = "$PREFS_KEY.${patch.key} ($run): ${if (error == null) "OK, ${hooks.size - before} hooks" else "FAIL $error"}"
                    Log.i(TAG, line)
                    if (error != null) {
                        Log.w(TAG, patch.key, error)
                        failures += line
                        failed++
                    }
                }
            }
            // 웜 실행은 캐시만으로 처리되어야 함.
            if (run == "warm" && Dex.opened) failures += "$PREFS_KEY (warm): DexKit opened, cache incomplete"
            Log.i(TAG, "$PREFS_KEY ($run): ${allPatches.size - failed}/${allPatches.size} patches resolved")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** 훅을 기록, 진짜 프레임워크가 거부하는 것(추상 메서드)은 거부. 핸들은 [Patch.install]이 요구하는 대로 답변. */
    private fun fakeXposed(module: ApplicationInfo, hooks: MutableList<Executable>): XposedInterface {
        fun <T> proxy(type: Class<T>, handler: (String, Array<Any?>) -> Any?): T =
            type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, m, a -> handler(m.name, a ?: emptyArray()) })!!

        fun builder(target: Executable?): XposedInterface.HookBuilder {
            lateinit var self: XposedInterface.HookBuilder
            self = proxy(XposedInterface.HookBuilder::class.java) { name, _ ->
                when (name) {
                    "intercept" -> {
                        if (target != null) {
                            require(!Modifier.isAbstract(target.modifiers)) { "hooking abstract method $target" }
                            hooks += target
                        }
                        proxy(XposedInterface.HookHandle::class.java) { method, _ -> if (method == "getExecutable") target else null }
                    }
                    else -> self
                }
            }
            return self
        }

        return proxy(XposedInterface::class.java) { name, args ->
            when (name) {
                "hook" -> builder(args[0] as Executable)
                "hookClassInitializer" -> builder(null)
                "deoptimize" -> true
                "getModuleApplicationInfo" -> module
                "log" -> Log.i(TAG, args[2].toString(), args.getOrNull(3) as Throwable?).let { null }
                "getApiVersion" -> 102
                else -> error("unexpected XposedInterface.$name")
            }
        }
    }

    companion object {
        const val TAG = "DCVerify"
    }
}
