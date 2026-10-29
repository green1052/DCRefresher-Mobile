package com.green1052.dcinside

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

// DCInside 5.3.6(OneStore 빌드: "is OneStore" 검사가 true로 하드코딩됨) 기준 분석. 전면 R8 난독화; 코드는
// Remote Config 키, Kotlin 파라미터 이름 문자열, 레이아웃 때문에 남은 뷰 클래스와 R 클래스로 찾음.

/** DCInside config 객체의 Remote Config getter: [key]를 읽는 유일한 메서드. */
internal fun configGetter(key: String, type: String) = findMethod("config.$key") {
    matcher {
        returnType(type)
        paramCount(0)
        usingStrings(listOf(key), StringMatchType.Equals)
    }
}

// region 광고
private val blockAds = Patch(
    key = "block_ads",
    title = "광고 차단",
    summary = "홈, 글 목록, 본문, 댓글, 검색의 배너와 네이티브 광고, 네이버 파워링크를 불러오지 않고, 광고 SDK도 시작하지 않습니다.",
) {
    // 광고 폭포수: AdConfig.selectAds(Remote Config list) -> AdSequence(loaders) -> 네트워크. 빈 선택은
    // 앱 자체의 "설정된 광고 없음" 상태라 홈 피드에 광고 행이 추가되지 않음.
    findMethod("ads.select_ads") {
        matcher {
            returnType("java.util.List")
            paramTypes("java.util.List")
            usingStrings(listOf("config"), StringMatchType.Equals)
        }
    }.hook { ArrayList<Any?>() }

    // AdSequence(List<loader>, host)는 빈 리스트에서 즉시 반환됨. onCurrentLoadFailed 참조로 앵커링.
    val sequence = findConstructor("ads.sequence") {
        matcher {
            name("<init>")
            paramTypes("java.util.List", null)
            addInvoke { usingStrings(listOf("onCurrentLoadFailed(Ljava/lang/String;)V"), StringMatchType.Equals) }
        }
    }
    sequence.hook { it.proceedWithArg(0, ArrayList<Any?>()) }

    // 각 광고 슬롯 설정(AdSequence를 만드는 모든 곳)은 config를 읽기 전에 컨테이너를 보이고 50-250dp를
    // 예약함; 건너뛰면 컨테이너는 레이아웃 선언대로 접힌 채 유지. 5.3.6: 게시글 목록 페이지 행과
    // 퀵 배너, 본문 푸터(2), 좋아요 아래/댓글/이미지 광고 뷰, 네이버 검색 광고(2).
    val sequenceClass = sequence.declaringClass.name
    cachedLines("ads.slot_setups") {
        findMethod { matcher { addInvoke { declaredClass(sequenceClass); name("<init>") } } }
            .filter { it.isMethod && it.className != sequenceClass }
            .map { it.descriptor }
    }.map { it.toMethod() }
        .onEach { check(it.returnType == Void.TYPE) { "ad slot setup returns ${it.returnType}: $it" } }
        .ifEmpty { error("ads.slot_setups: no match") }
        .hookAll { null }

    // AdUtil.adInitialize(activity): HomeActivity의 AdMob + AdPie + AdMixer 부트스트랩.
    findMethod("ads.initialize") {
        matcher {
            returnType("void")
            paramTypes("androidx.appcompat.app.AppCompatActivity")
            addInvoke("Lcom/gomfactory/adpie/sdk/AdPieSDK;->getInstance()Lcom/gomfactory/adpie/sdk/AdPieSDK;")
        }
    }.replace(null)

    // AdFit 배너, 생성되는 모든 위치.
    "com.kakao.adfit.ads.ba.BannerAdView".toClass().getMethod("loadAd").replace(null)

    // "_read_like_bottom_ad_enable": 게시글 좋아요 버튼 아래 광고 행.
    configGetter("_read_like_bottom_ad_enable", "boolean").replace(false)

    // 게시글 목록 하단 퀵 배너 띠(50dp)와 구분선; 앱은 이들을 숨기기만 함. fragment_post_list만
    // 이들을 담고 그 바인딩이 id로 inflate하므로, 다른 inflate는 int 비교 한 번이면 됨.
    val postList = res("layout", "fragment_post_list")
    val quickAdIds = intArrayOf(res("id", "list_quick_ad_wrap"), res("id", "list_quick_ad_wrap_divider"))
    LayoutInflater::class.java.getMethod("inflate", Int::class.javaPrimitiveType, ViewGroup::class.java, Boolean::class.javaPrimitiveType)
        .after { chain, root ->
            if (chain.getArg(0) == postList && root is View) for (id in quickAdIds) root.findViewById<View>(id)?.visibility = View.GONE
            root
        }

    // 게시글 페이지가 WebView 안에서 요청하는 네이버 광고.
    blockWebViewUrls("app.dcinside.com/api/_naverad")
}

private val hideAdViews = Patch(
    key = "hide_ad_views",
    title = "본문 광고 자리 숨기기",
    summary = "본문 이미지 광고와, 댓글 위나 댓글 사이의 광고 자리를 숨깁니다.",
) {
    // 레이아웃용으로 이름이 남음; 광고 전용. 이미지 광고 뷰는 광고가 로드되지 않아도 생성자에서 250dp를 예약.
    val isAd = keepGone(listOf("PostReadImageAdView", "PostReadCommentAdView", "PostReadCommentTopAdView")
        .map { "com.dcinside.app.view.$it".toClass() })
    val int = Int::class.javaPrimitiveType
    // RecyclerView는 GONE 항목도 측정된 크기로 배치함. 셋 다 LinearLayout.
    LinearLayout::class.java.getDeclaredMethod("onMeasure", int, int).hook { chain ->
        if (isAd(chain.thisObject)) (chain.thisObject as View).collapse() else chain.proceed()
    }
}

// endregion

// region 네트워크 필터
private val blockAdHosts = Patch(
    key = "block_ad_hosts",
    title = "광고 서버 차단",
    summary = "AdMob, AppLovin, Moloco, 카카오 애드핏, Cauly, AdMixer, AdPie 광고 서버 접속을 막습니다.",
) {
    // 모두 5.3.6의 문자열에서 확인됨.
    blockHosts(
        listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "mobileads.google.com",
            "applovin.com", "applvn.com", "moloco.com", "adsmoloco.com",
            "ad.daum.net", "ds.kakao.com", "cauly.co.kr", "fsnsys.com", "admixer.co.kr", "adpies.com",
        )
    )
    // Chromium이 호스트를 직접 resolve함; 앱의 WebViewClient는 super를 호출.
    blockWebViewHosts()
}

private val blockTrackingHosts = Patch(
    key = "block_tracking_hosts",
    title = "추적 서버 차단",
    summary = "Firebase Analytics, Crashlytics, 네이버 애널리틱스, 카카오 광고 이벤트 수집 서버 접속을 막습니다.",
) {
    blockHosts(
        listOf(
            "app-measurement.com", "google-analytics.com", "firebase-settings.crashlytics.com",
            "crashlyticsreports-pa.googleapis.com", "firebaselogging-pa.googleapis.com",
            "wcs.naver.net", "aem-kakao-collector.onkakao.net",
        )
    )
    // Chromium이 호스트를 직접 resolve함; 앱의 WebViewClient는 super를 호출.
    blockWebViewHosts()
}

// endregion

// region 추적 SDK
private val disableFirebaseAnalytics = Patch(
    key = "disable_firebase_analytics",
    title = "Firebase Analytics 비활성화",
    summary = "Firebase Analytics 이벤트 기록과 수집을 끕니다.",
) {
    // 퍼블릭 API도 R8이 이름을 바꿈; 헬퍼가 logEvent의 고유 시그니처로 폴백.
    disableFirebaseLogEvent()
    // 인앱 측정.
    disableAnalyticsCollection("firebase.analyticsStatus")
}

private val disableCrashReports = Patch(
    key = "disable_crash_reports",
    title = "오류 보고 비활성화",
    summary = "Crashlytics 오류 보고와 앱 자체 오류 기록 전송을 끕니다.",
) {
    // DataCollectionArbiter.isAutomaticDataCollectionEnabled(): 유일한 public boolean getter.
    findMethod("crashlytics.collection_enabled") {
        matcher {
            declaredClass { addMethod { usingStrings(listOf("Crashlytics automatic data collection %s by %s."), StringMatchType.Equals) } }
            modifiers(Modifier.PUBLIC)
            returnType("boolean")
            paramCount(0)
        }
    }.replace(false)
    listOf("bus", "list", "read", "web").forEach { configGetter("report_crash_on_$it", "boolean").replace(false) }
}

private val disablePerformance = Patch(
    key = "disable_performance_monitoring",
    title = "성능 측정 비활성화",
    summary = "Firebase Performance의 화면과 네트워크 성능 수집을 끕니다.",
) {
    // 앱이 이 플래그를 FirebasePerformance.setPerformanceCollectionEnabled에 전달.
    configGetter("performance_enable", "boolean").replace(false)
}

// endregion

internal val adPatches = listOf(blockAds, hideAdViews, blockAdHosts)
internal val trackingPatches = listOf(blockTrackingHosts, disableFirebaseAnalytics, disableCrashReports, disablePerformance)
