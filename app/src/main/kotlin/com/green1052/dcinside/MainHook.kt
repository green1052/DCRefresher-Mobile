package com.green1052.dcinside

import android.app.Application
import android.app.Instrumentation
import android.content.SharedPreferences
import android.widget.Toast
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File

/** 이 모듈이 패치하는 앱과, 원격 프리퍼런스 그룹·DexKit 캐시 이름. */
const val TARGET_PACKAGE = "com.dcinside.app.android"
const val TARGET_NAME = "디시인사이드"
const val PREFS_KEY = "dcinside"

val allPatches = patchGroups.flatMap { it.patches }

fun dexCacheFile(dir: File) = File(dir, "dcrefresher_${PREFS_KEY}_dexkit.properties")

class MainHook : XposedModule() {
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var settingsChanged = false
    @Volatile private var installFinished = false

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        if (param.packageName != TARGET_PACKAGE) return
        xposed = this
        classLoader = param.classLoader
        prefs = getRemotePreferences(PREFS_KEY)

        val info = param.applicationInfo
        dataDir = File(info.dataDir)
        // 패치는 앱의 모든 프로세스(푸시, 동기화, ...)에 적용, 토스트는 메인 프로세스에서만 표시.
        val main = Application.getProcessName() == TARGET_PACKAGE
        // 설정 변경 시(원격 프리퍼런스 리스너) 앱의 모든 프로세스가 스스로 종료되어, 다음 실행에서 모든 설정을
        // 처음부터 적용. 쓰기는 리스너 발동 전에 커밋되므로 유실 없음.
        // 필드로 유지: SharedPreferences는 리스너를 약하게 참조. 설치 전에 등록: 설치 중 변경(콜드 DexKit
        // 스캔은 수 초 걸림)이 새면 안 되지만 즉시 kill하면 캐시 쓰기가 잘릴 수 있어, 설치 중 변경은
        // 설치가 끝난 뒤에야 프로세스를 종료.
        settingsChanged = false
        installFinished = false
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && allPatches.none { key in it.keys }) return@OnSharedPreferenceChangeListener
            if (installFinished) endProcess() else settingsChanged = true
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        val failed = Dex.use(info.sourceDir, dexCacheFile(dataDir)) {
            allPatches.mapNotNull { patch ->
                runCatching(patch::install).exceptionOrNull()?.let {
                    log("$PREFS_KEY: patch failed: ${patch.key}", it)
                    patch.title
                }
            }
        }
        installFinished = true
        if (settingsChanged) endProcess()
        if (failed.isNotEmpty() && main) {
            Instrumentation::class.java.getMethod("callApplicationOnCreate", Application::class.java).after { chain, r ->
                val titles = failed.joinToString("\n")
                Toast.makeText(chain.getArg(0) as Application, str(R.string.patch_failed, TAG, titles), Toast.LENGTH_LONG).show()
                r
            }
        }
    }

    private fun endProcess() {
        log("$PREFS_KEY: settings changed, ending ${Application.getProcessName()}")
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
