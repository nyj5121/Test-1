package app.dandan

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 앱을 열 때 뜨는 안내 화면. mode: intervene(약속) / limit(한도 끝) / blocked(잠긴 앱). */
class GateActivity : ComponentActivity() {
    private val pkgState = mutableStateOf("")
    private val modeState = mutableStateOf("intervene")
    private val version = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        read(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goHome() }
        })
        setContent {
            DandanTheme {
                key(version.intValue) {
                    Gate(pkgState.value, modeState.value, onHome = { goHome() }, onDone = { finish() })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        read(intent)
    }

    private fun read(i: Intent) {
        pkgState.value = i.getStringExtra("pkg") ?: ""
        modeState.value = i.getStringExtra("mode") ?: "intervene"
        version.intValue = version.intValue + 1
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

@Composable
fun Gate(pkg: String, mode: String, onHome: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val label = remember(pkg) { Apps.label(ctx, pkg) }
    val pr = remember(pkg) { Store.promises().firstOrNull { it.pkg == pkg } }
    val today = Store.dayKey()
    var stage by remember { mutableIntStateOf(0) }
    var phase by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf(false) }

    LaunchedEffect(stage) {
        if (stage == 1) {
            phase = 0
            delay(2000); phase = 1
            delay(1000); phase = 2
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when {
            mode == "blocked" -> {
                Text("지금은 잠긴 앱이에요", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
                Spacer(Modifier.height(32.dp))
                Button(onClick = onHome, Modifier.fillMaxWidth().height(54.dp)) { Text("홈으로") }
            }

            mode == "limit" -> {
                Text("오늘 약속한 만큼 다 썼어요", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Text("내일 다시 만나요.", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                Spacer(Modifier.height(32.dp))
                Button(onClick = onHome, Modifier.fillMaxWidth().height(54.dp)) { Text("홈으로") }
            }

            stage == 0 -> {
                val used = if (pr != null) Promises.used(pr, today) else 0
                val limit = pr?.limit ?: 1
                val left = (limit - used).coerceAtLeast(0)
                val leftText = if (pr?.unit == "count") "${left}번 남음" else "${Fmt.minutes(left)} 남음"
                val limitText = if (pr?.unit == "count") "하루 ${limit}번" else "하루 ${Fmt.minutes(limit)}"
                Text("나는 $label 을(를)\n$limitText 만 쓴다.", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(28.dp))
                LinearProgressIndicator(
                    progress = { (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(10.dp),
                    color = Color(0xFFFFB347), trackColor = Color.White.copy(alpha = 0.15f)
                )
                Spacer(Modifier.height(10.dp))
                Text(leftText, color = Color.White, fontSize = 16.sp)
                Text(
                    "약속 ${(pr?.streak ?: 0) + 1}일째 · 오늘 ${Store.opens(today, pkg)}번 열었어요",
                    color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp
                )
                Spacer(Modifier.height(36.dp))
                Button(onClick = onHome, Modifier.fillMaxWidth().height(54.dp)) { Text("조금만 더 참을게요") }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { stage = 1 }, Modifier.fillMaxWidth().height(54.dp)) { Text("그래도 쓸래요") }
                Spacer(Modifier.height(18.dp))
                Text(
                    "오늘은 아예 못 열게 잠글래요",
                    color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp,
                    modifier = Modifier.clickable {
                        val k = "dl_$today"
                        Store.putStrSet(k, Store.strSet(k) + pkg)
                        onHome()
                    }.padding(8.dp)
                )
            }

            else -> {
                Text(
                    if (phase == 0) "정말 필요한지 생각해 보세요" else "이 앱이 지금도 필요한가요?",
                    color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(40.dp))
                if (phase >= 2) {
                    Button(onClick = { sheet = true }, Modifier.fillMaxWidth().height(54.dp)) { Text("필요해요") }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "안 쓸래요", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp,
                        modifier = Modifier.clickable { onHome() }.padding(10.dp)
                    )
                }
            }
        }
    }

    if (sheet) {
        val maxAdd = if (pr != null && pr.unit == "time") (pr.limit - Promises.used(pr, today)).coerceAtLeast(1) else 120
        var minutes by remember { mutableIntStateOf(0) }
        AlertDialog(
            onDismissRequest = { sheet = false },
            title = { Text("얼마나 쓸까요?") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("${minutes}분", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("시간을 추가해 주세요", fontSize = 13.sp)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 5, 15, 30).forEach { a ->
                            OutlinedButton(
                                onClick = { minutes = (minutes + a).coerceAtMost(maxAdd) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
                            ) { Text("+$a") }
                        }
                    }
                    TextButton(onClick = { minutes = 0 }) { Text("되돌리기") }
                }
            },
            confirmButton = {
                Button(
                    enabled = minutes > 0,
                    onClick = {
                        Store.put("grant_$pkg", SystemClock.elapsedRealtime() + minutes * 60_000L)
                        Store.addOpen(pkg)
                        sheet = false
                        onDone()
                    }
                ) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { sheet = false }) { Text("취소") } }
        )
    }
}
