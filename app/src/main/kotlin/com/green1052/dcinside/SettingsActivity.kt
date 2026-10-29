package com.green1052.dcinside

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.lang.ref.WeakReference

/**
 * 패치 토글, 프레임워크의 원격 프리퍼런스에 기록. 값이 바뀌면 실행 중인 대상 앱이 스스로 종료됨
 * (MainHook 참고). Miuix(HyperOS 스타일 Compose 컴포넌트)로 그림.
 */
class SettingsActivity : ComponentActivity() {
    private var target by mutableStateOf(targets[0])
    private var query by mutableStateOf("")
    private val expanded = mutableStateListOf<String>()
    /** 서비스나 프리퍼런스 전체가 바뀔 때 증가, 여기서 읽는 모든 값을 다시 읽게 함. */
    private var revision by mutableIntStateOf(0)
    /** 토글마다 증가, 그룹 개수를 다시 세게 함. */
    private var edits by mutableIntStateOf(0)
    private var prefs: SharedPreferences? = null
    private var dialog by mutableStateOf<Dialog?>(null)
    // 아직 기록 안 한 타이핑된 옵션 텍스트: 원격 프리퍼런스 apply()마다 대상 앱을 끝내는 IPC라, 텍스트는
    // 키 입력마다가 아니라 탭 전환·초기화·pause 때 기록.
    private val pendingText = HashMap<String, String>()
    private val icons = HashMap<String, ImageBitmap>()

    private class Dialog(val message: String, val confirm: String? = null, val onConfirm: () -> Unit = {})

    companion object : XposedServiceHelper.OnServiceListener {
        // 헬퍼에 unregister가 없어 프로세스당 한 번만 등록하고, 보이는 액티비티로 전달.
        @Volatile private var service: XposedService? = null
        @Volatile private var current = WeakReference<SettingsActivity>(null)
        private const val STATE_TARGET = "target"
        private const val STATE_QUERY = "query"
        private const val STATE_EXPANDED = "expanded"
        private val OK_COLOR = Color(0xFF30A46C)
        private val ERROR_COLOR = Color(0xFFE5484D)

        init {
            XposedServiceHelper.registerListener(this)
        }

        override fun onServiceBind(service: XposedService) {
            this.service = service
            onVisible { it.refresh() }
        }

        override fun onServiceDied(service: XposedService) {
            // 프레임워크가 여러 개일 수 있어, 다른 프레임워크의 죽음이 바인드된 서비스를 던져버리면 안 됨.
            if (this.service != service) return
            this.service = null
            onVisible { it.refresh() }
        }

        /** 보이는 액티비티가 있으면 UI 스레드에서 [f] 실행: 콜백이 요청한 액티비티보다 오래 살 수 있음. */
        private fun onVisible(f: (SettingsActivity) -> Unit) {
            val a = current.get() ?: return
            a.runOnUiThread { if (!a.isDestroyed) f(a) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { s ->
            targets.firstOrNull { it.key == s.getString(STATE_TARGET) }?.let { target = it }
            query = s.getString(STATE_QUERY).orEmpty()
            s.getStringArrayList(STATE_EXPANDED)?.let(expanded::addAll)
        }
        enableEdgeToEdge()
        setContent { AppTheme { Screen() } }
        current = WeakReference(this)
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TARGET, target.key)
        outState.putString(STATE_QUERY, query)
        outState.putStringArrayList(STATE_EXPANDED, ArrayList(expanded))
    }

    override fun onPause() {
        super.onPause()
        flushText()
    }

    private fun flushText() {
        val p = prefs
        if (p != null && pendingText.isNotEmpty()) p.edit().also { e -> pendingText.forEach { (k, v) -> e.putString(k, v) } }.apply()
        pendingText.clear()
    }

    private fun refresh() {
        flushText()
        // 바인드 콜백과 이 호출 사이에 서비스가 죽을 수 있음.
        prefs = runCatching { service?.getRemotePreferences(target.key) }.getOrNull()
        revision++
    }

    private fun select(t: Target) {
        if (t == target) return
        target = t
        refresh()
    }

    /** 앱 아이콘, 미설치면 null, 실패는 캐시하지 않아 나중에 설치한 앱도 보임. */
    private fun icon(pkg: String): ImageBitmap? {
        icons[pkg]?.let { return it }
        return runCatching { packageManager.getApplicationIcon(pkg).toBitmap().asImageBitmap() }
            .getOrNull()?.also { icons[pkg] = it }
    }

    // region 화면
    @Composable
    private fun Screen() {
        val rev = revision
        val p = prefs
        val scroll = MiuixScrollBehavior()
        Scaffold(
            topBar = {
                TopAppBar(title = getString(R.string.app_name), subtitle = getString(R.string.app_description), scrollBehavior = scroll)
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxHeight().nestedScroll(scroll.nestedScrollConnection),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            ) {
                item { Status(rev) }
                item {
                    TabRow(
                        tabs = targets.map { getString(it.name) },
                        selectedTabIndex = targets.indexOf(target),
                        onTabSelected = { select(targets[it]) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        // 스크롤 탭, 가장 긴 앱 이름(디시인사이드)이 들어갈 만큼 넓게.
                        minWidth = 108.dp,
                        maxWidth = 132.dp,
                    )
                }
                item { AppCard(rev) }
                item { SearchField() }
                if (p == null) {
                    item { Note(getString(R.string.settings_no_service)) }
                } else {
                    // 필드는 입력값(공백 포함)을 유지, 필터는 양 끝을 무시.
                    val q = query.trim()
                    val groups = target.groups.mapNotNull { g ->
                        val patches = g.patches.filter { q.isEmpty() || it.title.contains(q, true) || it.summary.contains(q, true) }
                        if (patches.isEmpty()) null else g to patches
                    }
                    if (groups.isEmpty()) item { Note(getString(R.string.settings_no_match, q)) }
                    items(groups, key = { "${target.key}/${it.first.title}" }) { (group, patches) -> GroupCard(p, group, patches, rev) }
                }
                item { Footer() }
            }
        }
        dialog?.let { d ->
            WindowDialog(show = true, summary = d.message, onDismissRequest = { dialog = null }) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (d.confirm != null) TextButton(getString(R.string.cancel), { dialog = null }, Modifier.weight(1f))
                    TextButton(
                        d.confirm ?: getString(R.string.ok),
                        onClick = {
                            dialog = null
                            d.onConfirm()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }

    /** 서비스 뒤의 프레임워크: 이름, 버전, API 레벨. */
    @Composable
    private fun Status(rev: Int) {
        val info = remember(rev) {
            service?.let { runCatching { "${it.frameworkName} ${it.frameworkVersion}, API ${it.apiVersion}" }.getOrNull() }
        }
        Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            BasicComponent(
                title = getString(if (info != null) R.string.settings_framework_connected else R.string.settings_framework_disconnected),
                summary = info ?: getString(R.string.settings_enable_module),
                startAction = { Dot(if (info != null) OK_COLOR else ERROR_COLOR) },
            )
        }
    }

    /** 선택된 앱: 아이콘, 버전, 스코프(요청 버튼 포함), 실행 여부. */
    @Composable
    private fun AppCard(rev: Int) {
        val s = service ?: return
        val t = target
        val pkg = remember(rev, t) { runCatching { packageManager.getPackageInfo(t.packageName, 0) }.getOrNull() }
        val inScope = remember(rev, t) { runCatching { t.packageName in s.scope }.getOrDefault(false) }
        val running = remember(rev, t) { runCatching { s.runningTargets.any { it.processName == t.packageName } }.getOrDefault(false) }
        Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            BasicComponent(
                title = getString(t.name),
                summary = listOfNotNull(
                    pkg?.versionName?.let { getString(R.string.settings_version, it) } ?: getString(R.string.settings_not_installed),
                    getString(if (inScope) R.string.settings_in_scope else R.string.settings_not_in_scope),
                    getString(R.string.settings_running).takeIf { running },
                ).joinToString(", ") + "\n" + getString(R.string.settings_restart_note),
                startAction = {
                    icon(t.packageName)?.let { Image(it, null, Modifier.padding(end = 14.dp).size(44.dp)) }
                },
                endActions = { Dot(if (inScope) OK_COLOR else ERROR_COLOR) },
            )
            if (!inScope && pkg != null) Button(
                onClick = {
                    // 사용자가 결정해야 응답이 오고, 그때 액티비티는 재생성되었거나 닫혀 있을 수 있음.
                    s.requestScope(listOf(t.packageName), object : XposedService.OnScopeEventListener {
                        override fun onScopeRequestApproved(approved: List<String>) = onVisible { it.refresh() }
                        override fun onScopeRequestFailed(message: String) = onVisible { it.dialog = Dialog(it.getString(R.string.settings_scope_failed, message)) }
                    })
                },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(getString(R.string.settings_add_scope), color = MiuixTheme.colorScheme.onPrimary) }
        }
    }

    @Composable
    private fun SearchField() {
        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            label = getString(R.string.settings_search),
            useLabelAsPlaceholder = true,
            singleLine = true,
            leadingIcon = {
                Icon(MiuixIcons.Search, null, Modifier.padding(start = 14.dp, end = 6.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            },
        )
    }

    /** 그룹당 카드 하나: 헤더(켠 개수, 펼침 화살표) 뒤, 열렸거나 검색 중이면 그 패치들. */
    @Composable
    private fun GroupCard(p: SharedPreferences, group: PatchGroup, patches: List<Patch>, rev: Int) {
        val id = "${target.key}/${group.title}"
        val open = query.isNotBlank() || id in expanded
        val on = run {
            edits // 토글마다 재계산
            group.patches.count { p.getBoolean(it.key, it.default) }
        }
        val arrow by animateFloatAsState(if (open) 90f else 0f, label = "arrow")
        Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            BasicComponent(
                title = group.title,
                summary = getString(R.string.settings_group_count, on, group.patches.size),
                onClick = { if (!expanded.remove(id)) expanded += id },
                onClickLabel = getString(if (open) R.string.settings_collapse else R.string.settings_expand),
                endActions = {
                    Icon(MiuixIcons.ChevronForward, null, Modifier.rotate(arrow), tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
                },
            )
            AnimatedVisibility(open) {
                Column { patches.forEach { PatchRow(p, it, rev) } }
            }
        }
    }

    @Composable
    private fun PatchRow(p: SharedPreferences, patch: Patch, rev: Int) {
        val on = run { edits; p.getBoolean(patch.key, patch.default) }
        SwitchPreference(
            checked = on,
            onCheckedChange = {
                p.edit().putBoolean(patch.key, it).apply()
                edits++
            },
            title = patch.title,
            summary = patch.summary.ifEmpty { null },
        )
        AnimatedVisibility(on && patch.options.isNotEmpty()) {
            // 더 어두운 밴드에 들여쓰기해 패치의 설정으로 읽히게 함.
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(16.dp)).background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .padding(vertical = 4.dp),
            ) {
                for (opt in patch.options) when (val def = opt.default) {
                    is Boolean -> {
                        val checked = run { edits; p.getBoolean(opt.key, def) }
                        SwitchPreference(
                            checked = checked,
                            onCheckedChange = {
                                p.edit().putBoolean(opt.key, it).apply()
                                edits++
                            },
                            title = opt.title,
                        )
                    }
                    is String -> {
                        var text by remember(opt.key, rev) { mutableStateOf(pendingText[opt.key] ?: p.getString(opt.key, def).orEmpty()) }
                        fun set(value: String) {
                            text = value
                            pendingText[opt.key] = value
                        }
                        if (opt.color) {
                            // 색상: 스와치를 누르면 선택기가 열리고 정확한 값도 입력 가능.
                            var picking by remember { mutableStateOf(false) }
                            val color = parseArgb(text, parseArgb(def, 0))
                            BasicComponent(
                                title = opt.title,
                                summary = hex(color),
                                onClick = { picking = true },
                                onClickLabel = getString(R.string.settings_pick_color),
                                endActions = { Swatch(color) },
                            )
                            if (picking) ColorPickerDialog(opt.title, color, onDismiss = { picking = false }) { set(hex(it)) }
                        } else {
                            Text(
                                opt.title,
                                Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.footnote1,
                            )
                            TextField(
                                value = text,
                                onValueChange = ::set,
                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                                singleLine = true,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Footer() {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                getString(R.string.settings_app_info),
                onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${target.packageName}"))) },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                getString(R.string.settings_reset),
                onClick = {
                    val p = prefs ?: return@TextButton
                    dialog = Dialog(getString(R.string.settings_reset_confirm, getString(target.name)), getString(R.string.settings_reset_action)) {
                        pendingText.clear()
                        // clear()가 아니라 키마다 remove: 리스너는 제거된 키를 전달받지만, clear()는 프레임워크에 따라
                        // null 키로 오거나 아예 안 옴.
                        p.edit().also { e -> p.all.keys.forEach(e::remove) }.apply()
                        revision++
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    @Composable
    private fun Note(text: String) {
        Text(
            text,
            Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            style = MiuixTheme.textStyles.body2,
        )
    }

    @Composable
    private fun Dot(color: Color) {
        Box(Modifier.padding(end = 14.dp).size(10.dp).clip(CircleShape).background(color))
    }

    // endregion
}
