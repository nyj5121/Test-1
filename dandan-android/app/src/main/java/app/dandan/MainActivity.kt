package app.dandan

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val finished = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        finished.value = intent.getBooleanExtra("finished", false)
        Scheduler.reschedule(this)
        setContent {
            DandanTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(finished.value) {
                        finished.value = false
                        Store.put("finishedFlag", false)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("finished", false)) finished.value = true
    }

    override fun onResume() {
        super.onResume()
        Store.bump()
    }
}

@Composable
fun MainScreen(finishedFlag: Boolean, dismissFinished: () -> Unit) {
    Store.rev.intValue
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var showPerm by remember { mutableStateOf(false) }
    val tabs = listOf("🔒" to "잠금", "⏰" to "예약", "🤝" to "약속", "📵" to "바보폰", "⚙️" to "설정")

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Text(t.first, fontSize = 20.sp) },
                        label = { Text(t.second) }
                    )
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Header()
            if (!Perm.ready(ctx)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f))
                        .clickable { showPerm = true }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "잠금이 제대로 동작하려면 권한이 필요해요 ›",
                        color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold, fontSize = 14.sp
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> LockTab { showPerm = true }
                    1 -> ScheduleTab { showPerm = true }
                    2 -> PromiseTab()
                    3 -> DumbTab { showPerm = true }
                    else -> SettingsTab { showPerm = true }
                }
            }
        }
    }

    if (showPerm) PermissionsScreen { showPerm = false }

    if (finishedFlag) {
        val trial = Store.bool("finishedTrial")
        AlertDialog(
            onDismissRequest = dismissFinished,
            title = { Text(if (trial) "체험 잠금이 끝났어요" else "잠금이 끝났어요") },
            text = {
                Text(
                    if (trial) "진짜 잠금을 시작하면 오늘부터 연속 기록이 쌓여요."
                    else "오늘로 ${Store.streak()}일 연속이에요. 수고했어요!"
                )
            },
            confirmButton = { Button(onClick = dismissFinished) { Text("확인") } }
        )
    }
}

@Composable
private fun Header() {
    Store.rev.intValue
    val ctx = LocalContext.current
    var usage by remember { mutableStateOf<Long?>(null) }
    // 사용 통계 조회는 느릴 수 있어서 화면을 막지 않게 따로 읽는다
    LaunchedEffect(Unit) {
        while (true) {
            usage = withContext(Dispatchers.Default) { Apps.usageTodayMs(ctx) }
            delay(15_000)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(SimpleDateFormat("E d", Locale.KOREA).format(Date()), fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                "사용시간 " + (usage?.let { Fmt.dur(it) } ?: "—"),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("🔥 ${Store.streak()}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "잠금시간 " + Fmt.dur(Engine.lockedTodayMs(ctx)),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun PermRow(title: String, why: String, ok: Boolean, btn: String = "허용하기", onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text((if (ok) "✅ " else "⬜ ") + title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(why, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        if (!ok) OutlinedButton(onClick = onClick) { Text(btn) }
    }
}

@Composable
fun PermissionsScreen(onClose: () -> Unit) {
    Store.rev.intValue
    val ctx = LocalContext.current
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Store.bump() }
    val pkgUri = Uri.parse("package:${ctx.packageName}")
    fun open(i: Intent) {
        try { ctx.startActivity(i) } catch (e: Exception) { toast(ctx, "설정 화면을 열 수 없어요") }
    }

    FullDialog("권한 설정", onCancel = onClose, saveLabel = null) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            PermRow(
                "접근성 (필수)",
                "잠금 중 설정·알림창·최근 앱을 막고, 지금 열린 앱을 알아보는 데만 써요.",
                Perm.accessibility(ctx)
            ) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            Text(
                "설치 파일(APK)로 깔았다면 스위치가 흐리게 막혀 있을 수 있어요. 그땐 아래 버튼으로 앱 정보를 열어 오른쪽 위 ⋮ → '제한된 설정 허용'을 누른 뒤 다시 시도하세요.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            TextButton(
                onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)) },
                modifier = Modifier.padding(horizontal = 12.dp)
            ) { Text("앱 정보 열기") }

            PermRow(
                "다른 앱 위에 표시 (필수)", "잠금 화면과 안내 화면을 다른 앱 위에 띄워요.",
                Perm.overlay(ctx)
            ) { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)) }
            PermRow(
                "기기 관리자", "앱 삭제 방지에 써요. 켜 두면 잠금 중에 앱을 지울 수 없어요.",
                Perm.admin(ctx)
            ) {
                open(
                    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(ctx, AdminReceiver::class.java))
                        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "앱 삭제 방지를 위해 필요해요.")
                )
            }
            PermRow(
                "사용 기록 접근", "상단의 오늘 사용시간을 보여주는 데 써요.",
                Perm.usage(ctx)
            ) { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            PermRow(
                "알림", "예약 잠금 5분 전에 알려줘요.",
                Perm.notif(ctx)
            ) { if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
            PermRow(
                "배터리 최적화 제외", "백그라운드에서 잠금 서비스가 꺼지지 않게 해요.",
                Perm.battery(ctx)
            ) { open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)) }
            PermRow(
                "정확한 알람", "예약 잠금이 정확한 시각에 시작되게 해요.",
                Perm.alarm(ctx)
            ) { if (Build.VERSION.SDK_INT >= 31) open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
