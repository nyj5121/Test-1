package app.dandan

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DumbTab(goPerm: () -> Unit) {
    Store.rev.intValue
    val ctx = LocalContext.current
    var picker by remember { mutableStateOf(false) }
    val on = Store.dumbOn
    val apps = Store.dumbApps.toList()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("스마트폰을 바보폰으로", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "홈 화면을 시계와 꼭 필요한 앱 이름만 있는 단순한 화면으로 바꿔요. 아이콘과 알림 배지가 사라지면 습관적으로 폰을 여는 일이 줄어들어요.",
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
                Text(if (on) "켜져 있어요" else "꺼져 있어요", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = on,
                onCheckedChange = { want ->
                    when {
                        want && !Perm.accessibility(ctx) -> goPerm()
                        !want && Engine.nearLock(ctx) -> toast(ctx, "곧 잠기거나 잠금 중이라 지금은 끌 수 없어요")
                        else -> Store.dumbOn = want
                    }
                }
            )
        }
        Spacer(Modifier.height(18.dp))

        Text("홈 화면에 둘 앱", fontWeight = FontWeight.Bold)
        Text("전화와 메시지는 항상 보여요.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            "켜 두면 홈 버튼을 눌러도 이 화면이 나와요. 끄려면 홈 화면 아래의 '단단 설정'으로 들어오세요.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (picker) {
        AppPicker(
            "홈 화면에 둘 앱", Store.dumbApps,
            onDone = { Store.dumbApps = it; picker = false }, onDismiss = { picker = false }
        )
    }
}
