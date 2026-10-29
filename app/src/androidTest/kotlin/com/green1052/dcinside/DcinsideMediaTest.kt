package com.green1052.dcinside

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

@RunWith(AndroidJUnit4::class)
class DcinsideMediaTest {
    private val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    private val out = File(dir, "upload_test_out")
    // 사진 같은 노이즈 패턴: 빈 비트맵은 PNG가 WebP보다 이미 작음.
    private fun bitmap() = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply {
        val r = java.util.Random(1)
        for (x in 0 until width) for (y in 0 until height) setPixel(x, y, 0xFF000000.toInt() or r.nextInt(0xFFFFFF))
    }
    private fun encode(format: Bitmap.CompressFormat) = ByteArrayOutputStream().also { bitmap().compress(format, 90, it) }.toByteArray()
    private fun ByteArray.has(s: String) = String(this, Charsets.ISO_8859_1).contains(s)

    @Test
    fun jpegDropsExifButKeepsOrientation() {
        val src = File(dir, "exif_test.jpg").apply { writeBytes(encode(Bitmap.CompressFormat.JPEG)) }
        ExifInterface(src.path).run {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "37/1,33/1,0/1")
            setAttribute(ExifInterface.TAG_MAKE, "TestCam")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val clean = cleanUpload(src, out)!!
        val exif = ExifInterface(clean.path)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals(64, BitmapFactory.decodeFile(clean.path).width)
    }

    @Test
    fun jpegDropsMpfAndTrailingData() {
        val jpeg = encode(Bitmap.CompressFormat.JPEG)
        // SOI 뒤에 APP2 "MPF"와 APP11(JUMBF / C2PA, 유지 목록에 없음), EOI 뒤에 두 번째 이미지 / Motion Photo 영상.
        fun segment(marker: Int, tag: String) =
            byteArrayOf(0xFF.toByte(), marker.toByte(), 0, 10) + tag.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 0, 0, 0)
        val tagged = jpeg.copyOfRange(0, 2) + segment(0xE2, "MPF\u0000") + segment(0xEB, "C2PA") + jpeg.copyOfRange(2, jpeg.size) +
            "ÿØGPS-secret-video".toByteArray(Charsets.ISO_8859_1)
        val clean = stripMetadata(tagged)!!
        assertFalse(clean.has("MPF") || clean.has("C2PA") || clean.has("secret"))
        assertEquals(jpeg.size, clean.size)
        assertEquals(64, BitmapFactory.decodeByteArray(clean, 0, clean.size).width)
    }

    @Test
    fun stillImagesBecomeUprightWebp() {
        val jpeg = File(dir, "webp_test.jpg").apply { writeBytes(encode(Bitmap.CompressFormat.JPEG)) }
        ExifInterface(jpeg.path).run {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_MAKE, "TestCam")
            saveAttributes()
        }
        val webp = cleanUpload(jpeg, out, 80)!!
        assertEquals("webp", webp.extension)
        assertTrue(webp.length() < jpeg.length())
        assertFalse(webp.readBytes().has("TestCam"))
        // 바로 세워 그림: 64x32 사진을 90도 회전.
        BitmapFactory.decodeFile(webp.path).run { assertEquals(32, width); assertEquals(64, height) }

        val png = File(dir, "webp_test.png").apply { writeBytes(encode(Bitmap.CompressFormat.PNG)) }
        assertEquals("webp", cleanUpload(png, out, 80)!!.extension)
        // 끄면: strip된 JPEG는 JPEG로 남음.
        assertEquals("jpg", cleanUpload(jpeg, out)!!.extension)
    }

    @Test
    fun pngDropsTextChunks() {
        val png = encode(Bitmap.CompressFormat.PNG)
        // IHDR 뒤에 tEXt 청크(8바이트 시그니처 + 25바이트 IHDR 청크).
        val data = "Location\u0000Seoul".toByteArray(Charsets.ISO_8859_1)
        val chunk = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, data.size.toByte()))
            val body = "tEXt".toByteArray() + data
            write(body)
            val crc = CRC32().apply { update(body) }.value.toInt()
            write(byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()))
        }.toByteArray()
        val tagged = png.copyOfRange(0, 33) + chunk + png.copyOfRange(33, png.size)
        assertTrue(tagged.has("Seoul"))
        val clean = stripMetadata(tagged)!!
        assertFalse(clean.has("Seoul"))
        assertEquals(png.size, clean.size)
        assertNotNull(BitmapFactory.decodeByteArray(clean, 0, clean.size))
    }

    @Test
    fun webpDropsExif() {
        assumeTrue(Build.VERSION.SDK_INT >= 30) // WEBP_LOSSLESS(ExifInterface의 WebP 쓰기 포함)은 API 30+
        val src = File(dir, "exif_test.webp").apply { writeBytes(encode(Bitmap.CompressFormat.WEBP_LOSSLESS)) }
        ExifInterface(src.path).run {
            setAttribute(ExifInterface.TAG_MAKE, "TestCam")
            saveAttributes()
        }
        assertTrue(src.readBytes().has("TestCam"))
        val clean = cleanUpload(src, out)!!.readBytes()
        assertFalse(clean.has("TestCam"))
        assertNotNull(BitmapFactory.decodeByteArray(clean, 0, clean.size))
    }

    @Test
    fun gifDropsComments() {
        // 1x1 GIF: 헤더, 2색 테이블 달린 화면 디스크립터, 코멘트, NETSCAPE 루프, 이미지, 트레일러.
        val gif = "GIF89a".toByteArray() + byteArrayOf(1, 0, 1, 0, 0x80.toByte(), 0, 0, 0, 0, 0, -1, -1, -1) +
            byteArrayOf(0x21, 0xFE.toByte(), 6) + "secret".toByteArray() + byteArrayOf(0) +
            byteArrayOf(0x21, 0xFF.toByte(), 11) + "NETSCAPE2.0".toByteArray() + byteArrayOf(3, 1, 0, 0, 0) +
            byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0) + byteArrayOf(0x3B)
        val clean = stripMetadata(gif)!!
        assertFalse(clean.has("secret"))
        assertTrue(clean.has("NETSCAPE2.0"))
        assertNotNull(BitmapFactory.decodeByteArray(clean, 0, clean.size))
    }

    @Test
    fun mp4HidesMetadataBoxes() {
        fun box(type: String, vararg body: ByteArray): ByteArray {
            val content = body.fold(ByteArray(0)) { a, b -> a + b }
            val size = 8 + content.size
            return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) + type.toByteArray() + content
        }
        // 생성·수정 시각: version 0엔 "TIME" 2개(각 32비트), version 1엔 4개(각 64비트).
        val time = "TIME".toByteArray()
        val v0 = byteArrayOf(0, 0, 0, 0) + time + time + ByteArray(4)
        val v1 = byteArrayOf(1, 0, 0, 0) + time + time + time + time + ByteArray(4)
        val mp4 = box("ftyp", "isom".toByteArray(), ByteArray(4)) +
            box(
                "moov", box("mvhd", v0), box("udta", "©xyzSeoul".toByteArray()),
                box("trak", box("tkhd", v0), box("meta", "Phone".toByteArray()), box("mdia", box("mdhd", v1))),
            ) + box("mdat", ByteArray(16))
        val clean = cleanUpload(File(dir, "meta_test.mp4").apply { writeBytes(mp4) }, out)!!.readBytes()
        assertEquals(mp4.size, clean.size)
        assertFalse(clean.has("udta") || clean.has("meta") || clean.has("TIME"))
        assertTrue(clean.has("moov") && clean.has("trak") && clean.has("mvhd") && clean.has("mdhd") && clean.has("mdat"))
        // HEIF는 meta 박스 유지: 이미지를 설명하는 박스라.
        val heic = box("ftyp", "heic".toByteArray(), ByteArray(4)) + box("meta", ByteArray(8))
        assertNull(cleanUpload(File(dir, "meta_test.heic").apply { writeBytes(heic) }, out))
    }

    @Test
    fun postMediaHtmlRewritesGifsAndDownloadBlocks() {
        val html = """<p><img class="gif" src="https://dcimg/p.gif" data-mp4="https://dcimg/v.mp4?a=1&amp;b=2"><img class="pic" src="x.jpg" data-mp4="y.mp4">""" +
            """<video controlslist="nofullscreen nodownload" src="m.mp4"></video>""" +
            """<iframe src="https://app.dcinside.com/movie/player?no=1&is_copy=0"></iframe></p>"""
        assertEquals(
            """<p><video controls playsinline autoplay muted loop poster="https://dcimg/p.gif" src="https://dcimg/v.mp4?a=1&amp;b=2"></video><img class="pic" src="x.jpg" data-mp4="y.mp4">""" +
                """<video controlslist="nofullscreen" src="m.mp4"></video>""" +
                """<iframe src="https://app.dcinside.com/movie/player?no=1&is_copy=1"></iframe></p>""",
            postMediaHtml(html, gif = true, download = true),
        )
        assertEquals(html, postMediaHtml(html, gif = false, download = false))
    }
}
