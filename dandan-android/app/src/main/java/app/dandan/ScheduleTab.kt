package app.dandan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

private fun upsert(s: Schedule) {
    val all = Store.schedules()
    val idx = all.indexOfFirst { it.id == s.id }
    Store.saveSchedules(if (idx >= 0) all.toMutableList().also { it[idx] = s } else all + s)
}

@Composable
fun ScheduleTab(goPerm: () -> Unit) {
    Store.rev.intValue
    val ctx = LocalContext.current
    var mode by remember { mutableIntStateOf(0) }
    val target = if (mode == 0) "phone" else "app"
    var editing by remember { mutableStateOf<Schedule?>(null) }
    var creating by remember { mutableStateOf(false) }
    val list = Store.schedules().filter { it.target == target }
    val now = System.currentTimeMillis()
    var ask by remember { mutableStateOf<Pair<Schedule, () -> Unit>?>(null) }
    fun blocked(s: Schedule, then: () -> Unit) {
        if (Friend.scopeOn("sched_edit")) ask = Pair(s, then)
        else toast(ctx, "곧 잠기는 예약은 지금 고칠 수 없어요")
    }

    Column(Modifier.fillMaxSize()) {
        Seg(listOf("폰 잠금", "앱 잠금"), mode) { mode = it }
        val active = list.any { Engine.schedWindow(it, now) != null }
        val next = list.mapNotNull { Engine.nextStart(it, now) }.minOrNull()
        Text(
            when {
                active -> "지금 예약 잠금 중이에요"
                next != null && mode == 0 -> "${Fmt.dur(next - now)} 후에 핸드폰이 잠겨요"
                next != null -> "${Fmt.dur(next - now)} 후에 앱이 잠겨요"
                else -> "예약된 잠금이 없어요"
            },
            fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(list, key = { it.id }) { s ->
                ScheduleCard(
                    s,
                    onToggle = { on ->
                        val act = {
                            upsert(
                                s.copy(
                                    enabled = on,
                                    onceStart = if (on && s.days == 0) Engine.nextOccurrence(s.startMin, now) else s.onceStart
                                )
                            )
                        }
                        if (!on && Engine.editLocked(s)) blocked(s, act) else act()
                    },
                    onEdit = {
                        if (Engine.editLocked(s)) blocked(s) { editing = s } else editing = s
                    },
                    onDelete = {
                        val act = { Store.saveSchedules(Store.schedules().filter { it.id != s.id }) }
                        if (Engine.editLocked(s)) blocked(s, act) else act()
                    }
                )
            }
            item {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .clickable { if (!Perm.ready(ctx)) goPerm() else creating = true }
                        .padding(18.dp),
                    contentAlignment = Alignment.Center
                ) { Text("+ 예약 추가", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
            }
            if (list.any { it.locked }) {
                item {
                    Text(
                        "자물쇠 표시가 있는 예약은 긴급 해제의 대가로 자동 생성된 것이라 고치거나 지울 수 없어요.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (creating || editing != null) {
        ScheduleEditor(
            init = editing, target = target,
            onSave = { upsert(it); editing = null; creating = false },
            onDismiss = { editing = null; creating = false }
        )
    }
    ask?.let { pair ->
        val sc = pair.first
        FriendRequestDialog(
            kind = "sched_edit", label = "${Fmt.clock(sc.startMin)}-${Fmt.clock(sc.endMin)}", param = "10",
            onDismiss = { ask = null },
            onApproved = {
                Store.put("schedPass_${sc.id}", System.currentTimeMillis() + 10 * 60_000L)
                ask = null
                pair.second()
            }
        )
    }
}

@Composable
private fun ScheduleCard(s: Schedule, onToggle: (Boolean) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onEdit() }.padding(start = 18.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            if (s.silent) Text("알림 없이 잠금", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
            Text(
                (if (s.locked) "🔒 " else "") + "${Fmt.clock(s.startMin)} - ${Fmt.clock(s.endMin)}",
                fontSize = 24.sp, fontWeight = FontWeight.Bold
            )
            Text(
                Fmt.days(s.days) + if (s.target == "app") " · 앱 ${s.apps.size}개" else "",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = s.enabled, onCheckedChange = onToggle)
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮", fontSize = 20.sp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("편집") }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("삭제") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun ScheduleEditor(init: Schedule?, target: String, onSave: (Schedule) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var sh by remember { mutableIntStateOf((init?.startMin ?: 22 * 60) / 60) }
    var sm by remember { mutableIntStateOf((init?.startMin ?: 22 * 60) % 60) }
    var eh by remember { mutableIntStateOf((init?.endMin ?: 23 * 60) / 60) }
    var em by remember { mutableIntStateOf((init?.endMin ?: 23 * 60) % 60) }
    var days by remember { mutableIntStateOf(init?.days ?: 127) }
    var silent by remember { mutableStateOf(init?.silent ?: false) }
    var apps by remember { mutableStateOf(init?.apps ?: emptySet()) }
    var picker by remember { mutableStateOf(false) }

    FullDialog(
        if (init == null) "예약 추가" else "예약 편집", onCancel = onDismiss,
        onSave = {
            if (target == "app" && apps.isEmpty()) {
                toast(ctx, "잠글 앱을 골라 주세요")
            } else {
                val start = sh * 60 + sm
                val now = System.currentTimeMillis()
                onSave(
                    Schedule(
                        id = init?.id ?: UUID.randomUUID().toString(),
                        startMin = start, endMin = eh * 60 + em, days = days,
                        enabled = init?.enabled ?: true, silent = silent, target = target, apps = apps,
                        onceStart = if (days == 0) Engine.nextOccurrence(start, now) else 0L
                    )
                )
            }
        }
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("시작", fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Wheel(24, sh, { sh = it }, 72.dp); Text(":", fontSize = 24.sp); Wheel(60, sm, { sm = it }, 72.dp)
            }
            Text("종료", fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Wheel(24, eh, { eh = it }, 72.dp); Text(":", fontSize = 24.sp); Wheel(60, em, { em = it }, 72.dp)
            }
            Text(
                "종료 시각이 시작보다 빠르면 다음 날 아침까지로 쳐요.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Text("반복", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("월", "화", "수", "목", "금", "토", "일").forEachIndexed { i, d ->
                    val on = (days shr i) and 1 == 1
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { days = days xor (1 shl i) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            d, fontWeight = FontWeight.Bold,
                            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Row {
                TextButton(onClick = { days = 127 }) { Text("매일") }
                TextButton(onClick = { days = 0 }) { Text("한 번만") }
            }
            Text(Fmt.days(days), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            if (target == "phone") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("알림 없이 잠금", fontWeight = FontWeight.Bold)
                        Text("잠기기 5분 전 알림을 보내지 않아요.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = silent, onCheckedChange = { silent = it })
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("잠글 앱 ${apps.size}개", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    TextButton(onClick = { picker = true }) { Text("고르기") }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
    if (picker) {
        AppPicker("잠글 앱 고르기", apps, onDone = { apps = it; picker = false }, onDismiss = { picker = false })
    }
}
