package com.green1052.dcinside

import android.content.Context
import android.os.SystemClock
import android.text.Html
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// region 공용 탐색자
/** 검색 결과 어댑터: 계속검색 행과 그 끝 마커를 둘 다 inflate하는 onCreateViewHolder. */
private fun searchAdapter(): Class<*> = Dex.cached("search.adapter") {
    findMethod {
        matcher {
            returnType("androidx.recyclerview.widget.RecyclerView\$ViewHolder")
            paramTypes("android.view.ViewGroup", "int")
            usingNumbers(res("layout", "view_search_more"))
            usingNumbers(res("layout", "view_serpos"))
        }
    }.one("search.adapter").className
}.toClass()

/** PostSearchFragment의 클래스: onCreate(프레임워크가 남긴 이름)가 수행하는 무인수 생성자. */
private fun searchFragment(): Class<*> = Dex.cached("search.fragment") {
    findMethod { matcher { declaredClass("com.dcinside.app.post.PostSearchActivity"); name("onCreate") } }
        .flatMap { it.invokes }
        .filter { it.isConstructor && it.paramCount == 0 && it.className.startsWith("com.dcinside.app.post.") }
        .one("search.fragment").className
}.toClass()

/** 프래그먼트의 (page, refresh) 검색 시작점: 11파라미터 목록 요청을 호출하는 유일한 메서드. */
private fun searchStarter(fragment: Class<*>): Method = Dex.cached("search.starter") {
    findMethod {
        matcher {
            declaredClass(fragment.name)
            returnType("void")
            paramTypes("int", "boolean")
            addInvoke {
                paramCount(11)
                paramTypes(
                    "java.lang.String", "java.lang.String", "int", "com.dcinside.app.type.P", "java.lang.CharSequence",
                    "java.lang.String", "com.dcinside.app.response.PostHead", "com.dcinside.app.type.B", "java.lang.String",
                    "com.dcinside.app.type.E", "java.lang.String",
                )
            }
        }
    }.one("search.starter").descriptor
}.toMethod()

/** 프래그먼트의 static (adapter, galleryId, response) 표시 전 필터(차단 사용자); 호스트의 Unit을 반환.
 *  public synthetic 접근자가 같은 모양이라 private 본문을 매칭. */
private fun searchResponseFilter(fragment: Class<*>, adapter: Class<*>): Method = Dex.cached("search.response_filter") {
    findMethod {
        matcher {
            declaredClass(fragment.name)
            modifiers(Modifier.STATIC or Modifier.PRIVATE or Modifier.FINAL)
            paramCount(3)
            paramTypes(adapter.name, "java.lang.String", null)
        }
    }.one("search.response_filter").descriptor
}.toMethod()

// endregion

// region 계속검색 자동 진행
internal val autoContinueSearch = Patch(
    key = "auto_continue_search",
    title = "검색 결과 자동으로 이어서 검색",
    summary = "검색 결과 끝의 \"계속 검색\" 버튼을 누르지 않아도 목록 끝에 닿으면 다음 결과를 자동으로 불러옵니다.",
) {
    val adapter = searchAdapter()
    // 호스트 자체 클래스: 모듈은 androidx에 의존하지 않음.
    val holder = "androidx.recyclerview.widget.RecyclerView\$ViewHolder".toClass()
    val bind = adapter.declaredMethods.single {
        it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(holder, Int::class.javaPrimitiveType)) && !it.isBridge
    }
    val viewType = adapter.declaredMethods.single {
        it.returnType == Int::class.javaPrimitiveType && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) && !it.isBridge
    }
    bind.after { chain, r ->
        // 계속검색 행(타입 7)은 로딩 행을 겸함; 자체 클릭 경로가 요청 진행 중인 동안엔 누름을 버리고,
        // 앱이 마지막 페이지에서 행을 지우므로 루프가 끝남.
        val type = runCatching { viewType.invoke(chain.thisObject, chain.getArg(1)) }.getOrNull()
        if (type != 7) {
            presses = 0 // 콘텐츠 행 바인딩됨: 페이지 도착, 누름 상한 초기화
            return@after r
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastPress >= 2000 && presses < 30) {
            lastPress = now
            presses++
            itemView(chain.getArg(0)!!).performClick()
        }
        r
    }
}

private var lastPress = 0L
private var presses = 0

// endregion

// region 식별자 검색
private class Identifier(val kind: String, val value: String)

/** id:/ip: 검색이 starter부터 응답 필터까지 실행되는 동안 설정됨; 일반 검색은 null. */
@Volatile private var identifier: Identifier? = null

/** 모듈이 searchAs 뒤에 다시 호출한 starter 실행: 메인 스레드에서만 쓰임. */
private var resuming = false

/** user_id -> 현재 닉네임, gallog에서 가져옴; 세션 동안 유지. */
private val nicknames = ConcurrentHashMap<String, String>()

private val gallogFetcher by lazy { Executors.newSingleThreadExecutor() }

/** 이 패치가 항목을 추가한 검색 타입 스피너와 원래 항목 수. */
@Volatile private var typeSpinner = WeakReference<AdapterView<*>>(null)
@Volatile private var typeCount = 0

/** search_type(_nc) 문자열 배열; 들어오는 어댑터를 내용으로 식별하기 위함. */
private var typeArrays: List<Array<String>>? = null

private fun resolveTypeArrays(): List<Array<String>>? = runCatching {
    listOf("search_type", "search_type_nc").map { app.resources.getStringArray(res("array", it)) }
}.onFailure { log("dcinside: search type arrays") }.getOrNull()

/** 검색창 텍스트: 검색 화면 트리의 유일한 ClearableEditText(레이아웃용으로 남은 클래스명). */
private fun typedText(spinner: AdapterView<*>): String? {
    val cls = "com.dcinside.app.view.ClearableEditText".toClass()
    fun find(v: View): EditText? = if (cls.isInstance(v)) v as? EditText
        else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
    return find(spinner.rootView)?.text?.toString()?.trim()
}

internal val searchByIdentifier = Patch(
    key = "search_by_identifier",
    title = "식별코드·IP 검색",
    summary = "검색 타입 셀렉트 박스의 식별코드·IP 항목을 고르거나 검색창에 id:식별코드, ip:IP를 입력하면 작성자 검색 뒤 그 사용자의 글만 남깁니다. 값은 부분 일치로 찾습니다. 식별코드는 식별코드 표시 기능이나 갤로그 주소에서, IP는 닉네임 옆의 값(보통 앞 두 자리)을 씁니다. ip: 뒤에는 띄어쓰기와 닉네임을 붙일 수 있고, 없으면 ㅇㅇ로 검색합니다. 디시 검색이 닉네임 기준이라 닉네임을 바꾼 옛 글은 찾지 못합니다.",
) {
    val fragment = searchFragment()
    val adapter = searchAdapter()
    val starter = searchStarter(fragment)
    val postItem = "com.dcinside.app.response.PostItem".toClass()
    val userId = serializedGetters(postItem, "user_id").first()
    val ip = serializedGetters(postItem, "ip").first()
    val filter = searchResponseFilter(fragment, adapter)
    val gallList = serializedGetters(filter.parameterTypes[2], "gall_list").first()
    val typeP = "com.dcinside.app.type.P".toClass()
    val nameType = typeP.enumConstants?.first { (it as Enum<*>).name == "NAME" }!!
    val typeField = fragment.declaredFields.first { it.type == typeP }.apply { isAccessible = true }
    val keywordFields = fragment.declaredFields.filter {
        !Modifier.isStatic(it.modifiers) && it.type == String::class.java
    }.onEach { it.isAccessible = true }

    // 검색 타입 셀렉트 박스: 앱은 search_type(_nc) 배열로 ArrayAdapter를 만들어 타입 스피너와
    // 드롭다운을 채운다. 드롭다운 목록은 스피너 어댑터와 별개로 배열에서 직접 만들어지는 경우가
    // 있어(스피너 어댑터를 바꿔도 목록이 안 바뀜), 원천인 배열 생성 시점에 항목을 붙인다.
    // 원래 항목을 넘는 위치는 어떤 P 상수에도 해당하지 않아 앱은 기본 타입을 유지하고,
    // 아래의 starter 훅이 그 선택을 식별자 검색으로 바꾼다.
    fun extended(arr: Array<String>): Array<String> =
        arr + "식별코드" + "IP"
    val arrayCtor = ArrayAdapter::class.java.getDeclaredConstructor(
        Context::class.java, Int::class.javaPrimitiveType, Array<Any>::class.java
    )
    arrayCtor.hook { chain ->
        val arg = chain.getArg(2) as? Array<*>
        val arrays = typeArrays ?: resolveTypeArrays()?.also { typeArrays = it } ?: return@hook chain.proceed()
        val base = arg?.filterIsInstance<String>()?.toTypedArray()?.let { a ->
            arrays.firstOrNull { arr -> a.contentEquals(arr) }
        }
        if (base == null) return@hook chain.proceed()
        chain.proceedWithArg(2, extended(base))
    }

    val setAdapter = "android.widget.Spinner".toClass()
        .getDeclaredMethod("setAdapter", "android.widget.SpinnerAdapter".toClass())
    setAdapter.hook { chain ->
        val spinner = chain.thisObject as? AdapterView<*>
        val arrays = typeArrays ?: resolveTypeArrays()?.also { typeArrays = it } ?: return@hook chain.proceed()
        // 스피너 상태 기록: 어댑터 앞부분이 search_type(_nc)으로 시작하면(확장 여부와 무관).
        val base = (chain.getArg(0) as? ArrayAdapter<*>)?.let { a ->
            arrays.firstOrNull { arr -> a.count >= arr.size && (0 until arr.size).all { a.getItem(it) == arr[it] } }
        }
        if (spinner == null || base == null) return@hook chain.proceed()
        typeSpinner = WeakReference(spinner)
        typeCount = base.size
        val incoming = chain.getArg(0) as? ArrayAdapter<*>
        // fallback: 생성자 훅이 못 미친 버전에서 5항목 그대로 오면 확장 어댑터로 다시 세팅.
        // 재진입한 호출은 앞부분이 같아 위에서 상태만 기록되고 여기로 오지 않는다.
        if (incoming != null && incoming.count == base.size) {
            setAdapter.invoke(
                spinner,
                ArrayAdapter(spinner.context, res("layout", "view_spinner_item"), extended(base))
            )
            null
        } else chain.proceed()
    }

    fun matches(item: Any) = identifier?.let { f ->
        val v = (if (f.kind == "id") userId else ip).invoke(item) as? String
        v != null && f.value.isNotEmpty() && v.contains(f.value, ignoreCase = true)
    } ?: true

    // 모든 응답은 프래그먼트의 차단 사용자 필터를 거침; gall_list에서 일치하지 않는 게시글을 제자리에서
    // 버리면 필터, 렌더러, 계속 검색 카운터가 모두 그 결과를 읽음.
    filter.before { chain ->
        val response = chain.getArg(2) ?: return@before
        if (identifier == null) return@before
        runCatching {
            @Suppress("UNCHECKED_CAST")
            (gallList.invoke(response) as? MutableList<Any?>)?.removeAll { !matches(it ?: return@removeAll true) }
        }.onFailure { log("dcinside: identifier filter", it) }
    }

    // starter는 새 검색에선 (1, true), 페이지에선 (page, false)로 실행됨: 새 실행일 때만
    // 식별자를 해석; 페이지는 고쳐 쓴 키워드, 타입, 필터를 유지.
    starter.hook { chain ->
        val frag = chain.thisObject
        if (frag == null || chain.getArg(0) != 1 || chain.getArg(1) != true) return@hook chain.proceed()
        // 모듈이 다시 호출한 실행: searchAs가 키워드·타입·식별자를 이미 정했으므로 그대로 진행. 다시
        // 해석하면 키워드 필드가 닉네임으로 바뀌어 스피너 기본 위치를 보고 identifier를 풀어버림.
        if (resuming) { resuming = false; return@hook chain.proceed() }

        // 키워드 필드의 id:/ip: 접두사, 또는 이 패치가 타입 스피너에 추가한 식별코드/IP
        // 항목(이때는 입력한 텍스트 자체가 값).
        val prefixField = keywordFields.firstOrNull { f ->
            (f.get(frag) as? String)?.let { it.startsWith("id:") || it.startsWith("ip:") } == true
        }
        val keyword = prefixField?.get(frag) as? String
        val kind: String
        val value: String
        var nick: String? // 필터링 전 검색에 쓸 닉네임; null = gallog에서 해석
        val fields: List<Field>
        if (keyword != null) {
            kind = kindOf(keyword)
            value = valueOf(keyword)
            if (value.isEmpty()) {
                identifier = null
                return@hook chain.proceed()
            }
            nick = if (kind == "ip") keyword.split(' ', limit = 2).getOrNull(1)?.takeIf(String::isNotEmpty) else null
            fields = listOf(prefixField)
        } else {
            val spinner = typeSpinner.get()
            val pos = spinner?.selectedItemPosition ?: -1
            kind = when (pos) {
                typeCount -> "id"
                typeCount + 1 -> "ip"
                else -> ""
            }
            val text = if (kind.isEmpty() || spinner == null) null else typedText(spinner)
            if (text.isNullOrEmpty()) {
                identifier = null
                return@hook chain.proceed()
            }
            value = text
            nick = null
            // 키워드 필드는 입력한 텍스트를 가짐; 값이 같은 다른 String 필드도 같은 텍스트
            // (갤러리 id와 이름은 식별자처럼 보이지 않음).
            fields = keywordFields.filter { it.get(frag) == text }
        }

        // 서버 닉네임 검색은 부분문자열 매칭이지만 해시나 IP는 아예 못 찾는다. 닉네임을
        // 알면 닉네임으로 검색 후 식별자로 걸러내고, 모르면 빈 키워드로 전체를 가져와
        // 클라이언트에서 걸러낸다(스캔). 빈 serVal은 전체 목록을 반환함.
        fun searchAs(nickToSearch: String?) {
            fields.forEach { it.set(frag, nickToSearch ?: "") }
            typeField.set(frag, nameType)
            identifier = Identifier(kind, value)
        }

        if (nick != null || kind == "ip") {
            // ip:IP 닉네임 → 닉네임 검색; ip/IP 단독 → 바로 스캔
            searchAs(nick)
            chain.proceed()
        } else {
            val cached = nicknames[value]
            if (cached != null) {
                searchAs(cached)
                chain.proceed()
            } else {
                // gallog에서 현재 닉네임을 해석해 정확한 검색을 시도. 실패하면(부분 ID,
                // 숨긴 gallog) 빈 닉네임으로 폴백 = 스캔. 그 사이 화면은 자체 로딩 상태 표시.
                gallogFetcher.execute {
                    val fetched = gallogNickname(value)
                    mainHandler.post {
                        if (nicknames.size > 2000) nicknames.clear()
                        nicknames[value] = fetched ?: "" // 실패도 기억해 재시도 안 함
                        searchAs(fetched)
                        resuming = true
                        starter.invoke(frag, 1, true)
                    }
                }
                null // 이번 실행 건너뜀; 해석된 닉네임으로 다시 호출
            }
        }
    }
}

private fun kindOf(keyword: String) = keyword.substringBefore(':').lowercase()

private fun valueOf(keyword: String): String {
    val v = keyword.substringAfter(':').trim()
    // ip:1.2.3.4 닉네임 — 뒤의 닉네임은 검색할 작성자 이름; 필터 값은 IP.
    return if (keyword.startsWith("ip:", true)) v.substringBefore(' ') else v
}

/** 작성자의 gallog 페이지에서 현재 닉네임을 가져옴; 숨겨졌거나 알 수 없는 gallog면 null. */
private fun gallogNickname(id: String): String? = runCatching {
    (URL("https://gallog.dcinside.com/${id.trim('/')}").openConnection() as HttpURLConnection).run {
        connectTimeout = 10_000
        readTimeout = 10_000
        setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
        try {
            inputStream.bufferedReader().readText()
                .let { Regex("<strong class=\"nick_name\">(.*?)</strong>").find(it)?.groupValues?.get(1) }
                ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }
                ?.takeIf(String::isNotEmpty)
        } finally {
            disconnect()
        }
    }
}.onFailure { log("dcinside: gallog nickname of $id", it) }.getOrNull()

// endregion
