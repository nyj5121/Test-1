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

/** 바보폰 모드의 홈 화면. 시계와 꼭 필요한 앱만 글자로 보여준다. */
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
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(10_000) } }

    val rows = remember(Store.dumbApps) {
        val l = ArrayList<String>()
        Apps.dialerPkg(ctx)?.let { l.add(it) }
        Apps.smsPkg(ctx)?.let { if (it !in l) l.add(it) }
        Store.dumbApps.forEach { if (it !in l) l.add(it) }
        l.toList()
    }

    Column(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 32.dp, vertical = 56.dp)) {
        Text(
            SimpleDateFormat("M월 d일 EEEE", Locale.KOREA).format(Date(now)),
            color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp
        )
        Text(
            SimpleDateFormat("a h:mm", Locale.KOREA).format(Date(now)),
            color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Light
        )
        Spacer(Modifier.height(40.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            items(rows) { p ->
                Text(
                    Apps.label(ctx, p), color = Color.White, fontSize = 26.sp,
                    modifier = Modifier.fillMaxWidth().clickable { Apps.open(ctx, p) }.padding(vertical = 10.dp)
                )
            }
        }
        Text(
            "단단 설정", color = Color.White.copy(alpha = 0.45f), fontSize = 15.sp,
            modifier = Modifier.clickable {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.padding(vertical = 8.dp)
        )
        if (preview) TextButton(onClick = onClose) { Text("미리보기 닫기") }
    }
}
