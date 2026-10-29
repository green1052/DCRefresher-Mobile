package com.green1052.dcinside

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.window.WindowDialog

/** Miuix (HyperOS) 색상, 시스템 따라 라이트/다크. */
@Composable
internal fun AppTheme(content: @Composable () -> Unit) {
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    MiuixTheme(controller = controller, content = content)
}

/** 동그란 색상 스와치. */
@Composable
internal fun Swatch(color: Int, size: Int = 26) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color(color))
            .border(1.dp, MiuixTheme.colorScheme.dividerLine, CircleShape),
    )
}

/** ARGB 색상용 Miuix HSV + 알파 선택기와 편집 가능한 hex 필드, 결과는 [onPick]이 받음. */
@Composable
internal fun ColorPickerDialog(title: String, initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var color by remember { mutableStateOf(initial) }
    var hex by remember { mutableStateOf(hex(initial)) }
    WindowDialog(show = true, title = title, onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ColorPicker(
                color = Color(color),
                onColorChanged = {
                    color = it.toArgb()
                    hex = hex(color)
                },
            )
            TextField(
                value = hex,
                onValueChange = { text ->
                    hex = text
                    color = parseArgb(text, color)
                },
                label = "#AARRGGBB",
                singleLine = true,
                leadingIcon = { Box(Modifier.padding(start = 12.dp, end = 4.dp)) { Swatch(color, 22) } },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(stringResource(R.string.cancel), onDismiss, Modifier.weight(1f))
                TextButton(
                    stringResource(R.string.ok),
                    onClick = {
                        onPick(color)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

internal fun hex(color: Int) = "#%08X".format(color)

internal fun Drawable.toBitmap(): Bitmap {
    val w = intrinsicWidth.takeIf { it > 0 } ?: 96
    val h = intrinsicHeight.takeIf { it > 0 } ?: 96
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
        setBounds(0, 0, w, h)
        draw(Canvas(bitmap))
    }
}
