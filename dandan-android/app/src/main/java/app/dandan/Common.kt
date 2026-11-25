package app.dandan

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.filter

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8C9BFF),
    onPrimary = Color(0xFF0B0E2A),
    secondary = Color(0xFFFFB347),
    background = Color(0xFF0F1115),
    surface = Color(0xFF171A21),
    onBackground = Color(0xFFECEEF3),
    onSurface = Color(0xFFECEEF3),
    surfaceVariant = Color(0xFF232733),
    onSurfaceVariant = Color(0xFFA9AFC0),
    outline = Color(0xFF3A4054)
)

@Composable
fun DandanTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, content = content)
}

fun toast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

@Composable
fun AppIcon(pkg: String, size: Dp = 40.dp) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) {
        try { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() } catch (e: Exception) { null }
    }
    if (bmp != null) Image(bmp, contentDescription = null, modifier = Modifier.size(size))
    else Box(Modifier.size(size).clip(CircleShape).background(Color.Gray))
}

/** 위아래로 밀어 값을 고르는 휠. 가운데 줄이 선택값. */
@Composable
fun Wheel(count: Int, value: Int, onChange: (Int) -> Unit, width: Dp = 84.dp) {
    val itemH = 52.dp
    val state = rememberLazyListState(initialFirstVisibleItemIndex = value.coerceIn(0, count - 1))
    val fling = rememberSnapFlingBehavior(state)
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.filter { !it }.collect {
            onChange(state.firstVisibleItemIndex.coerceIn(0, count - 1))
        }
    }
    val mid by remember { derivedStateOf { state.firstVisibleItemIndex + 1 } }
    LazyColumn(
        state = state,
        flingBehavior = fling,
        modifier = Modifier.height(itemH * 3).width(width),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items(count + 2) { i ->
            Box(Modifier.height(itemH).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (i in 1..count) {
                    val sel = i == mid
                    Text(
                        String.format("%02d", i - 1),
                        fontSize = if (sel) 34.sp else 22.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        color = if (sel) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
fun Seg(items: List<String>, sel: Int, onSel: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp)).background(cs.surfaceVariant).padding(4.dp)
    ) {
        items.forEachIndexed { i, t ->
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (i == sel) cs.primary else Color.Transparent)
                    .clickable { onSel(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    t, fontWeight = FontWeight.Bold,
                    color = if (i == sel) cs.onPrimary else cs.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun BigButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp)
    ) { Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun <T> Pick(text: String, options: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            "$text ▾",
            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (label, v) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onPick(v) })
            }
        }
    }
}

@Composable
fun FullDialog(
    title: String,
    onCancel: () -> Unit,
    saveLabel: String? = "저장",
    onSave: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onCancel) { Text(if (saveLabel == null) "닫기" else "취소") }
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    if (saveLabel != null) TextButton(onClick = onSave) { Text(saveLabel) }
                    else Spacer(Modifier.width(64.dp))
                }
                content()
            }
        }
    }
}

/** 앱을 고르는 전체 화면. single=true면 하나만 고른다. */
@Composable
fun AppPicker(
    title: String,
    initial: Set<String>,
    single: Boolean = false,
    onDone: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val all = remember { Apps.launchable(ctx) }
    var q by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf(initial) }
    FullDialog(title, onCancel = onDismiss, onSave = { onDone(sel) }) {
        OutlinedTextField(
            value = q, onValueChange = { q = it },
            placeholder = { Text("앱 이름 검색") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        Text(
            "선택 ${sel.size}", modifier = Modifier.padding(16.dp, 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(all.filter { it.label.contains(q, true) }, key = { it.pkg }) { a ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        sel = if (a.pkg in sel) sel - a.pkg else if (single) setOf(a.pkg) else sel + a.pkg
                    }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIcon(a.pkg, 40.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(a.label, Modifier.weight(1f), fontSize = 16.sp)
                    Checkbox(checked = a.pkg in sel, onCheckedChange = null)
                }
            }
        }
    }
}
