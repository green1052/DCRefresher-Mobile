package com.green1052.dcinside

import android.view.View
import java.lang.reflect.Method

// region 목록
/** 갤러리 목록 프래그먼트의 (목록 응답, 페이지, 탭) 핸들러; 로드된 페이지를 목록 어댑터에 넘김.
 *  5.3.25: 프래그먼트가 루트 패키지로 난독화되어, 응답 모델(검색과 공유)의 파라미터 없는 List getter를
 *  호출하는 (응답, 페이지, 탭) void 메서드로 탐색. */
private fun galleryListHandler() = findMethod("notice.gallery_handler") {
    matcher {
        returnType("void")
        paramTypes(null, "int", null)
        addInvoke {
            declaredClass(listResponse().name)
            returnType("java.util.List")
            paramCount(0)
        }
    }
}

/** 목록 응답의 게시글 getter([handler]의 첫 인자에서 호출). */
private fun galleryPosts(handler: Method) = serializedGetters(handler.parameterTypes[0], "gall_list").first()

internal val hideOfficialNotices = Patch(
    key = "hide_official_notices",
    title = "운영자 공지 숨기기",
    summary = "갤러리 공지에서 디시인사이드 운영자 공지를 숨깁니다.",
    default = false,
) {
    val postItem = "com.dcinside.app.response.PostItem".toClass()
    val name = serializedGetters(postItem, "name").first()
    fun official(post: Any?) = postItem.isInstance(post) && name.invoke(post) == "운영자"

    // 탭 enum은 상수 이름이 그대로 남음.
    val handler = galleryListHandler()
    val posts = galleryPosts(handler)
    handler.before { chain ->
        if ((chain.getArg(2) as? Enum<*>)?.name == "NOTICE") {
            @Suppress("UNCHECKED_CAST")
            runCatching { (posts.invoke(chain.getArg(0)) as MutableList<Any?>).removeAll { official(it) } }
        }
    }
    // 5.3.25: 미니 갤러리 공지 어댑터(setItems)가 사라져 미니 갤러리는 그대로 둠.
}

internal val hidePageIndicators = Patch(
    key = "hide_page_indicators",
    title = "페이지 구분 표시 숨기기",
    summary = "글 목록과 검색 결과의 'Page N' 구분 표시를 숨깁니다.",
    default = false,
) {
    val id = res("string", "post_section_text")
    // 게시글 목록 어댑터의 페이지 행 바인더와 검색 어댑터의 onBindViewHolder(모든 행 타입)가 매 바인딩마다
    // "Page N" 텍스트(view_post_list_item_page의 유일하게 보이는 뷰)와 가시성을 설정; 이후 id로 숨기면
    // 해당 행만 접힘.
    val text = res("id", "post_list_item_page_text")
    val holder = "androidx.recyclerview.widget.RecyclerView".toClass()
        .getMethod("findContainingViewHolder", View::class.java).returnType
    findMethods("page_indicator.binders") { matcher { usingNumbers(id) } }
        .ifEmpty { error("page_indicator.binders: no match") }
        .hookAll { chain ->
            chain.proceed().also {
                chain.args.firstOrNull { holder.isInstance(it) }?.let { itemView(it).findViewById<View>(text)?.visibility = View.GONE }
            }
        }
}

// endregion
