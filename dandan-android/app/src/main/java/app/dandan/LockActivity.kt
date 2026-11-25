package app.dandan

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideBars()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        setContent { DandanTheme { LockScreen { finish() } } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    private fun hideBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

@Composable
fun LockScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var n by remember { mutableIntStateOf(0) }
    var emergency by remember { mutableStateOf(false) }
    var friendAsk by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(250); n++ } }

    val phone = remember(n) { Engine.phone(ctx) }
    LaunchedEffect(n) {
        if (phone == null) {
            if (Engine.finalizeIfDone(ctx)) {
                try {
                    ctx.startActivity(
                        Intent(ctx, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            .putExtra("finished", true)
                    )
                } catch (e: Exception) { }
            }
            onClose()
        }
    }

    val bg = remember(Store.bgUri) { loadBg(ctx, Store.bgUri) }
    val ring = Color(Store.ringColor)
    val frac = if (phone == null || phone.totalMs <= 0) 0f
    else (phone.remainMs.toFloat() / phone.totalMs.toFloat()).coerceIn(0f, 1f)
    val now = Date()

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B2040), Color(0xFF0A0C14))))) {
        if (bg != null) {
            Image(bg, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        }
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(Modifier.height(24.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val s = 14.dp.toPx()
                        val inset = s / 2
                        val sz = Size(size.width - s, size.height - s)
                        drawArc(Color.White.copy(alpha = 0.15f), 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(s))
                        drawArc(ring, -90f, 360f * frac, false, Offset(inset, inset), sz, style = Stroke(s, cap = StrokeCap.Round))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (phone?.trial == true) "체험 중" else "남은 시간",
                            color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp
                        )
                        Text(
                            Fmt.hms(phone?.remainMs ?: 0L), color = Color.White,
                            fontSize = 40.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    SimpleDateFormat("M월 d일 EEEE", Locale.KOREA).format(now),
                    color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp
                )
                Text(
                    SimpleDateFormat("a h:mm", Locale.KOREA).format(now),
                    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                QuickApps(ctx)
                if (Friend.scopeOn("phone_unlock") && phone?.trial != true) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "${Friend.name()}에게 해제 요청", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
                        modifier = Modifier.clickable { friendAsk = true }.padding(8.dp)
                    )
                }
                if (Store.emergencyEnabled && phone?.trial != true) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "긴급 해제", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp,
                        modifier = Modifier.clickable { emergency = true }.padding(8.dp)
                    )
                }
            }
        }
    }
    if (emergency) EmergencyDialog(ctx, onDismiss = { emergency = false })
    if (friendAsk) {
        FriendRequestDialog(
            kind = "phone_unlock", label = "폰 잠금", param = "0", allowShare = false,
            onDismiss = { friendAsk = false },
            onApproved = { friendAsk = false; Engine.releaseByFriend(ctx) }
        )
    }
}

private fun loadBg(ctx: Context, uri: String) = if (uri.isBlank()) null else try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > 2000 || bounds.outHeight / sample > 2000) sample *= 2
    val o = BitmapFactory.Options().apply { inSampleSize = sample }
    ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, o) }?.asImageBitmap()
} catch (e: Throwable) { null }

@Composable
private fun QuickApps(ctx: Context) {
    val pkgs = remember {
        val l = ArrayList<String>()
        Apps.dialerPkg(ctx)?.let { l.add(it) }
        Apps.smsPkg(ctx)?.let { if (it !in l) l.add(it) }
        Store.allowed.forEach { if (it !in l) l.add(it) }
        l.toList()
    }
    Column(
        Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        pkgs.chunked(4).forEach { rowPkgs ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                rowPkgs.forEach { p ->
                    Column(
                        Modifier.clickable {
                            GuardService.graceUntil = SystemClock.elapsedRealtime() + 2500L
                            Apps.open(ctx, p)
                        },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AppIcon(p, 52.dp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            Apps.label(ctx, p).take(6), color = Color.White.copy(alpha = 0.8f),
                            fontSize = 11.sp, textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

private const val SENTENCE = "나는 오늘 나와 한 약속을 스스로 깨뜨리고, 그 대가를 모두 받아들이며 잠금을 해제한다"
private const val WAIT_MS = 30 * 60_000L

@Composable
private fun EmergencyDialog(ctx: Context, onDismiss: () -> Unit) {
    var n by remember { mutableIntStateOf(0) }
    var typed by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { delay(500); n++ } }
    val startE = Store.long("emStartE")
    val started = startE > 0
    val left = if (started) maxOf(0L, startE + WAIT_MS - SystemClock.elapsedRealtime()).also { n.hashCode() } else WAIT_MS
    val ready = started && left == 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("긴급 해제") },
        text = {
            Column {
                Text(
                    "해제하면 이런 대가를 치러요.\n" +
                        "· 연속 기록이 0으로 돌아가요\n" +
                        "· 오늘 쌓은 잠금 시간이 지워져요\n" +
                        "· 남은 시간의 2배가 내일 같은 시각에 다시 잠겨요 (수정 불가)",
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(12.dp))
                if (!started) {
                    Text("시작하면 30분을 기다려야 해제할 수 있어요.", fontSize = 14.sp)
                } else if (!ready) {
                    Text("해제까지 ${Fmt.hms(left)}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("아래 문장을 그대로 입력하세요.", fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(SENTENCE, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(typed, { typed = it }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            when {
                !started -> Button(onClick = { Store.put("emStartE", SystemClock.elapsedRealtime()) }) { Text("30분 대기 시작") }
                ready -> Button(
                    enabled = typed.trim() == SENTENCE,
                    onClick = {
                        Store.put("emStartE", 0L)
                        Engine.emergencyRelease(ctx)
                        onDismiss()
                    }
                ) { Text("해제하기") }
                else -> TextButton(onClick = onDismiss) { Text("닫기") }
            }
        },
        dismissButton = {
            if (started) TextButton(onClick = { Store.put("emStartE", 0L); onDismiss() }) { Text("그만둘래요") }
            else TextButton(onClick = onDismiss) { Text("돌아가기") }
        }
    )
}
