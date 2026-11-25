package app.dandan

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun Section(title: String) {
    Text(
        title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 6.dp)
    )
}

@Composable
private fun Row2(title: String, sub: String, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

/** 잠금이 곧 시작되거나 진행 중이면 보호 설정을 끌 수 없다. */
private val relaxAsk = mutableStateOf(false)

private fun canRelax(ctx: Context): Boolean {
    if (Engine.nearLock(ctx)) {
        if (Friend.scopeOn("relax")) relaxAsk.value = true
        else toast(ctx, "잠금 중이거나 2시간 안에 잠금이 시작돼서 지금은 끌 수 없어요")
        return false
    }
    return true
}

@Composable
fun SettingsTab(goPerm: () -> Unit) {
    Store.rev.intValue
    val ctx = LocalContext.current
    var allowedPicker by remember { mutableStateOf(false) }
    var deco by remember { mutableStateOf(false) }
    var faq by remember { mutableStateOf(false) }
    var friendSetup by remember { mutableStateOf(false) }
    var friendAdd by remember { mutableStateOf(false) }
    val listGate = rememberScopeGate("list_edit")
    var notifyDlg by remember { mutableStateOf(false) }
    var logDlg by remember { mutableStateOf(false) }
    var cfgAsk by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("설정", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(20.dp, 8.dp))

        Section("잠금 화면")
        Row2(
            "허용 앱 설정",
            Store.allowed.let { a ->
                if (a.isEmpty()) "잠금 중에도 쓸 앱 (없음)" else a.joinToString(", ") { Apps.label(ctx, it) }
            },
            onClick = { listGate.run("허용 앱 목록 바꾸기") { allowedPicker = true } }
        ) { Text("›", fontSize = 20.sp) }
        Row2("잠금 화면 꾸미기", "배경 사진과 시계 링 색을 바꿔요", onClick = { deco = true }) { Text("›", fontSize = 20.sp) }
        Row2("긴급 잠금 해제", "켜면 잠금 중 대가를 치르고 풀 수 있어요 (기본 꺼짐)") {
            Switch(
                checked = Store.emergencyEnabled,
                onCheckedChange = { want ->
                    if (want || canRelax(ctx)) Store.emergencyEnabled = want
                }
            )
        }
        if (Store.emergencyEnabled) {
            Text(
                "해제하려면 30분을 기다리고 긴 문장을 입력해야 해요. 해제하면 연속 기록이 0이 되고, 남은 시간의 2배가 내일 같은 시각에 다시 잠겨요.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))

        Section("잠금 강도")
        Row2("예약 잠금 전 수정 방지", "잠기기 2시간 전부터 시각 수정·삭제·끄기 불가") {
            Switch(
                checked = Store.editProtect,
                onCheckedChange = { want -> if (want || canRelax(ctx)) Store.editProtect = want }
            )
        }
        Row2("앱 삭제 방지", "기기 관리자로 등록해 앱을 지울 수 없게 해요") {
            Switch(
                checked = Perm.admin(ctx),
                onCheckedChange = { want ->
                    val comp = ComponentName(ctx, AdminReceiver::class.java)
                    if (want) {
                        try {
                            ctx.startActivity(
                                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
                                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "앱 삭제 방지를 위해 필요해요.")
                            )
                        } catch (e: Exception) { toast(ctx, "열 수 없어요") }
                    } else if (canRelax(ctx)) {
                        (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).removeActiveAdmin(comp)
                        Store.bump()
                    }
                }
            )
        }
        Row2("철벽 모드", "잠금 중 빠져나가는 길을 모두 막아요 (접근성 필요)") {
            Switch(
                checked = Store.ironMode,
                onCheckedChange = { want ->
                    if (want && !Perm.accessibility(ctx)) goPerm()
                    else if (want || canRelax(ctx)) Store.ironMode = want
                }
            )
        }
        if (Store.ironMode) {
            Column(Modifier.padding(start = 36.dp, end = 20.dp)) {
                SubSwitch("최근 앱 진입 방지", Store.ironRecents) { if (it || canRelax(ctx)) Store.ironRecents = it }
                SubSwitch("전원 메뉴 방지", Store.ironPower) { if (it || canRelax(ctx)) Store.ironPower = it }
                SubSwitch("상태바·알림창 차단", Store.ironStatus) { if (it || canRelax(ctx)) Store.ironStatus = it }
                SubSwitch("종료·재부팅 창 차단", Store.ironPopup) { if (it || canRelax(ctx)) Store.ironPopup = it }
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))

        Section("친구 해제")
        Row2(
            "친구",
            if (Friend.registered()) "${Friend.name()} 등록됨 (${Friend.count()}/${Friend.MAX_FRIENDS}) — 누구든 승인할 수 있어요" else "친구를 정하면 해제를 친구가 결정해요. 여러 명도 돼요",
            onClick = { friendSetup = true }
        ) { Text("›", fontSize = 20.sp) }
        if (Friend.registered()) {
            Row2(
                "알림 방법",
                if (Notify.any()) listOfNotNull(if (Notify.smsOn()) "문자" else null, if (Notify.pushOn()) "푸시" else null).joinToString(" · ") + " 켜짐 — 요청하면 친구에게 자동으로 가요"
                else "꺼짐 — 요청을 친구에게 직접 보내야 해요",
                onClick = { notifyDlg = true }
            ) { Text("›", fontSize = 20.sp) }
            Text(
                "아래 옵션을 켜면 그 상황에서 내가 아니라 친구가 풀지 말지 결정해요. 옵션을 끄거나 친구를 끊을 때도 친구 승인이 필요해요.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Friend.SCOPES.forEach { (key, title, desc) ->
                Row2(title, desc) {
                    Switch(
                        checked = Store.bool("fs_$key"),
                        onCheckedChange = { want ->
                            if (want) Friend.enableScope(key)
                            else cfgAsk = Pair<String, () -> Unit>("옵션 끄기: $title", { Store.put("fs_$key", false) })
                        }
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))

        Section("기타")
        Row2("권한 설정", "접근성, 다른 앱 위에 표시, 기기 관리자 등", onClick = goPerm) { Text("›", fontSize = 20.sp) }
        Row2("잠금 차단 기록", "허용 앱이 막힐 때 원인을 찾는 데 써요", onClick = { logDlg = true }) { Text("›", fontSize = 20.sp) }
        Row2("자주 묻는 질문", "잠금이 안 풀릴 때, 권한이 막힐 때", onClick = { faq = true }) { Text("›", fontSize = 20.sp) }
        Spacer(Modifier.height(40.dp))
    }

    if (allowedPicker) {
        AppPicker(
            "허용 앱 고르기", Store.allowed,
            onDone = { Store.allowed = it; allowedPicker = false },
            onDismiss = { allowedPicker = false }
        )
    }
    listGate.Dialog()
    if (deco) DecoDialog { deco = false }
    if (notifyDlg) NotifyDialog { notifyDlg = false }
    if (logDlg) LockLogDialog { logDlg = false }
    if (friendSetup) {
        if (Friend.registered() && !friendAdd) {
            AlertDialog(
                onDismissRequest = { friendSetup = false },
                title = { Text("친구 ${Friend.count()}명") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            "등록된 친구 누구든 승인할 수 있어요. 요청은 모두에게 가고, 한 명이 승인하면 나머지 링크는 닫혀요. " +
                                "친구를 더하거나 끊을 때도 친구 승인이 필요해요. 친구가 열쇠를 잃어버릴 수 있으니 연결 페이지의 '백업'을 해 두게 하세요.",
                            fontSize = 13.sp
                        )
                        Friend.list().forEach { f ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Text(f.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    friendSetup = false
                                    cfgAsk = Pair<String, () -> Unit>("친구 끊기: ${f.name}", { Friend.remove(f.pk) })
                                }) { Text("끊기") }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (Friend.count() >= Friend.MAX_FRIENDS) {
                            toast(ctx, "친구는 ${Friend.MAX_FRIENDS}명까지 등록할 수 있어요")
                        } else {
                            friendSetup = false
                            cfgAsk = Pair<String, () -> Unit>("친구 추가", { friendAdd = true; friendSetup = true })
                        }
                    }) { Text("친구 추가") }
                },
                dismissButton = { TextButton(onClick = { friendSetup = false }) { Text("닫기") } }
            )
        } else {
            FriendSetupDialog { friendSetup = false; friendAdd = false }
        }
    }
    cfgAsk?.let { pair ->
        FriendRequestDialog(
            kind = "cfg_change", label = pair.first, param = "0",
            onDismiss = { cfgAsk = null },
            onApproved = { cfgAsk = null; pair.second() }
        )
    }
    if (relaxAsk.value) {
        FriendRequestDialog(
            kind = "relax", label = "잠금 강도 설정", param = "0", effect = "relax",
            onDismiss = { relaxAsk.value = false },
            onApproved = {
                relaxAsk.value = false
                toast(ctx, "10분 동안 설정을 끌 수 있어요")
            }
        )
    }
    if (faq) {
        AlertDialog(
            onDismissRequest = { faq = false },
            title = { Text("자주 묻는 질문") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Q. 접근성 스위치가 흐리게 막혀 있어요.", fontWeight = FontWeight.Bold)
                    Text("설치 파일로 깐 앱은 안드로이드가 처음엔 막아요. 권한 설정에서 '앱 정보 열기' → 오른쪽 위 ⋮ → '제한된 설정 허용'을 누른 뒤 다시 켜세요.", fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("Q. 잠금이 안 풀려요.", fontWeight = FontWeight.Bold)
                    Text("잠금은 정한 시간이 지나면 저절로 풀려요. 폰을 껐다 켜도 시간은 그대로 흘러요. 처음엔 꼭 10초 체험으로 먼저 시험해 보세요.", fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("Q. 전화가 오면요?", fontWeight = FontWeight.Bold)
                    Text("전화와 문자 앱은 잠금 중에도 쓸 수 있어요. 더 쓰고 싶은 앱은 허용 앱에 추가하세요.", fontSize = 13.sp)
                }
            },
            confirmButton = { TextButton(onClick = { faq = false }) { Text("닫기") } }
        )
    }
}

@Composable
private fun SubSwitch(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun DecoDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { }
            Store.bgUri = uri.toString()
        }
    }
    val colors = listOf(0xFF8C9BFF, 0xFFFFB347, 0xFF6EE7B7, 0xFFF472B6, 0xFFFFFFFF)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("잠금 화면 꾸미기") },
        text = {
            Column {
                OutlinedButton(onClick = { launcher.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("배경 사진 고르기")
                }
                if (Store.bgUri.isNotBlank()) {
                    TextButton(onClick = { Store.bgUri = "" }) { Text("배경 사진 지우기") }
                }
                Spacer(Modifier.height(14.dp))
                Text("시계 링 색", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    colors.forEach { c ->
                        val sel = Store.ringColor == c.toInt()
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).background(Color(c))
                                .clickable { Store.ringColor = c.toInt() },
                            contentAlignment = Alignment.Center
                        ) { if (sel) Text("✓", color = Color.Black, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("완료") } }
    )
}

@Composable
private fun LockLogDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    Store.rev.intValue
    val log = Store.str("lockLog")
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("잠금 차단 기록") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (log.isBlank()) "아직 기록이 없어요. 잠금 중에 앱이 막히면 여기에 남아요.\n\nblock=막음 · skip=앞에 허용 앱이 있어서 봐줌 · grace=앱을 여는 중이라 봐줌 · tick=1초 점검이 막음"
                    else log,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = { TextButton(onClick = { copyText(ctx, log); toast(ctx, "복사했어요") }) { Text("복사") } },
        dismissButton = {
            Row {
                TextButton(onClick = { Store.put("lockLog", "") }) { Text("지우기") }
                TextButton(onClick = onClose) { Text("닫기") }
            }
        }
    )
}
