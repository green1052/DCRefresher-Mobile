package com.green1052.dcinside

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import org.luckypray.dexkit.query.enums.StringMatchType
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.Modifier
import java.util.UUID

// region 게시글 미디어
private val readBody = ThreadLocal<Any?>()

private val gifControls = Option("post_media_gif_controls", "움짤을 재생 막대가 있는 동영상으로 바꾸기", true)
private val allowDownload = Option("post_media_allow_download", "다운로드가 막힌 동영상 저장 허용", true)

private val postMedia = Patch(
    key = "post_media",
    title = "본문 움짤과 동영상 제어",
    summary = "본문 움짤을 멈추고 넘겨 볼 수 있게 바꾸고, 다운로드가 막힌 동영상도 저장할 수 있게 합니다.",
    options = listOf(gifControls, allowDownload),
) {
    // DCAdblocker 방식: 앱이 파싱하기 전에 본문 HTML을 고쳐, 앱의 GIF 플레이어(탭하면 보이는 오버레이
    // 뒤에서 도는 비디오)가 아예 만들어지지 않게 하고 <video>는 일반 비디오 경로를 따르게 함.
    val gif = gifControls.value
    val download = allowDownload.value
    // 본문 뷰 빌더: soup(PostInfo body) -> WebView 콘텐츠 프로세서; "inactive activity" 에러로 앵커링.
    val builder = findMethod("media.read_builder") {
        matcher {
            paramTypes(null, "com.dcinside.app.model.PostInfo")
            usingStrings(listOf("inactive activity"), StringMatchType.Equals)
        }
    }
    // soup 헬퍼는 공용(게시글 편집, 캡처, ...): 빌더가 실행 중일 때만 고침.
    val soup = Dex.cached("media.soup") {
        methodData(builder).invokes.filter {
            it.paramTypeNames == listOf("java.lang.String") && it.returnTypeName.startsWith("org.jsoup.")
        }.map { it.descriptor }.distinct().one("media.soup")
    }.toMethod()
    builder.scope(readBody)
    // 한 줄짜리 static 헬퍼: 컴파일된 코드가 이를 훅을 넘어 빌더에 인라인할 수 있음.
    deoptimize(builder)
    soup.hook { chain ->
        val html = chain.getArg(0) as? String
        if (readBody.get() == null || html == null) chain.proceed() else chain.proceedWithArg(0, postMediaHtml(html, gif, download))
    }
}

private val imgTag = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
private val noDownload = Regex("""(controlslist\s*=\s*"[^"]*?)\s*\bnodownload\b""", RegexOption.IGNORE_CASE)
// 앱의 무비 플레이어 페이지는 is_copy=1이 아니면 다운로드 버튼을 빼 놓음.
private val playerNoCopy = Regex("""(app\.dcinside\.com/movie/player[^"']*?[?&;]is_copy=)0""")

private fun attr(tag: String, name: String) =
    Regex("""\s$name\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)

/** 본문 HTML에서 GIF 이미지(`<img class="gif" data-mp4>`)를 제어 가능한 비디오로 바꾸고 다운로드 차단 해제. */
internal fun postMediaHtml(html: String, gif: Boolean, download: Boolean): String {
    var out = html
    if (gif) out = imgTag.replace(out) { m ->
        val tag = m.value
        val mp4 = attr(tag, "data-mp4")
        if (mp4.isNullOrEmpty() || attr(tag, "class")?.split(' ')?.contains("gif") != true) tag
        else """<video controls playsinline autoplay muted loop poster="${attr(tag, "src").orEmpty()}" src="$mp4"></video>"""
    }
    if (download) out = playerNoCopy.replace(noDownload.replace(out, "$1")) { it.groupValues[1] + "1" }
    return out
}

// endregion

// region 업로드
internal val uploadWebp = Option("clean_uploads_webp", "사진을 WebP로 바꿔 올리기 (더 작아질 때만, 움짤과 WebP 제외)", false)
internal val uploadWebpQuality = Option("clean_uploads_webp_quality", "WebP 품질 (10~100, 낮을수록 작고 흐림)", "80")

/** 이 스레드에서 마지막 요청 본문을 만든 파일의 확장자; 파트의 파일명에 사용. */
private val uploadExtension = ThreadLocal<String?>()

private val cleanUploads = Patch(
    key = "clean_uploads",
    title = "업로드 파일 정리",
    summary = "글과 댓글에 올리는 파일 이름을 무작위로 바꾸고, 이미지, 동영상, 음성 파일에서 위치, 기기, 시각 등 메타데이터를 지웁니다. 사진 방향은 유지합니다.",
    options = listOf(uploadWebp, uploadWebpQuality),
) {
    val webpQuality = webpQuality()
    // 모든 multipart 파일 파트(게시글, 투표, AI 이미지, ...)는 파일명을 URL 인코딩하는
    // (builder, name, filename, body)를 거침. 업로드 콘텐츠의 확장자를 유지(서버가 이를 기준 삼을 수 있음):
    // 호출부가 같은 스레드에서 파트 추가 직전에 본문(아래에서 훅, 변환될 수 있음)을 만듦.
    findMethod("upload.file_part") {
        matcher {
            paramTypes(null, "java.lang.String", "java.lang.String", "okhttp3.RequestBody")
            usingStrings(listOf("UTF-8"), StringMatchType.Equals)
        }
    }.hook { chain ->
        val name = chain.getArg(2) as? String ?: return@hook chain.proceed()
        val ext = uploadExtension.get()?.also { uploadExtension.remove() } ?: name.substringAfterLast('.', "").lowercase()
        chain.proceedWithArg(2, UUID.randomUUID().toString() + if (ext.isEmpty()) "" else ".$ext")
    }

    // 요청 본문 팩토리(파일, 진행 리스너), 미디어 타입마다 하나; 파일은 전송 시점에 읽힘.
    for (type in listOf("image/jpg", "video/mp4", "audio/m4a")) findMethod("upload.body.$type") {
        matcher {
            modifiers(Modifier.STATIC)
            returnType("okhttp3.RequestBody")
            paramTypes("java.io.File", null)
            usingStrings(listOf(type), StringMatchType.Equals)
        }
    }.hook { chain ->
        val file = chain.getArg(0) as? File ?: return@hook chain.proceed()
        val clean = runCatching { cleanUpload(file, File(app.cacheDir, "dcrefresher_uploads"), webpQuality.takeIf { type == "image/jpg" }) }
            .onFailure { log("upload metadata not removed: $file", it) }.getOrNull()
        // 확장자 없는 파일: null, 파트 훅이 이름의 확장자로 폴백.
        uploadExtension.set((clean ?: file).extension.lowercase().ifEmpty { null })
        if (clean == null) chain.proceed() else chain.proceedWithArg(0, clean)
    }
}

/** 이미지는 메모리에서 스트리핑; 더 큰 것은 그대로 업로드. */
private const val MAX_IMAGE_BYTES = 64L shl 20

/** HEIF/AVIF: 메타데이터 박스가 이미지 정보까지 담고 있어 그대로 업로드(변환 안 함). */
private val IMAGE_BRANDS = setOf("heic", "heix", "hevc", "heim", "heis", "mif1", "msf1", "avif", "avis")

/** WebP 옵션의 품질(10-100), 꺼져 있으면 null. */
internal fun webpQuality(): Int? =
    if (uploadWebp.value) (uploadWebpQuality.value.trim().toIntOrNull() ?: 80).coerceIn(10, 100) else null

/**
 * [src]의 메타데이터 없는 복사본을 [dir]에 만들거나, 미처리 형식이나 깨진 파일이면 null(로그 남김). JPEG는
 * 방향 유지; HEIF/AVIF는 그대로 둠. [webpQuality]를 주면 정지 사진은 더 작을 때 WebP가 됨
 * (DCRefresher-Reborn의 write 모듈); 업로드 이름은 결과의 확장자를 따름.
 */
internal fun cleanUpload(src: File, dir: File, webpQuality: Int? = null): File? {
    val clean = stripUpload(src, dir)
    if (webpQuality == null) return clean
    val webp = runCatching { toWebp(clean ?: src, dir, webpQuality) }.onFailure { log("WebP not made: $src", it) }.getOrNull()
    return webp?.also { clean?.delete() } ?: clean
}

@Suppress("DEPRECATION") // API 29에는 WEBP_LOSSY가 없고 품질 100 미만 WEBP는 손실 압축.
private val WEBP_LOSSY = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

private const val PNG_SIGNATURE = "\u0089PNG\r\n\u001a\n"

/**
 * [src]를 [dir]에 WebP로; JPEG나 정지 PNG이고 WebP가 더 작을 때, 아니면 null. 디코딩된 픽셀에서 바로 세워
 * 그리므로 메타데이터 없음. 애니메이션 이미지는 건너뜀: Android는 애니메이션 WebP 인코딩 불가.
 */
private fun toWebp(src: File, dir: File, quality: Int): File? {
    val head = ByteArray(8).also { h -> src.inputStream().use { it.read(h) } }
    val jpeg = head.u8(0) == 0xFF && head.u8(1) == 0xD8
    val png = String(head, Charsets.ISO_8859_1) == PNG_SIGNATURE
    // 비디오(ArcaLive가 모든 업로드를 여기로 보냄)는 읽기 전에 거부.
    if (!jpeg && !png || src.length() > MAX_IMAGE_BYTES) return null
    val data = src.readBytes()
    if (png && isApng(data)) return null
    val decoded = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
    var bitmap = decoded
    val webp = try {
        if (jpeg) bitmap = upright(decoded, ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1))
        ByteArrayOutputStream().also { bitmap.compress(WEBP_LOSSY, quality, it) }.toByteArray()
    } finally {
        bitmap.recycle()
        decoded.recycle()
    }
    // 잘 압축된 JPEG는 WebP로 바꾸면 커질 수 있음; 그땐 스트리핑된 원본을 업로드.
    if (webp.size >= data.size) return null
    dir.mkdirs()
    return File(dir, "${UUID.randomUUID()}.webp").apply { writeBytes(webp) }
}

/** 애니메이션 PNG는 첫 IDAT 앞에 acTL 청크가 있음. */
private fun isApng(png: ByteArray): Boolean {
    var i = 8
    while (i + 8 <= png.size) {
        val type = String(png, i + 4, 4, Charsets.ISO_8859_1)
        if (type == "acTL") return true
        if (type == "IDAT" || type == "IEND") return false
        val len = png.be32(i)
        if (len < 0 || len > png.size - i - 12) return false // 깨진 파일(stripPng도 거부)
        i += 12 + len
    }
    return false
}

/** [bitmap]을 EXIF [orientation](1-8)대로 회전한 것; 이미 바로 서 있으면 [bitmap] 그대로. */
private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
    val m = Matrix()
    when (orientation) {
        2 -> m.setScale(-1f, 1f)
        3 -> m.setRotate(180f)
        4 -> m.apply { setRotate(180f); postScale(-1f, 1f) }
        5 -> m.apply { setRotate(90f); postScale(-1f, 1f) }
        6 -> m.setRotate(90f)
        7 -> m.apply { setRotate(-90f); postScale(-1f, 1f) }
        8 -> m.setRotate(-90f)
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
}

private fun stripUpload(src: File, dir: File): File? {
    val head = ByteArray(12)
    if (src.inputStream().use { it.read(head) } < 12) return null
    val iso = String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp"
    if (iso && String(head, 8, 4, Charsets.ISO_8859_1) in IMAGE_BRANDS) return null.also { log("HEIF/AVIF upload sent as is: $src") }
    val stripped = when {
        iso -> null
        src.length() > MAX_IMAGE_BYTES -> return null.also { log("upload too large to strip, sent as is: $src") }
        else -> stripMetadata(src.readBytes()) ?: return null.also { log("upload format not stripped, sent as is: $src") }
    }

    dir.mkdirs()
    // 업로드는 전송 시점(및 재시도 때)에 파일을 읽으므로, 오래된 복사본은 하루 뒤에야 삭제.
    val now = System.currentTimeMillis()
    dir.listFiles()?.forEach { if (now - it.lastModified() > 86_400_000) it.delete() }
    val ext = src.extension
    val out = File(dir, UUID.randomUUID().toString() + if (ext.isEmpty()) "" else ".$ext")
    if (stripped == null) {
        src.copyTo(out)
        if (!hideIsoMetadata(out)) return null.also { out.delete(); log("media boxes not parsed, sent as is: $src") }
        return out
    }
    out.writeBytes(stripped)
    if (head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()) {
        val orientation = ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        if (orientation != ExifInterface.ORIENTATION_NORMAL) ExifInterface(out.path).run {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
    }
    return out
}

/** [data]에서 JPEG, PNG, WebP, GIF의 메타데이터를 제거한 것; 다른 형식이나 깨진 데이터면 null. */
internal fun stripMetadata(data: ByteArray): ByteArray? = runCatching {
    fun ascii(at: Int, n: Int) = String(data, at, n, Charsets.ISO_8859_1)
    when {
        data.u8(0) == 0xFF && data.u8(1) == 0xD8 -> stripJpeg(data)
        ascii(0, 8) == PNG_SIGNATURE -> stripPng(data)
        ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> stripWebp(data)
        ascii(0, 4) == "GIF8" -> stripGif(data)
        else -> null
    }
}.getOrNull() // 잘린 파일은 끝을 넘어 읽힘: 그대로 업로드

private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xff
private fun ByteArray.be32(i: Int) = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)

/**
 * APP0(JFIF), APP2 ICC_PROFILE, APP14(Adobe 색 변환) APPn 세그먼트만 유지. EXIF, XMP, MPF,
 * IPTC, C2PA/JUMBF, 벤더 세그먼트, 코멘트, EOI 이후의 모든 것(자체 EXIF가 달린 두 번째 이미지: MPF
 * 프리뷰, Ultra HDR 게인 맵; Motion Photo 비디오; 벤더 트레일러)은 버림.
 */
private fun stripJpeg(jpeg: ByteArray): ByteArray? {
    val out = ByteArrayOutputStream(jpeg.size)
    out.write(jpeg, 0, 2)
    var i = 2
    while (i < jpeg.size) {
        if (jpeg.u8(i) != 0xFF) return null
        val marker = jpeg.u8(i + 1)
        if (marker == 0xFF) { // 필 바이트
            i++
            continue
        }
        if (marker == 0xD9) { // 이미지 끝
            out.write(jpeg, i, 2)
            break
        }
        val end = i + 2 + ((jpeg.u8(i + 2) shl 8) or jpeg.u8(i + 3))
        if (end < i + 4 || end > jpeg.size) return null
        fun tag(s: String) = end - i >= 4 + s.length && String(jpeg, i + 4, s.length, Charsets.ISO_8859_1) == s
        val keep = when (marker) {
            0xFE -> false
            0xE0 -> true
            0xE2 -> tag("ICC_PROFILE\u0000")
            0xEE -> tag("Adobe")
            in 0xE1..0xEF -> false
            else -> true
        }
        if (keep) out.write(jpeg, i, end - i)
        i = end
        // SOS: 엔트로피 코딩 데이터는 스태핑(FF00), RSTn, 필이 아닌 다음 마커까지 이어짐; 프로그레시브
        // 이미지는 세그먼트와 스캔이 더 이어짐.
        if (marker == 0xDA) {
            var p = i
            while (p + 1 < jpeg.size) {
                if (jpeg.u8(p) == 0xFF) {
                    val next = jpeg.u8(p + 1)
                    if (next != 0x00 && next != 0xFF && next !in 0xD0..0xD7) break
                    p += if (next == 0xFF) 1 else 2
                } else p++
            }
            if (p + 1 >= jpeg.size) p = jpeg.size // 끝 마커 없음: 데이터를 그대로 둠
            out.write(jpeg, i, p - i)
            i = p
        }
    }
    return out.toByteArray()
}

/** 텍스트, EXIF, 시간 청크; 색상(iCCP, sRGB, gAMA...)과 APNG 청크는 유지. */
private val PNG_METADATA = setOf("tEXt", "zTXt", "iTXt", "eXIf", "tIME")

private fun stripPng(png: ByteArray): ByteArray? {
    val out = ByteArrayOutputStream(png.size)
    out.write(png, 0, 8)
    var i = 8
    while (true) {
        val len = png.be32(i)
        val end = i + 12 + len // length, type, data, CRC
        if (len < 0 || end > png.size) return null
        val type = String(png, i + 4, 4, Charsets.ISO_8859_1)
        if (type !in PNG_METADATA) out.write(png, i, end - i)
        if (type == "IEND") return out.toByteArray()
        i = end
    }
}

/** EXIF와 XMP 청크를 버리고 VP8X 플래그를 지우고 RIFF 크기를 고침. */
private fun stripWebp(webp: ByteArray): ByteArray? {
    fun le32(at: Int) = webp.u8(at) or (webp.u8(at + 1) shl 8) or (webp.u8(at + 2) shl 16) or (webp.u8(at + 3) shl 24)
    val out = ByteArrayOutputStream(webp.size)
    out.write(webp, 0, 12)
    var i = 12
    while (i + 8 <= webp.size) {
        val size = le32(i + 4)
        if (size < 0 || i + 8 + size > webp.size) return null
        val end = minOf(i + 8 + size + (size and 1), webp.size) // 청크는 짝수 크기로 패딩됨
        val type = String(webp, i, 4, Charsets.ISO_8859_1)
        if (type != "EXIF" && type != "XMP ") out.write(webp, i, end - i)
        i = end
    }
    val bytes = out.toByteArray()
    val riff = bytes.size - 8
    for (k in 0..3) bytes[4 + k] = (riff ushr (8 * k)).toByte()
    if (String(bytes, 12, 4, Charsets.ISO_8859_1) == "VP8X") bytes[20] = (bytes.u8(20) and 0x0C.inv()).toByte()
    return bytes
}

/** 코멘트와 애플리케이션 확장을 버림; 애니메이션 루프용(NETSCAPE2.0, ANIMEXTS1.0)은 제외. */
private fun stripGif(gif: ByteArray): ByteArray? {
    fun colorTable(flags: Int) = if (flags and 0x80 != 0) 3 * (1 shl ((flags and 7) + 1)) else 0
    fun blocksEnd(from: Int): Int { // 데이터 서브블록은 길이 0 블록으로 끝남
        var p = from
        while (gif.u8(p) != 0) p += 1 + gif.u8(p)
        return p + 1
    }
    val out = ByteArrayOutputStream(gif.size)
    var i = 13 + colorTable(gif.u8(10))
    out.write(gif, 0, i)
    while (true) {
        when (gif.u8(i)) {
            0x3B -> { // 트레일러
                out.write(0x3B)
                return out.toByteArray()
            }
            0x21 -> {
                val end = blocksEnd(i + 2)
                val keep = when (gif.u8(i + 1)) {
                    0xFE -> false
                    0xFF -> String(gif, i + 3, 11, Charsets.ISO_8859_1).let { it == "NETSCAPE2.0" || it == "ANIMEXTS1.0" }
                    else -> true
                }
                if (keep) out.write(gif, i, end - i)
                i = end
            }
            0x2C -> { // 이미지: 디스크립터, 로컬 색상 테이블, LZW 코드 크기, 데이터
                val end = blocksEnd(i + 10 + colorTable(gif.u8(i + 9)) + 1)
                out.write(gif, i, end - i)
                i = end
            }
            else -> return null
        }
    }
}

/** ISO 미디어 메타데이터(위치, 기기, XMP)를 담은 박스들과 이를 찾아볼 컨테이너들. */
private val ISO_METADATA = setOf("udta", "meta", "uuid")
private val ISO_CONTAINERS = setOf("moov", "trak", "mdia")
/** 생성·수정 시간으로 시작하는 full box: 버전 0은 32비트, 버전 1은 64비트. */
private val ISO_TIMED = setOf("mvhd", "tkhd", "mdhd")

/**
 * 제자리에서: MP4/M4A 메타데이터 박스를 "free"로 이름 바꾸고 mvhd/tkhd/mdhd의 생성·수정 시간을 0으로.
 * 크기와 오프셋은 그대로라 미디어는 손대지 않고 큰 비디오도 통째로 읽지 않음. 깨진 박스 트리면 false.
 */
internal fun hideIsoMetadata(file: File): Boolean = RandomAccessFile(file, "rw").use { raf ->
    fun walk(start: Long, end: Long): Boolean {
        var p = start
        while (p + 8 <= end) {
            raf.seek(p)
            var size = raf.readInt().toLong() and 0xffffffffL
            val type = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.ISO_8859_1)
            var header = 8L
            if (size == 1L) {
                if (end - p < 16) return false
                size = raf.readLong()
                header = 16
            } else if (size == 0L) size = end - p
            // p + size > end가 아닌 이유: 거대한 64비트 크기에서 오버플로 남.
            if (size < header || size > end - p) return false
            if (type in ISO_METADATA) {
                raf.seek(p + 4)
                raf.write("free".toByteArray())
            } else if (type in ISO_TIMED) {
                raf.seek(p + header)
                val times = if (raf.read() == 1) 16 else 8
                if (size < header + 4 + times) return false
                raf.seek(p + header + 4)
                raf.write(ByteArray(times))
            } else if (type in ISO_CONTAINERS && !walk(p + header, p + size)) return false
            p += size
        }
        return true
    }
    walk(0, raf.length())
}

// endregion


internal val mediaPatches = listOf(postMedia, cleanUploads)
