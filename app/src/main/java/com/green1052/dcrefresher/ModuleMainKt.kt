package com.green1052.dcrefresher

import android.util.Log
import android.view.View
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class ModuleMainKt : XposedModule() {
    companion object {
        const val TAG = "DCRefresher"
        private val AD_CONTAINER_IDS = setOf(
            0x7f0b0080, // ad_container_view
            0x7f0b07df, // list_quick_ad_wrap
            0x7f0b07e0, // list_quick_ad_wrap_bg
            0x7f0b07e1, // list_quick_ad_wrap_divider
            0x7f0b0bad, // post_list_item_page_ad_container
            0x7f0b0d9b, // read_footer_ad_container
            0x7f0b0faf, // search_naver_ad_container
        )
    }

    private var adViewClasses: Set<Class<*>> = emptySet()

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!param.isFirstPackage) return

        // Ad SDK load/show — block ads from loading or displaying
        mapOf(
            "com.fsn.cauly.CaulyAdBannerView" to arrayOf("show", "load"),
            "com.fsn.cauly.CaulyCloseAd" to arrayOf("show"),
            "com.fsn.cauly.CaulyInterstitialAd" to arrayOf("show"),
            "com.fsn.cauly.CaulyIconAd" to arrayOf("show"),
            "com.fsn.cauly.CaulyRewardAd" to arrayOf("show"),
            "com.fsn.cauly.CaulyCustomAd" to arrayOf("requestAd", "requestAdData", "requestAdView"),
            "com.gomfactory.adpie.sdk.AdView" to arrayOf("load", "loadNextAd", "showAdContent"),
            "com.gomfactory.adpie.sdk.InterstitialAd" to arrayOf("load", "show"),
            "com.gomfactory.adpie.sdk.RewardedAd" to arrayOf("load", "show"),
            "com.gomfactory.adpie.sdk.RewardedVideoAd" to arrayOf("load", "show"),
            "com.gomfactory.adpie.sdk.NativeAd" to arrayOf("loadAd"),
            "com.applovin.mediation.ads.MaxAdView" to arrayOf("loadAd"),
            "com.applovin.mediation.ads.MaxInterstitialAd" to arrayOf("loadAd", "showAd"),
            "com.applovin.mediation.ads.MaxRewardedAd" to arrayOf("loadAd", "showAd"),
            "com.applovin.mediation.ads.MaxAppOpenAd" to arrayOf("loadAd", "showAd"),
            "com.kakao.adfit.ads.ba.BannerAdView" to arrayOf("loadAd"),
            "com.nasmedia.admixerssp.ads.AMMBannerView" to arrayOf("loadAd", "showAd"),
            "com.nasmedia.admixerssp.ads.AMMInterstitial" to arrayOf("loadInterstitial", "loadAd", "showAd", "showInterstitial"),
            "com.nasmedia.admixerssp.ads.AMMRewardVideo" to arrayOf("loadRewardVideoAd", "loadAd", "showAd", "showRewardVideoAd"),
            "com.nasmedia.admixerssp.ads.AMMVideoInterstitial" to arrayOf("loadInterstitialVideoAd", "loadAd", "showAd"),
            "com.nasmedia.admixerssp.ads.AMMVideoView" to arrayOf("loadAd", "showAd"),
            "com.nasmedia.admixerssp.ads.AMMNativeAdView" to arrayOf("loadAd", "loadNativeAdView", "showAd"),
            "com.google.android.gms.ads.BaseAdView" to arrayOf("loadAd"),
            "com.google.android.gms.ads.admanager.AdManagerAdView" to arrayOf("loadAd"),
            "com.google.android.gms.ads.AdLoader" to arrayOf("loadAd", "loadAds"),
            "com.google.android.gms.ads.interstitial.InterstitialAd" to arrayOf("load"),
            "com.google.android.gms.ads.rewarded.RewardedAd" to arrayOf("load"),
            "com.google.android.gms.ads.mediation.Adapter" to arrayOf(
                "loadAppOpenAd", "loadBannerAd", "loadInterstitialAd", "loadNativeAd",
                "loadNativeAdMapper", "loadRewardedAd", "loadRewardedInterstitialAd"),
            "com.google.android.gms.ads.mediation.rtb.RtbAdapter" to arrayOf(
                "loadRtbAppOpenAd", "loadRtbBannerAd", "loadRtbInterstitialAd", "loadRtbNativeAd",
                "loadRtbNativeAdMapper", "loadRtbRewardedAd", "loadRtbRewardedInterstitialAd"),
        ).forEach { (cls, methods) -> noop(param.classLoader, cls, *methods) }

        // Ad view classes — for setVisibility hook
        adViewClasses = listOf(
            "com.fsn.cauly.CaulyAdBannerView", "com.fsn.cauly.CaulyAdView",
            "com.gomfactory.adpie.sdk.AdView", "com.applovin.mediation.ads.MaxAdView",
            "com.kakao.adfit.ads.ba.BannerAdView",
            "com.nasmedia.admixerssp.ads.AMMBannerView", "com.nasmedia.admixerssp.ads.AMMVideoView",
            "com.nasmedia.admixerssp.ads.AMMNativeAdView",
            "com.google.android.gms.ads.BaseAdView", "com.google.android.gms.ads.admanager.AdManagerAdView",
            "com.google.android.gms.ads.AdView"
        ).mapNotNull { runCatching { Class.forName(it, false, param.classLoader) }.getOrNull() }.toSet()

        // Force ad views + containers GONE — redirect setVisibility(VISIBLE) to GONE
        runCatching {
            hook(Class.forName("android.view.View", false, param.classLoader)
                .getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType))
                .intercept { chain ->
                    val view = chain.thisObject as? View
                    if (view != null && chain.args[0] as Int == View.VISIBLE && shouldHide(view)) {
                        chain.proceed(arrayOf(View.GONE))
                    } else chain.proceed()
                }
        }.onFailure { log(Log.WARN, TAG, "hook failed: View.setVisibility", it) }

        // Collapse app's ad containers — block minHeight, onLoad, post-list re-show, constructors
        noop(param.classLoader, "com.dcinside.app.ad.support.a", "j")
        noop(param.classLoader, "com.dcinside.app.post.fragments.B3", "F2")
        listOf("c","d","e","f","g","h","i","j","k","H","l","m","n","o","p","r","s","t","u","w","x").forEach {
            hideContainer(param.classLoader, "com.dcinside.app.ad.support.$it")
            noop(param.classLoader, "com.dcinside.app.ad.support.$it", "f")
        }

        // Obfuscated app config — Naver ad script + post list ad gate + store check
        hookReturning(param.classLoader, "com.dcinside.app.util.Mr", "s0" to null, "C0" to false)
        hookReturning(param.classLoader, "com.dcinside.app.util.ks", "o" to false, "p" to false)

        // Trackers — Firebase Analytics/Crashlytics/Performance + AppMeasurement
        noop(param.classLoader, "com.google.firebase.analytics.FirebaseAnalytics",
            "logEvent", "setCurrentScreen", "setUserProperty", "setUserId",
            "setAnalyticsCollectionEnabled", "resetAnalyticsData", "setDefaultEventParameters",
            "setConsent", "setSessionTimeoutDuration", "setMinimumSessionDuration")
        noop(param.classLoader, "com.google.firebase.crashlytics.j",
            "h", "i", "j", "c", "m", "n", "o", "p", "q", "r", "s", "t")
        noop(param.classLoader, "com.google.firebase.perf.metrics.Trace", "start", "stop", "incrementMetric")
        noop(param.classLoader, "com.google.android.gms.measurement.AppMeasurement",
            "logEvent", "setUserProperty", "setUserId")

        log(Log.INFO, TAG, "ad + tracker blocking hooks installed for ${param.packageName}")
    }

    private fun shouldHide(view: View): Boolean =
        adViewClasses.any { it.isAssignableFrom(view.javaClass) } || view.id in AD_CONTAINER_IDS

    private fun hookReturning(cl: ClassLoader, className: String, vararg pairs: Pair<String, Any?>) {
        runCatching {
            val cls = Class.forName(className, false, cl)
            cls.declaredMethods.forEach { m ->
                pairs.find { it.first == m.name }?.let { (name, ret) ->
                    runCatching { hook(m).intercept { _ -> ret } }
                        .onFailure { log(Log.WARN, TAG, "hook failed: $className.$name", it) }
                }
            }
        }.onFailure { log(Log.DEBUG, TAG, "class not found, skip: $className") }
    }

    private fun noop(cl: ClassLoader, className: String, vararg methodNames: String) {
        runCatching {
            val cls = Class.forName(className, false, cl)
            cls.declaredMethods.forEach { m ->
                if (m.name in methodNames) {
                    runCatching { hook(m).intercept { _ -> null } }
                        .onFailure { log(Log.WARN, TAG, "hook failed: $className.${m.name}", it) }
                }
            }
        }.onFailure { log(Log.DEBUG, TAG, "class not found, skip: $className") }
    }

    private fun hideContainer(cl: ClassLoader, className: String) {
        runCatching {
            Class.forName(className, false, cl).declaredConstructors.forEach { c ->
                runCatching {
                    hook(c).intercept { chain ->
                        chain.proceed()
                        chain.args.firstOrNull { it is View }?.let { (it as View).visibility = View.GONE }
                    }
                }.onFailure { log(Log.WARN, TAG, "hook failed: $className.<init>", it) }
            }
        }.onFailure { log(Log.DEBUG, TAG, "class not found, skip: $className") }
    }

}