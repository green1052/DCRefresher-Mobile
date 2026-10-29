package com.green1052.dcinside

import android.app.Application
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.query.enums.StringMatchType
import java.io.File
import java.lang.reflect.Modifier
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

// region DCRefresher-Reborn DB (data 브랜치, 자체 CDN 제공, docs/release.md 참고)
private const val DB_URL = "https://dcrefresher.green1052.com"

// 캐시 복사본을 읽기 전까지 비어 있음; 새 버전을 내려받으면 교체.
/** user_id -> "gallery1, gallery2": 밴 목록(ban.json: gallery -> user_ids)에 그 id가 있는 갤러리들. */
@Volatile internal var bannedIn = emptyMap<String, String>()
/** IP 접두사("185.104") -> 배지(" KT, 부산은행" / " SoftBank Corp. (일본)" / " Tencent (VPN)"). */
@Volatile internal var ipLabel: (String) -> String? = { null }

/** 처음 필요로 하는 패치가 시작함: 캐시된 DB를 읽고, 서버 버전이 바뀌었으면 내려받음. */
internal val dbLoader by lazy {
    thread {
        // 패치는 모든 프로세스에 적용됨; 작성자 행을 그리는 건 UI 프로세스(데이터 디렉터리와 같은 이름)뿐.
        // ArcaLive의 IP 정보도 사용.
        if (Application.getProcessName() != dataDir.name) return@thread
        // 패치 설치 중, 호스트 Application이 생기기 전에 시작; 배지 라벨은 그 str()이 필요함.
        while (appOrNull == null) Thread.sleep(50)
        val dir = File(dataDir, "dcrefresher").apply { mkdirs() }
        fun cached(name: String) = File(dir, name).takeIf { it.exists() }?.readText()
        // 복사본이 없거나 깨졌으면 서버 버전이 캐시와 같아도 다시 내려받음.
        val loaded = runCatching { cached("ip.json")?.let { ip -> cached("ban.json")?.let { ban -> loadDb(ip, ban); true } } == true }
            .onFailure { log("dcinside: cached DB is broken", it) }.getOrDefault(false)
        runCatching {
            fun get(path: String) = (URL("$DB_URL/$path").openConnection() as HttpURLConnection).run {
                connectTimeout = 10_000
                readTimeout = 30_000
                useCaches = false
                try { inputStream.bufferedReader().readText() } finally { disconnect() }
            }
            val version = get("version").trim()
            if (version.isEmpty() || loaded && version == cached("version")?.trim()) return@runCatching
            val ip = get("ip.json")
            val ban = get("ban.json")
            loadDb(ip, ban) // 깨진 파일이면 예외를 던져 기존 것 유지
            // version과 ip.json은 CDN이 따로 캐시함: DCRefresher처럼 ip.json 자체의 버전을 저장.
            val stored = JSONObject(ip).optString("version").ifEmpty { version }
            // 순서대로 진행, rename 실패에서 멈춤: 버전은 두 파일이 모두 저장된 뒤에야 저장.
            mapOf("ip.json" to ip, "ban.json" to ban, "version" to stored).forEach { (name, text) ->
                check(File(dir, "$name.tmp").apply { writeText(text) }.renameTo(File(dir, name))) { "$name not saved" }
            }
        }.onFailure { log("dcinside: DB update failed", it) }
    }
}

private fun loadDb(ip: String, ban: String) {
    val lookup = ipLookup(ip)
    val json = JSONObject(ban)
    val index = HashMap<String, MutableList<String>>()
    json.keys().forEach { gallery ->
        val ids = json.optJSONArray(gallery) ?: return@forEach
        for (i in 0 until ids.length()) index.getOrPut(ids.getString(i)) { ArrayList() } += gallery
    }
    bannedIn = index.mapValues { it.value.joinToString(", ") }
    ipLabel = lookup
}

/**
 * DCRefresher-Reborn의 ip.json(core/ipdb.ts CompactIpData, IP_FORMAT 2) -> lookup, 그 ipInfoOf처럼 라벨링.
 * runs: 65536개 a.b 접두사에 대한 little-endian uint16 [run 시작들..., run 값들...]의 base64. 값 0은 데이터 없음,
 * 아니면 값-1은 meta 인덱스(meta: [org, country, vpn] 트리플) 또는 meta 개수를 넘으면 lists 인덱스.
 */
private fun ipLookup(text: String): (String) -> String? {
    val json = JSONObject(text)
    fun ints(a: JSONArray) = IntArray(a.length()) { a.getInt(it) }
    fun strings(a: JSONArray) = Array(a.length()) { a.getString(it) }
    val orgs = strings(json.getJSONArray("orgs"))
    val countries = strings(json.getJSONArray("countries"))
    val meta = ints(json.getJSONArray("meta"))
    val lists = json.getJSONArray("lists").let { a -> List(a.length()) { ints(a.getJSONArray(it)) } }
    val metaCount = meta.size / 3

    val vpnTag = str(R.string.dcinside_ip_vpn)
    // 테이블 값마다 배지 하나를 한 번만 구성: "org1, org2, org3 외 N (tag)", tag = VPN 또는 첫 후보의 국가
    // (한국은 없음), 그 tag에 따라 색칠.
    fun badge(candidates: IntArray): String {
        val names = candidates.map { orgs[meta[it * 3]] }.filter { it.isNotEmpty() }.distinct()
        val top = names.take(3).joinToString(", ")
        val shown = if (names.size > 3) str(R.string.dcinside_ip_more, top, names.size - 3) else top
        val first = candidates.first()
        val vpn = meta[first * 3 + 2] == 1
        val country = countries[meta[first * 3 + 1]]
        val tag = if (vpn) vpnTag else country
        val label = if (shown.isNotEmpty() && tag.isNotEmpty()) "$shown ($tag)" else shown.ifEmpty { tag }
        val color = when {
            vpn -> 0xFF8E4EC6
            country.isEmpty() -> 0xFF6495ED
            country == "일본" -> 0xFFE5484D
            country == "중국" -> 0xFFF76B15
            else -> 0xFF12A594
        }.toInt()
        return if (label.isEmpty()) "" else badge(" $label", color)
    }
    val labels = Array(metaCount + lists.size) { badge(if (it < metaCount) intArrayOf(it) else lists[it - metaCount]) }

    val runs = ByteBuffer.wrap(Base64.decode(json.getString("runs"), Base64.DEFAULT)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    require(runs.remaining() % 2 == 0) { "ip.json: odd runs" }
    val count = runs.remaining() / 2
    val table = ShortArray(65536)
    for (run in 0 until count) {
        val start = runs[run].toInt() and 0xffff
        val end = if (run + 1 < count) runs[run + 1].toInt() and 0xffff else 65536
        require(start < end && (runs[count + run].toInt() and 0xffff) <= labels.size) { "ip.json: bad run $run" }
        table.fill(runs[count + run], start, end)
    }

    return { ip ->
        val parts = ip.split('.')
        val a = parts.getOrNull(0)?.toIntOrNull() ?: -1
        val b = parts.getOrNull(1)?.toIntOrNull() ?: -1
        val value = if (a in 0..255 && b in 0..255) table[a * 256 + b].toInt() and 0xffff else 0
        if (value == 0) null else labels[value - 1].ifEmpty { null }
    }
}

// endregion

// region IP 정보
internal val showIpInfo = Patch(
    key = "show_ip_info",
    title = R.string.dcinside_show_ip_info_title,
    summary = R.string.dcinside_show_ip_info_summary,
) {
    // 모델의 IP getter가 아님: 5.3.6에선 이들이 사용자 메모 조회와 차단 목록에도 쓰임. 앱이 그리는 모든 "(ip)"
    // (게시글 목록, 검색, 게시글 헤더, 댓글, 더보기, 캡처)는 이름 줄 span 빌더(context, name, nickname, flag, ip,
    // ..., user_id, ...) 안에서 찾은 하나의 헬퍼 trim(ip, ".*")를 거침.
    dbLoader
    styleBadges()
    val nameLine = findMethod("ip.name_line") {
        matcher {
            modifiers(Modifier.STATIC)
            returnType("android.text.Spannable")
            paramTypes(
                "android.content.Context", "java.lang.String", "java.lang.String", "boolean", "java.lang.String",
                null, null, "java.lang.String", "java.lang.Boolean", "boolean",
            )
        }
    }
    val trim = Dex.cached("ip.trim") {
        methodData(nameLine).invokes.filter {
            it.paramTypeNames == listOf("java.lang.String", "java.lang.String") && it.returnTypeName == "java.lang.String"
        }.map { it.descriptor }.distinct().one("ip.trim")
    }
    // 컴파일된 호출부가 훅을 넘어 인라인할 수 있는 작은 static 헬퍼. 호출부가 약 70개라 IP 것(".*" 전달)만
    // 디옵티마이즈.
    cachedLines("ip.trim_callers") {
        findMethod { matcher { addInvoke(trim); usingStrings(listOf(".*"), StringMatchType.Equals) } }.map { it.descriptor }
    }.forEach { deoptimize(it.toExecutable()) }
    trim.toMethod().after { chain, ip ->
        if (chain.getArg(1) != ".*" || ip !is String) ip else ipLabel(ip)?.let { ip + it } ?: ip
    }
}

// endregion
