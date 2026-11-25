package app.dandan

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay

fun beginLock(ctx: Context, ms: Long, kind: String, trial: Boolean, apps: Set<String> = emptySet()) {
    Engine.startLock(ctx, ms, kind, trial, apps)
    if (kind == "phone") {
        ctx.startActivity(Intent(ctx, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun LockTab(goPerm: () -> Unit) {
    Store.rev.intValue
    var mode by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Seg(listOf("폰 잠금", "앱 잠금"), mode) { mode = it }
        if (mode == 0) PhonePane(goPerm) else AppPane(goPerm)
    }
}

@Composable
private fun PhonePane(goPerm: () -> Unit) {
    val ctx = LocalContext.current
    var h by remember { mutableIntStateOf(0) }
    var m by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    var trial by remember { mutableStateOf(false) }
    val total = (h * 60 + m) * 60_000L

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        Text("지금부터 잠글 시간을 정해 주세요", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Wheel(24, h, { h = it })
            Text("시간", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Wheel(60, m, { m = it })
            Text("분", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { trial = true }) { Text("잠금 체험해 보기 ›") }
        Spacer(Modifier.weight(1f))
        BigButton("바로 잠금", enabled = total > 0) {
            if (!Perm.ready(ctx)) goPerm() else confirm = true
        }
        Spacer(Modifier.height(20.dp))
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("${Fmt.dur(total)} 동안 잠글게요") },
            text = { Text("잠금이 시작되면 중간에 취소하기 어려워요.") },
            confirmButton = {
                Button(onClick = { confirm = false; beginLock(ctx, total, "phone", false) }) { Text("잠금") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("돌아가기") } }
        )
    }
    if (trial) {
        TrialDialog(
            onStart = { ms ->
                trial = false
                if (!Perm.ready(ctx)) goPerm() else beginLock(ctx, ms, "phone", true)
            },
            onDismiss = { trial = false }
        )
    }
}

@Composable
private fun TrialDialog(onStart: (Long) -> Unit, onDismiss: () -> Unit) {
    var sel by remember { mutableStateOf<Long?>(null) }
    val opts = listOf(10_000L to "10초", 20_000L to "20초", 30_000L to "30초", 60_000L to "1분", 120_000L to "2분")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("잠금을 체험할 시간") },
        text = {
            Column {
                Text("짧게 잠가 보면서 어떤 느낌인지 확인해 봐요.", fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                opts.forEach { (ms, label) ->
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
                            .background(
                                if (sel == ms) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { sel = ms }.padding(14.dp)
                    ) { Text(label, fontWeight = FontWeight.Bold) }
                }
            }
        },
        confirmButton = { Button(enabled = sel != null, onClick = { sel?.let(onStart) }) { Text("체험하기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun AppPane(goPerm: () -> Unit) {
    val ctx = LocalContext.current
    var h by remember { mutableIntStateOf(0) }
    var m by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf(false) }
    val listGate = rememberScopeGate("list_edit")
    var confirm by remember { mutableStateOf(false) }
    var n by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); n++ } }
    n.hashCode()

    val remain = Engine.appLockRemain(ctx)
    val apps = Store.appLockApps.toList()
    val total = (h * 60 + m) * 60_000L

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(12.dp))
        if (remain > 0) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)).padding(18.dp)
            ) {
                Column {
                    Text("앱 잠금 중", fontWeight = FontWeight.Bold)
                    Text(Fmt.hms(remain), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Text("지금부터 잠글 앱을 정해 주세요", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            apps.forEach { AppIcon(it, 48.dp) }
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).clickable { listGate.run("잠글 앱 목록 바꾸기") { picker = true } },
                contentAlignment = Alignment.Center
            ) { Text("+", fontSize = 26.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Text("선택한 앱 ${apps.size}개", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Wheel(24, h, { h = it })
            Text("시간", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Wheel(60, m, { m = it })
            Text("분", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        BigButton("바로 잠금", enabled = total > 0 && apps.isNotEmpty() && remain == 0L) {
            if (!Perm.ready(ctx)) goPerm() else confirm = true
        }
        Spacer(Modifier.height(20.dp))
    }

    listGate.Dialog()
    if (picker) {
        AppPicker(
            "잠글 앱 고르기", Store.appLockApps,
            onDone = { Store.appLockApps = it; picker = false },
            onDismiss = { picker = false }
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("앱 ${apps.size}개를 ${Fmt.dur(total)} 동안 잠글게요") },
            text = { Text("잠금이 끝날 때까지 이 앱들은 열 수 없어요.") },
            confirmButton = {
                Button(onClick = {
                    confirm = false
                    beginLock(ctx, total, "app", false, Store.appLockApps)
                    toast(ctx, "앱 잠금을 시작했어요")
                }) { Text("잠금") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("돌아가기") } }
        )
    }
}
