package com.green1052.dcrefresher.mobile

import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 앱의 R 클래스에서 가져온 리소스 id; R8이 필드 이름과 함께 남겨 둠. */
internal fun res(type: String, name: String) = "com.dcinside.app.R\$$type".toClass().getField(name).getInt(null)

/** [name]으로 직렬화되는 Gson 필드의 getter들(Kotlin 데이터 클래스는 두 개: getX와 componentN). */
internal fun serializedGetters(cls: Class<*>, name: String): List<Method> =
    cachedLines("getters.${cls.name}.$name") {
        val field = cls.declaredFields.first { f ->
            f.declaredAnnotations.any { a -> runCatching { a.annotationClass.java.getMethod("value").invoke(a) == name }.getOrDefault(false) }
        }
        getFieldData(field)!!.readers
            .filter { it.className == cls.name && it.paramCount == 0 && it.opCodes.size == 2 }
            .map { it.descriptor }
    }.map { it.toMethod() }.ifEmpty { error("no getter for \"$name\" in $cls") }

private val disableUpdatePopup = Patch(
    key = "disable_update_popup",
    title = R.string.dcinside_disable_update_popup_title,
    summary = R.string.dcinside_disable_update_popup_summary,
) {
    // 홈의 "스토어 버전이 더 최신인가(최근에 닫지 않았나)" 검사; 버전을 "\."로 분리.
    findMethod("update.is_newer") {
        matcher {
            modifiers(Modifier.PRIVATE or Modifier.FINAL)
            returnType("boolean")
            paramTypes("java.lang.String")
            usingStrings(listOf("\\."), StringMatchType.Equals)
            addInvoke("Ljava/lang/System;->currentTimeMillis()J")
        }
    }.replace(false)
}

val patchGroups = listOf(
    PatchGroup(R.string.dcinside_group_ads, adPatches),
    PatchGroup(R.string.dcinside_group_tracking, trackingPatches),
    PatchGroup(
        R.string.dcinside_group_features,
        listOf(showIdentifier, showIpInfo, autoContinueSearch, searchByIdentifier, hideOfficialNotices, hidePageIndicators, disableUpdatePopup) + mediaPatches,
    ),
)
