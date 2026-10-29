package com.green1052.dcinside

import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// region 식별자
private val authorLine = ThreadLocal<Any?>()

private val showBans = Option("show_identifier_bans", "차단 갤러리 정보 표시", true)
private val showActivity = Option("show_identifier_activity", "글댓비 표시 (작성자별로 조회해 다음 갱신 때 표시)", true)
private val lowActivity = Option("show_identifier_low_activity", "깡계 기준 (글+댓글 수가 이 값 이하면 빨간색, 0이면 끔)", "100")

internal val showIdentifier = Patch(
    key = "show_identifier",
    title = "식별코드 표시",
    summary = "고정닉과 반고정닉의 식별코드(아이디)를 글 목록, 검색 결과, 본문, 댓글의 닉네임 옆에 표시합니다.",
    options = listOf(showBans, showActivity, lowActivity),
) {
    // " (user_id)", 그리고 id가 밴 목록에 있는 갤러리들의 " [gallery, ...]", 마지막으로 " [posts/comments]".
    val bans = showBans.value
    val activity = showActivity.value
    val low = lowActivity.value.trim().toIntOrNull() ?: 0
    if (bans) dbLoader
    styleBadges()
    if (activity) android.app.Activity::class.java.getDeclaredMethod("onResume").after { chain, r ->
        resumedActivity = WeakReference(chain.thisObject as android.app.Activity)
        r
    }
    fun suffix(id: String) = badge(" ($id)", ID_COLOR) +
        (if (bans) bannedIn[id]?.let { badge(" [$it]", BAN_COLOR) } ?: "" else "") +
        (if (activity) activityBadge(id, low) else "")

    // 작성자 행 메서드가 실행 중일 때만 이름 getter에 붙으므로, 닉네임 차단·멘션·히스토리는
    // 순수 닉네임만 봄. 익명 작성자는 user_id가 없음(앱이 IP를 표시).
    fun idOf(getter: Method, model: Any?) = (model?.let { getter.invoke(it) } as? String)?.trim()?.takeIf { it.isNotEmpty() }

    val models = listOf("com.dcinside.app.model.PostInfo", "com.dcinside.app.response.PostItem").map { it.toClass() }
    val nameGetters = models.associateWith { serializedGetters(it, "name") }
    val header = findMethod("identifier.post_header") {
        matcher {
            declaredClass("com.dcinside.app.view.PostReadHeaderView")
            returnType("void")
            paramTypes("com.dcinside.app.model.PostInfo", "boolean", "java.lang.String")
        }
    }
    // 게시글 헤더와 게시글/검색 목록 바인더: 이름을 읽고 헤더의 작성자 span 빌더
    // (TextView를 받는 Spannable 빌더)를 호출하는 모든 곳.
    cachedLines("identifier.author_lines") {
        val span = methodData(header).invokes.first {
            it.returnTypeName == "android.text.Spannable" && it.paramTypeNames.firstOrNull() == "android.widget.TextView"
        }.descriptor
        nameGetters.values.flatten().flatMap { getter ->
            findMethod { matcher { addInvoke(span); addInvoke(methodData(getter).descriptor) } }.map { it.descriptor }
        }.distinct().ifEmpty { error("identifier.author_lines: no match") }
    }.forEach { line ->
        val method = line.toMethod()
        method.scope(authorLine)
        // 이름 getter는 단순 필드 getter; 컴파일된 호출부가 아래 훅을 넘어 인라인함.
        deoptimize(method)
    }

    nameGetters.forEach { (model, getters) ->
        val userId = serializedGetters(model, "user_id").first()
        getters.hookAll { chain ->
            val name = chain.proceed()
            val id = if (authorLine.get() != null && name is String) idOf(userId, chain.thisObject) else null
            // " (id)"로 매칭하므로 단순히 id를 포함한 닉네임(흔함)에도 붙음.
            if (id == null || " ($id)" in name as String) name else name + suffix(id)
        }
    }

    // 댓글: 작성자 행 빌더(이름 + IP/운영자 배지); 뒤에 아이콘이 오면 hair space로 끝남.
    val commentLine = findMethod("identifier.comment_line") {
        matcher {
            returnType("android.text.SpannableStringBuilder")
            paramTypes(null, "java.lang.String", "java.lang.String", "android.content.Context")
            usingStrings(listOf("owner"), StringMatchType.Equals)
        }
    }
    val commentUserId = serializedGetters(commentLine.parameterTypes[0], "user_id").first()
    commentLine.after { chain, line ->
        val id = idOf(commentUserId, chain.getArg(0))
        if (line is SpannableStringBuilder && id != null && " ($id)" !in line) {
            line.insert(if (line.endsWith(' ')) line.length - 1 else line.length, suffix(id))
        }
        line
    }
}

// endregion

// region 배지
// DCRefresher-Reborn의 배지 색상(features/userinfo/meta.ts).
private const val ID_COLOR = 0xFF999999.toInt()
private const val BAN_COLOR = 0xFFE8645F.toInt()
private const val LOW_ACTIVITY_COLOR = 0xFFFF0000.toInt()

/** 나간 모든 배지 텍스트 -> 색상; activity 캐시처럼 상한 둠: 긴 세션은 id가 끝없이 늘어남. */
internal val badgeColors = ConcurrentHashMap<String, Int>()

/** [text]를 보이지 않는 bidi 격리 문자(FSI..PDI)로 감싼 것; [styleBadges]가 찾아 표시되는 어디서든 색칠. */
internal fun badge(text: String, color: Int): String {
    if (badgeColors.size > 2000) badgeColors.clear()
    badgeColors[text] = color
    return "⁨$text⁩"
}

private var badgesStyled = false

/**
 * 이름과 IP는 여러 빌더를 거쳐 평범한 String으로 TextView에 도달함(그곳의 span은 사라지므로), 그래서 모든 setText를
 * 배지 마커로 스캔함(없으면 indexOf 한 번).
 */
internal fun styleBadges() {
    if (badgesStyled) return
    badgesStyled = true
    // 댓글의 "(IP)" 뷰(댓글 행, 더보기 목록)는 한 줄에 제약도 없음: 긴 IP 배지가 이웃을 덮고,
    // 더보기에선 빽빽한 체인(이름 포함)을 왼쪽 끝 밖으로 밀어냄. 화면 절반가량으로 상한 두고
    // ellipsize(더보기 것은 설정하지 않음).
    val ipViews = setOf(res("id", "reply_ip"), res("id", "reply_more_user_ip"))
    TextView::class.java.getMethod("setText", CharSequence::class.java, TextView.BufferType::class.java).hook { chain ->
        val text = chain.getArg(0) as? CharSequence ?: return@hook chain.proceed()
        // TextUtils는 Spanned 텍스트를 통째로 스캔; Kotlin의 CharSequence.indexOf는 글자 단위로 가며 할당함.
        var start = TextUtils.indexOf(text, '⁨')
        if (start < 0) return@hook chain.proceed()
        (chain.thisObject as TextView).takeIf { it.id in ipViews }?.run {
            maxWidth = resources.displayMetrics.widthPixels / 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val styled = SpannableString(text)
        while (start >= 0) {
            val end = TextUtils.indexOf(text, '⁩', start)
            if (end < 0) break
            badgeColors[text.substring(start + 1, end)]?.let { color ->
                styled.setSpan(ForegroundColorSpan(color), start + 1, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                styled.setSpan(RelativeSizeSpan(0.85f), start + 1, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            start = TextUtils.indexOf(text, '⁨', end)
        }
        chain.proceedWithArg(0, styled)
    }
}

// endregion

// region 갤로그 activity (DCRefresher-Reborn core/gallog.ts)
private const val GALLOG_API = "https://gall.dcinside.com/api/gallog_user_layer/gallog_content_reple"

/** [counts]는 (게시글, 댓글); 숨겨졌거나 알 수 없는 gallog는 (0, 0), 가져오는 중이거나 실패 후엔 null. */
private class Activity(val counts: IntArray?, val at: Long)

private val activities = ConcurrentHashMap<String, Activity>()
// 한 번에 한 요청이라 긴 목록이 DCInside에 요청을 쏟아지게 하지 않음.
private val activityFetcher by lazy { Executors.newSingleThreadExecutor() }

/**
 * 캐시에서 꺼낸 " [posts/comments]"(합계가 [low] 이하면 빨강). 없거나 오래된 항목(1시간; 실패 후
 * 5분)은 백그라운드에서 가져온 뒤 화면의 목록을 다시 바인딩.
 */
private fun activityBadge(id: String, low: Int): String {
    val now = System.currentTimeMillis()
    val cached = activities[id]
    if (cached == null || now - cached.at > if (cached.counts == null) 300_000 else 3_600_000) {
        if (activities.size > 2000) activities.clear()
        // 먼저 표시해 id가 한 번만 큐에 들어가게 함; 그동안 오래된 값을 표시.
        activities[id] = Activity(cached?.counts, now)
        activityFetcher.execute {
            val counts = fetchActivity(id)
            activities[id] = Activity(counts, System.currentTimeMillis())
            // 디바운스됨: 새 작성자 한 페이지는 가져오기가 끝나면 리바인딩 한 번으로 끝.
            if (counts != null && counts[0] + counts[1] > 0) mainHandler.run {
                removeCallbacks(redrawLists)
                postDelayed(redrawLists, 500)
            }
        }
    }
    val counts = cached?.counts?.takeIf { it[0] + it[1] > 0 } ?: return ""
    val isLow = low > 0 && counts[0] + counts[1] <= low
    val color = if (isLow) LOW_ACTIVITY_COLOR else ID_COLOR
    return badge(" [${counts[0]}/${counts[1]}]", color)
}

/** 포그라운드 activity; [redrawLists]가 이 activity의 목록을 다시 바인딩. */
@Volatile private var resumedActivity: WeakReference<android.app.Activity>? = null

private val recyclerView by lazy { "androidx.recyclerview.widget.RecyclerView".toClass() }
private val getAdapter by lazy { recyclerView.getMethod("getAdapter") }
private val adapterType by lazy { "androidx.recyclerview.widget.RecyclerView\$Adapter".toClass() }
private val getItemCount by lazy { adapterType.getMethod("getItemCount") }
private val notifyItemRangeChanged by lazy {
    adapterType.getMethod("notifyItemRangeChanged", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
}

/** 작성자 행을 다시 만들려고 화면의 모든 목록(게시글 목록, 검색, 댓글)을 리바인딩. post로 실행: 예외는 로깅만. */
private val redrawLists = Runnable {
    runCatching { resumedActivity?.get()?.window?.decorView?.let(::rebindLists) }.onFailure { log("dcinside: list redraw", it) }
}

private fun rebindLists(view: View) {
    if (recyclerView.isInstance(view)) {
        // ViewPager2 페이지 건너뜀: 프래그먼트 페이지를 리바인딩해도 다시 그려지는 게 없음.
        getAdapter.invoke(view)?.takeIf { a ->
            generateSequence<Class<*>>(a.javaClass) { it.superclass }.none { it.name.startsWith("androidx.viewpager2.") }
        }?.let { notifyItemRangeChanged.invoke(it, 0, getItemCount.invoke(it)) }
    }
    if (view is ViewGroup) for (i in 0 until view.childCount) rebindLists(view.getChildAt(i))
}

private fun fetchActivity(id: String): IntArray? = runCatching {
    (URL(GALLOG_API).openConnection() as HttpURLConnection).run {
        connectTimeout = 10_000
        readTimeout = 10_000
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("X-Requested-With", "XMLHttpRequest")
        try {
            outputStream.use { it.write("user_id=${URLEncoder.encode(id, "UTF-8")}".toByteArray()) }
            // "posts,comments,...". 숨겨졌거나 알 수 없는 gallog는 0,0으로 응답: 캐시(1시간 재요청 없음)하되
            // 배지 없이 표시, 빈 계정으로 읽힐 것이기 때문.
            val n = inputStream.bufferedReader().readText().split(',').take(2).map { it.trim().toInt() }
            intArrayOf(n[0], n[1])
        } finally {
            disconnect()
        }
    }
}.onFailure { log("dcinside: gallog activity of $id", it) }.getOrNull()

// endregion
