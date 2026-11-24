package app.dandan

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DumbTab(goPerm: () -> Unit) {
    Store.rev.intValue
    val ctx = LocalContext.current
    var picker by remember { mutableStateOf(false) }
    var policy by remember { mutableStateOf(false) }
    var freeAsk by remember { mutableStateOf(false) }
    var addWin by remember { mutableStateOf(false) }
    var cfgAsk by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var startDlg by remember { mutableStateOf(false) }
    var tickN by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tickN++ } }
    tickN.hashCode()

    val on = Store.dumbOn
    val apps = Store.dumbApps.toList()
    val friendCtl = Dumb.friendControlled()
    val freeLeft = Dumb.freeRemainMs(ctx)
    val wins = Dumb.windows()
    val now = System.currentTimeMillis()
    val canEditWins = !friendCtl && !Dumb.active(ctx)

    val base = when {
        !on -> "꺼져 있어요"
        freeLeft > 0 -> "자유 시간 · ${Fmt.dur(freeLeft)} 남음"
        Dumb.strict(ctx) -> "시계가 바뀌어서 잠시 자유 시간을 쓸 수 없어요"
        else -> {
            val n = Dumb.nextFreeStart(now)
            "바보폰 시간이에요" + if (n != null) " · 다음 자유 시간 " + SimpleDateFormat("E a h:mm", Locale.KOREA).format(Date(n)) else " · 자유 시간 없음"
        }
    }
    val status = if (on && Dumb.periodSet()) base + "\n기간 " + Fmt.span(Dumb.periodRemainMs(ctx)) + " 남음" else base

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("스마트폰을 바보폰으로", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "켜 두면 하루 종일 홈 화면이 시계와 꼭 필요한 앱 이름만 있는 화면이 되고, 고른 앱 말고는 열 수 없어요. 정해 둔 자유 시간에만 평소처럼 쓸 수 있어요.",
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("바보폰 모드", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(status, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = on,
                onCheckedChange = { want ->
                    when {
                        want && !Perm.accessibility(ctx) -> { goPerm() }
                        want -> { startDlg = true }
                        Friend.scopeOn("dumb_release") || friendCtl -> {
                            cfgAsk = Pair<String, () -> Unit>("바보폰 끄기", {
                                Store.dumbOn = false
                                Store.put("dumbEndW", 0L)
                            })
                        }
                        Dumb.periodSet() -> {
                            toast(ctx, "정한 기간이 끝날 때까지 끌 수 없어요 (${Fmt.span(Dumb.periodRemainMs(ctx))} 남음)")
                        }
                        Dumb.active(ctx) -> { toast(ctx, "바보폰 시간에는 끌 수 없어요. 자유 시간에 끄세요") }
                        else -> { Store.dumbOn = false }
                    }
                }
            )
        }

        // ---------- 자유 시간 ----------
        Spacer(Modifier.height(22.dp))
        Text("자유 시간", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("친구가 정하기", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "자유 시간을 친구만 정할 수 있어요. 바보폰을 끄는 것도 친구 승인이 필요해요.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = friendCtl,
                onCheckedChange = { want ->
                    if (want) {
                        if (!Friend.registered()) toast(ctx, "먼저 설정 탭에서 친구를 등록하세요")
                        else Friend.enableScope("dumb_policy")
                    } else {
                        cfgAsk = Pair<String, () -> Unit>("옵션 끄기: 바보폰 자유 시간", { Store.put("fs_dumb_policy", false) })
                    }
                }
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("해제도 친구 승인으로", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "바보폰을 끄려면(정한 기간이 남았어도) 친구 승인이 필요해요.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = Friend.scopeOn("dumb_release"),
                onCheckedChange = { want ->
                    if (want) {
                        if (!Friend.registered()) toast(ctx, "먼저 설정 탭에서 친구를 등록하세요")
                        else Friend.enableScope("dumb_release")
                    } else {
                        cfgAsk = Pair<String, () -> Unit>("옵션 끄기: 바보폰 해제 승인", { Store.put("fs_dumb_release", false) })
                    }
                }
            )
        }
        Spacer(Modifier.height(8.dp))

        if (wins.isEmpty()) {
            Text(
                if (friendCtl) "지금 유효한 자유 시간이 없어요. 친구에게 요청하세요." else "정해 둔 자유 시간이 없어요.",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            wins.forEachIndexed { i, w ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${Fmt.clock(w.s)} - ${Fmt.clock(w.e)}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(Fmt.days(w.days), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (canEditWins) {
                        TextButton(onClick = {
                            Store.put("dumbWins", Dumb.encode(Dumb.parse(Store.str("dumbWins")).filterIndexed { j, _ -> j != i }))
                        }) { Text("삭제") }
                    }
                }
            }
        }

        if (friendCtl) {
            val exp = Store.long("dumbExp")
            Text(
                if (exp > now / 1000) "정책 유효: " + SimpleDateFormat("M월 d일 a h:mm", Locale.KOREA).format(Date(exp * 1000)) + "까지"
                else "정책이 없거나 만료됐어요. 그동안은 하루 종일 바보폰이에요.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { policy = true }) { Text("자유 시간 요청·변경") }
                OutlinedButton(onClick = { freeAsk = true }) { Text("지금 잠깐 요청") }
            }
        } else if (canEditWins && wins.size < 8) {
            TextButton(onClick = { addWin = true }) { Text("+ 자유 시간 추가") }
        } else if (!friendCtl) {
            Text(
                "바보폰 시간에는 자유 시간을 고칠 수 없어요. 자유 시간이나 바보폰이 꺼져 있을 때 고치세요.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ---------- 홈 화면에 둘 앱 ----------
        Spacer(Modifier.height(22.dp))
        Text("홈 화면에 둘 앱", fontWeight = FontWeight.Bold)
        Text(
            "전화와 메시지는 항상 보여요. 설정 앱과 설치 화면은 고르더라도 바보폰 시간엔 열 수 없어요.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        if (apps.isEmpty()) {
            Text("아직 고른 앱이 없어요", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            apps.forEach { Text("· " + Apps.label(ctx, it), fontSize = 16.sp, modifier = Modifier.padding(vertical = 3.dp)) }
        }
        TextButton(onClick = { picker = true }) { Text("앱 고르기") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { ctx.startActivity(Intent(ctx, DumbPhoneActivity::class.java).putExtra("preview", true)) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("미리보기") }
        Spacer(Modifier.height(12.dp))
        Text(
            "바보폰 시간에도 홈 화면 아래의 '단단 설정'으로 이 화면에 들어올 수 있어요.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(30.dp))
    }

    if (picker) {
        AppPicker(
            "홈 화면에 둘 앱", Store.dumbApps,
            onDone = { Store.dumbApps = it; picker = false }, onDismiss = { picker = false }
        )
    }
    if (addWin) {
        FreeWinEditor(
            onSave = { w ->
                Store.put("dumbWins", Dumb.encode(Dumb.parse(Store.str("dumbWins")) + w))
                addWin = false
            },
            onDismiss = { addWin = false }
        )
    }
    if (startDlg) {
        DumbStartDialog(
            onStart = { days ->
                Dumb.startPeriod(ctx, days)
                Store.dumbOn = true
                startDlg = false
            },
            onDismiss = { startDlg = false }
        )
    }
    if (policy) DumbPolicyDialog { policy = false }
    if (freeAsk) FreeAskFlow { freeAsk = false }
    cfgAsk?.let { pair ->
        FriendRequestDialog(
            kind = "cfg_change", label = pair.first, param = "0",
            onDismiss = { cfgAsk = null },
            onApproved = { cfgAsk = null; pair.second() }
        )
    }
}

/** 바보폰을 켤 때 기간을 정하는 창. 기간 없이도 켤 수 있다. */
@Composable
private fun DumbStartDialog(onStart: (Int) -> Unit, onDismiss: () -> Unit) {
    var days by remember { mutableIntStateOf(7) }
    val opts = listOf(0 to "기간 없이", 1 to "1일", 3 to "3일", 7 to "7일", 14 to "14일", 30 to "30일")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("바보폰 기간") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "기간을 정하면 그동안은 끌 수 없고, 끝나면 저절로 꺼져요. 기간 없이 켜면 자유 시간에만 끌 수 있어요.",
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(10.dp))
                opts.forEach { (d, label) ->
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
                            .background(
                                if (days == d) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { days = d }.padding(14.dp)
                    ) { Text(label, fontWeight = FontWeight.Bold) }
                }
                if (Friend.scopeOn("dumb_release") || Dumb.friendControlled()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${Friend.name()}의 승인이 있으면 기간 중에도 끌 수 있어요.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { onStart(days) }) { Text("시작") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

/** "지금 N분만 벗어나게 해 줘" 요청: 시간을 고르고 친구 승인을 받는다. */
@Composable
fun FreeAskFlow(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var minutes by remember { mutableIntStateOf(0) }
    if (minutes == 0) {
        MinutesDialog(
            title = "몇 분 벗어날까요?",
            note = "직접 정해요. 친구가 이 시간 그대로 승인해야 해요.",
            confirmLabel = "친구에게 요청",
            onPick = { minutes = it },
            onDismiss = onDismiss
        )
    } else {
        FriendRequestDialog(
            kind = "dumb_free", label = "바보폰 자유 시간", param = minutes.toString(),
            onDismiss = onDismiss,
            onApproved = { Dumb.startSession(ctx, minutes * 60_000L) }
        )
    }
}

/** 자유 시간 요청·변경: 사유를 적어 링크를 만들고, 친구가 정한 정책 코드를 붙여넣는 창. */
@Composable
private fun DumbPolicyDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var reason by remember { mutableStateOf("") }
    var made by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf("") }
    val msg = if (!made) "" else
        "[단단] ${Friend.myName()}의 요청\n바보폰 자유 시간을 정해 주세요.\n사유: ${reason.trim()}\n\n" +
            "링크를 열어 시간대를 정하고 승인해 주세요 ↓\n${Friend.policyLink(reason)}\n\n(승인하면 나오는 코드를 나한테 보내 줘)"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${Friend.name()}에게 자유 시간 요청") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("1. 사유를 적어 주세요. 친구에게 그대로 보여요.", fontSize = 13.sp)
                OutlinedTextField(
                    value = reason, onValueChange = { reason = it.take(120) },
                    enabled = !made, modifier = Modifier.fillMaxWidth(), maxLines = 3,
                    placeholder = { Text("왜 자유 시간이 필요한가요?") }
                )
                if (!made) {
                    Button(enabled = reason.trim().isNotEmpty(), onClick = {
                        made = true
                        Notify.dispatch(
                            ctx, Friend.myName() + "이(가) 바보폰 자유 시간을 요청했어요", "사유: " + reason.trim(),
                            Friend.policyLink(reason)
                        ) { sent = it }
                    }) { Text("링크 만들기") }
                } else {
                    Spacer(Modifier.height(10.dp))
                    Text("2. 친구에게 요청이 가야 해요. 친구가 시간대와 유효기간을 정해 승인해요.", fontSize = 13.sp)
                    if (sent.isNotBlank()) Text(sent, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                    else if (!Notify.any()) Text("자동 알림이 꺼져 있어요. 아래 버튼으로 직접 보내세요.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copyText(ctx, msg); toast(ctx, "복사했어요") }) { Text("링크 복사") }
                        OutlinedButton(onClick = { kakaoText(ctx, msg) }) { Text("카카오톡") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { shareText(ctx, msg) }) { Text("다른 앱으로") }
                    }
                    TextButton(onClick = { made = false; code = ""; err = "" }) { Text("사유 고쳐서 다시 만들기") }
                    Spacer(Modifier.height(6.dp))
                    Text("3. 친구가 보내 준 코드를 붙여넣으세요.", fontSize = 13.sp)
                    OutlinedTextField(
                        value = code, onValueChange = { code = it; err = "" },
                        modifier = Modifier.fillMaxWidth(), maxLines = 5
                    )
                    TextButton(onClick = { code = readClipboard(ctx) }) { Text("붙여넣기") }
                    if (err.isNotBlank()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            if (made) {
                Button(
                    enabled = code.isNotBlank(),
                    onClick = {
                        val e = Friend.applyPolicy(code)
                        if (e == null) { toast(ctx, "자유 시간을 적용했어요"); onDismiss() } else err = e
                    }
                ) { Text("적용") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}

@Composable
private fun FreeWinEditor(onSave: (FreeWin) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var sh by remember { mutableIntStateOf(20) }
    var sm by remember { mutableIntStateOf(0) }
    var eh by remember { mutableIntStateOf(21) }
    var em by remember { mutableIntStateOf(0) }
    var days by remember { mutableIntStateOf(127) }
    FullDialog(
        "자유 시간 추가", onCancel = onDismiss,
        onSave = {
            if (sh * 60 + sm == eh * 60 + em) toast(ctx, "시작과 종료가 같아요")
            else if (days == 0) toast(ctx, "요일을 골라 주세요")
            else onSave(FreeWin(sh * 60 + sm, eh * 60 + em, days))
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
            Spacer(Modifier.height(12.dp))
            Text("반복", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("월", "화", "수", "목", "금", "토", "일").forEachIndexed { i, d ->
                    val sel = (days shr i) and 1 == 1
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { days = days xor (1 shl i) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            d, fontWeight = FontWeight.Bold,
                            color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Row {
                TextButton(onClick = { days = 127 }) { Text("매일") }
                TextButton(onClick = { days = 31 }) { Text("평일") }
                TextButton(onClick = { days = 96 }) { Text("주말") }
            }
        }
    }
}
