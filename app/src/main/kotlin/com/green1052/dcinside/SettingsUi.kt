package com.green1052.dcinside

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.graphics.createBitmap
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Miuix (HyperOS) 색상, 시스템 따라 라이트/다크. */
@Composable
internal fun AppTheme(content: @Composable () -> Unit) {
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    MiuixTheme(controller = controller, content = content)
}

internal fun Drawable.toBitmap(): Bitmap {
    val w = intrinsicWidth.takeIf { it > 0 } ?: 96
    val h = intrinsicHeight.takeIf { it > 0 } ?: 96
    return createBitmap(w, h).also { bitmap ->
        setBounds(0, 0, w, h)
        draw(Canvas(bitmap))
    }
}
