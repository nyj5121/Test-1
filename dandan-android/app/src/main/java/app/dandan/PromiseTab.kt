package app.dandan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private fun encodeSig(strokes: List<List<Offset>>, s: IntSize): String {
    if (s.width == 0 || s.height == 0) return ""
    return strokes.filter { it.size > 1 }.joinToString("|") { st ->
        st.joinToString(";") { String.format(Locale.US, "%.3f,%.3f", it.x / s.width, it.y / s.height) }
    }
}

@Composable
fun SignatureView(sig: String, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (sig.isBlank()) return@Canvas
        sig.split("|").forEach { st ->
            val pts = st.split(";").mapNotNull { p ->
                val xy = p.split(",")
                val x = xy.getOrNull(0)?.toFloatOrNull()
                val y = xy.getOrNull(1)?.toFloatOrNull()
                if (x != null && y != null) Offset(x * size.width, y * size.height) else null
            }
            for (i in 1 until pts.size) {
                drawLine(Color.White, pts[i - 1], pts[i], strokeWidth = 4f, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun SignaturePad(onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var strokes by remember { mutableStateOf(listOf<List<Offset>>()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    FullDialog("서명", onCancel = onDismiss, saveLabel = "완료", onSave = { onDone(encodeSig(strokes, size)) }) {
        Text(
            "약속의 마지막은 서명이에요. 손가락으로 서명해 주세요.",
            modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Canvas(
            Modifier.fillMaxWidth().height(280.dp).padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .onSizeChanged { size = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o -> strokes = strokes + listOf(listOf(o)) },
                        onDrag = { change, _ ->
                            change.consume()
                            if (strokes.isNotEmpty()) {
                                strokes = strokes.dropLast(1) + listOf(strokes.last() + change.position)
                            }
                        }
                    )
                }
        ) {
            strokes.forEach { st ->
                for (i in 1 until st.size) {
                    drawLine(Color.White, st[i - 1], st[i], strokeWidth = 4f, cap = StrokeCap.Round)
                }
            }
        }
        TextButton(onClick = { strokes = emptyList() }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("지우기") }
    }
}

@Composable
fun PromiseTab() {
    Store.rev.intValue
    val list = Store.promises()
    var editing by remember { mutableStateOf<Promise?>(null) }
    var creating by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("앱마다 하루에 쓸 시간을 스스로 약속해요", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "약속한 앱을 열면 먼저 한 번 더 생각하게 해요. 다 쓰면 그날은 열 수 없어요.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(list, key = { it.pkg }) { p -> PromiseCard(p) { editing = p } }
        item {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { creating = true }.padding(20.dp),
                contentAlignment = Alignment.Center
            ) { Text("+ 약속 추가", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text("숏폼 차단", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            SwitchLine("유튜브 쇼츠", Store.shortsYt) { Store.shortsYt = it }
            Text(
                "앱 화면 구성이 바뀌면 동작하지 않을 수 있어요.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            InstagramBlock()
        }
    }

    if (creating || editing != null) {
        PromiseEditor(editing, onDismiss = { creating = false; editing = null })
    }
}

@Composable
private fun InstagramBlock() {
    val ctx = LocalContext.current
    Spacer(Modifier.height(20.dp))
    Text("인스타그램", fontSize = 16.sp, fontWeight = FontWeight.Bold)
    SwitchLine("스토리·DM만 쓰기", Store.igMode) { want ->
        if (want && !Perm.accessibility(ctx)) toast(ctx, "접근성 권한을 먼저 켜 주세요") else Store.igMode = want
    }
    Text(
        "홈의 피드를 가리고 릴스·탐색·프로필을 막아요. 스토리와 DM은 그대로 써요. 못 알아보는 화면에서는 아무것도 하지 않아요.",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (Store.igMode) {
        SwitchLine("릴스는 스크롤만 막기", Store.igReels) { Store.igReels = it }
        Text(
            "켜면 릴스 탭을 완전히 막지 않고 열어 주되, 위아래로 넘길 수 없게 화면을 덮어요. 보이는 릴스 한 개만 볼 수 있고 좋아요·댓글도 누를 수 없어요. 다른 탭에 갔다가 돌아오면 다른 릴스가 나올 수 있어요.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("피드 가림막 시작 위치", Modifier.weight(1f), fontSize = 14.sp)
            Pick(
                if (Store.igTop == 0) "자동" else "${Store.igTop}%",
                listOf("자동" to 0, "20%" to 20, "25%" to 25, "30%" to 30, "35%" to 35, "40%" to 40, "45%" to 45)
            ) { Store.igTop = it }
        }
        Text(
            "스토리 줄이 가려지거나 피드가 보이면 이 값을 바꿔 보세요.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(12.dp))
    SwitchLine("화면 진단 기록 (문제 해결용)", Store.igDiag) { Store.igDiag = it }
    if (Store.igDiag) {
        val dump = Store.str("igDump")
        Text(
            if (dump.isBlank()) "인스타를 열어 홈·릴스·DM·스토리를 한 번씩 보고 오세요."
            else "기록 ${dump.length}자 · 화면 ${dump.split("=== ").size - 1}종",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row {
            TextButton(onClick = {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("dandan-ig", dump))
                toast(ctx, "복사했어요")
            }) { Text("복사") }
            TextButton(onClick = { Store.put("igDump", "") }) { Text("지우기") }
        }
        Text(
            "기록에는 화면 요소 이름과 위치만 담기고 메시지나 게시물 글은 담기지 않아요.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SwitchLine(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 15.sp)
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun PromiseCard(p: Promise, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val used = Promises.used(p)
    val left = (p.limit - used).coerceAtLeast(0)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface).clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(p.pkg, 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(Apps.label(ctx, p.pkg), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                if (p.unit == "count") "하루 ${p.limit}번" else "하루 ${Fmt.minutes(p.limit)}",
                fontSize = 20.sp, fontWeight = FontWeight.Bold
            )
            Text(
                (if (p.unit == "count") "${left}번 남음" else "${Fmt.minutes(left)} 남음") + " · 🔥 ${p.streak}",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (p.sig.isNotBlank()) SignatureView(p.sig, Modifier.width(70.dp).height(44.dp))
    }
}

private val TIME_OPTS = listOf(5, 10, 15, 20, 30, 40, 45, 60, 90, 120, 180, 240)
private val COUNT_OPTS = listOf(1, 2, 3, 4, 5, 6, 8, 10, 15, 20)

@Composable
private fun PromiseEditor(init: Promise?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var pkg by remember { mutableStateOf(init?.pkg ?: "") }
    var unit by remember { mutableStateOf(init?.unit ?: "time") }
    var freq by remember { mutableStateOf(init?.freq ?: "daily") }
    var limit by remember { mutableStateOf(init?.limit ?: 60) }
    var sig by remember { mutableStateOf(init?.sig ?: "") }
    var picker by remember { mutableStateOf(false) }
    var pad by remember { mutableStateOf(false) }

    FullDialog(
        "약속 정하기", onCancel = onDismiss,
        onSave = {
            if (pkg.isBlank()) toast(ctx, "앱을 골라 주세요")
            else {
                val p = Promise(
                    pkg, unit, freq, limit, init?.streak ?: 0,
                    init?.lastEval ?: Store.dayKey(), init?.created ?: Store.dayKey(), sig
                )
                Store.savePromises(Store.promises().filter { it.pkg != pkg } + p)
                onDismiss()
            }
        }
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("나는 ", fontSize = 20.sp)
                Text(
                    (if (pkg.isBlank()) "앱 고르기" else Apps.label(ctx, pkg)) + " ▾",
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { picker = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 18.sp
                )
                Text(" 을(를)", fontSize = 20.sp)
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pick(
                    if (unit == "time") "시간으로" else "횟수로",
                    listOf("시간으로" to "time", "횟수로" to "count")
                ) { unit = it; limit = if (it == "time") 60 else 3 }
                Pick(
                    when (freq) { "weekday" -> "평일"; "weekend" -> "주말"; else -> "매일" },
                    listOf("매일" to "daily", "평일" to "weekday", "주말" to "weekend")
                ) { freq = it }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("하루", fontSize = 20.sp)
                if (unit == "time") {
                    Pick(Fmt.minutes(limit), TIME_OPTS.map { Fmt.minutes(it) to it }) { limit = it }
                } else {
                    Pick("${limit}번", COUNT_OPTS.map { "${it}번" to it }) { limit = it }
                }
                Text("만 쓰겠습니다.", fontSize = 20.sp)
            }
            Spacer(Modifier.height(28.dp))
            Text("서명", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).clickable { pad = true },
                contentAlignment = Alignment.Center
            ) {
                if (sig.isBlank()) Text("눌러서 서명하기", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else SignatureView(sig, Modifier.fillMaxWidth().height(100.dp).padding(10.dp))
            }
            if (init != null) {
                Spacer(Modifier.height(24.dp))
                TextButton(onClick = {
                    Store.savePromises(Store.promises().filter { it.pkg != init.pkg })
                    onDismiss()
                }) { Text("이 약속 지우기", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (picker) {
        AppPicker(
            "약속할 앱", if (pkg.isBlank()) emptySet() else setOf(pkg), single = true,
            onDone = { pkg = it.firstOrNull() ?: ""; picker = false }, onDismiss = { picker = false }
        )
    }
    if (pad) SignaturePad(onDone = { sig = it; pad = false }, onDismiss = { pad = false })
}
