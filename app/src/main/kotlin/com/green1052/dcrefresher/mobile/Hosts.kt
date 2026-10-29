package com.green1052.dcrefresher.mobile

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.UnknownHostException

// region DNS 호스트 필터
// 현재 앱(대상당 프로세스 하나)의 모든 호스트 차단 패치가 공유.
// 교체만 하고 변경은 하지 않음: 나중 패치가 호스트를 추가할 때 다른 스레드에서 읽고 있을 수 있음.
@Volatile private var blockedHosts = emptySet<String>()

private var dnsFilterInstalled = false

/** [host] 또는 상위 도메인이 차단됐는지 판별: 라벨당 set 조회 한 번. */
fun isBlockedHost(host: String?): Boolean {
    var h = host?.lowercase()?.trimEnd('.') ?: return false
    while (h.isNotEmpty()) {
        if (h in blockedHosts) return true
        h = h.substringAfter('.', "")
    }
    return false
}

/** [hosts]와 서브도메인, 사용자의 쉼표 구분 [extra] 목록까지 차단 ("*.foo.com", ".foo.com"도 동작). */
fun blockHosts(hosts: Collection<String>, extra: Option<String>? = null) {
    val user = extra?.value.orEmpty().split(',').map { it.trim().trimStart('*', '.').lowercase() }.filter(String::isNotEmpty)
    blockedHosts = blockedHosts + hosts.map { it.lowercase() } + user
    installDnsFilter()
}

/** 모든 호스트 패치가 쓰는 InetAddress 훅 하나, 재설치는 no-op. */
private fun installDnsFilter() {
    if (dnsFilterInstalled) return
    dnsFilterInstalled = true
    // ponytail: DNS 레벨에서만 차단, Chromium(WebView)과 Cronet은 자체 resolving이라 그 요청은 커버 안 됨.
    listOf(
        InetAddress::class.java.getMethod("getAllByName", String::class.java),
        InetAddress::class.java.getMethod("getByName", String::class.java),
    ).hookAll(ExceptionMode.PASSTHROUGH) { chain ->
        val host = chain.getArg(0) as String?
        if (isBlockedHost(host)) throw UnknownHostException(host)
        chain.proceed()
    }
}

// blockedHosts처럼 교체만 하고 변경은 하지 않음.
@Volatile private var blockedUrlParts = emptySet<String>()

private val webViewFiltered = HashSet<Class<*>>()

/**
 * 차단 호스트로 향하는 WebView 요청에 빈 응답 반환. 프레임워크의 shouldInterceptRequest를 훅하면
 * super를 호출하는 클라이언트까지 커버, super를 호출하지 않는 클라이언트는 [overrides]로 전달.
 */
fun blockWebViewHosts(vararg overrides: Class<*>) {
    for (client in listOf(WebViewClient::class.java) + overrides) {
        if (client in webViewFiltered) continue
        // 훅을 건 뒤에야 기록, 이후 패치가 같은 클라이언트를 넘길 때 override가 사라졌으면 그 패치도 실패하게.
        client.getDeclaredMethod("shouldInterceptRequest", WebView::class.java, WebResourceRequest::class.java).hook { chain ->
            val url = (chain.getArg(1) as WebResourceRequest?)?.url
            if (url != null && (isBlockedHost(url.host) || url.toString().let { u -> blockedUrlParts.any { it in u } }))
                WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            else chain.proceed()
        }
        webViewFiltered += client
    }
}

/** URL에 [parts] 중 하나가 포함된 WebView 요청도 빈 응답: 차단하면 안 되는 호스트의 광고 경로 처리용. */
fun blockWebViewUrls(vararg parts: String) {
    blockedUrlParts = blockedUrlParts + parts
    blockWebViewHosts()
}

// endregion
