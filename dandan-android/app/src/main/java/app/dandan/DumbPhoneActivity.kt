package app.dandan

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 바보폰 모드의 홈 화면. 시계와 꼭 필요한 앱만 글자로 보여준다. 자유 시간이 되면 저절로 닫힌다. */
class DumbPhoneActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        val preview = intent.getBooleanExtra("preview", false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (preview) finish() }
        })
        setContent { DandanTheme { DumbHome(preview) { finish() } } }
    }
}

@Composable
fun DumbHome(preview: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    Store.rev.intValue
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var ask by remember { mutableStateOf(false) }
    var policyAsk by remember { mutableStateOf(false) }
    var offAsk by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    if (!preview && !Dumb.active(ctx)) LaunchedEffect(now) { onClose() }

    val rows = remember(Store.dumbApps) {
        val l = ArrayList<String>()
        Apps.dialerPkg(ctx)?.let { l.add(it) }
        Apps.smsPkg(ctx)?.let { if (it !in l) l.add(it) }
        Store.dumbApps.forEach { if (it !in l) l.add(it) }
        l.filter { it !in Dumb.ALWAYS_BLOCKED }
    }
    val next = Dumb.nextFreeStart(now)

    Column(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 32.dp, vertical = 56.dp)) {
        Text(
            SimpleDateFormat("M월 d일 EEEE", Locale.KOREA).format(Date(now)),
            color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp
        )
        Text(
            SimpleDateFormat("a h:mm", Locale.KOREA).format(Date(now)),
            color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Light
        )
        Text(
            if (next != null) "다음 자유 시간 " + SimpleDateFormat("E a h:mm", Locale.KOREA).format(Date(next))
            else "오늘은 자유 시간이 없어요",
            color = Color.White.copy(alpha = 0.45f), fontSize = 13.sp
        )
        Spacer(Modifier.height(36.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            items(rows) { p ->
                Text(
                    Apps.label(ctx, p), color = Color.White, fontSize = 26.sp,
                    modifier = Modifier.fillMaxWidth().clickable { Apps.open(ctx, p) }.padding(vertical = 10.dp)
                )
            }
        }
        // 바보폰 시간에는 단단 설정에 들어갈 수 없어서, 친구에게 부탁하는 것만 여기서 한다
        if (Dumb.friendControlled()) {
            Text(
                "${Friend.name()}에게 자유 시간 잠깐 요청", color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp,
                modifier = Modifier.clickable { ask = true }.padding(vertical = 8.dp)
            )
            Text(
                "자유 시간 정해 달라고 요청", color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp,
                modifier = Modifier.clickable { policyAsk = true }.padding(vertical = 8.dp)
            )
        }
        if (Friend.scopeOn("dumb_release") || Dumb.friendControlled()) {
            Text(
                "바보폰 끄기 요청 (${Friend.name()} 승인)", color = Color.White.copy(alpha = 0.45f), fontSize = 15.sp,
                modifier = Modifier.clickable { offAsk = true }.padding(vertical = 8.dp)
            )
        }
        if (preview) TextButton(onClick = onClose) { Text("미리보기 닫기") }
    }
    if (ask) FreeAskFlow { ask = false }
    if (policyAsk) DumbPolicyDialog { policyAsk = false }
    if (offAsk) {
        FriendRequestDialog(
            kind = "cfg_change", label = "바보폰 끄기", param = "0",
            onDismiss = { offAsk = false },
            onApproved = {
                Store.dumbOn = false
                Store.put("dumbEndW", 0L)
            }
        )
    }
}
