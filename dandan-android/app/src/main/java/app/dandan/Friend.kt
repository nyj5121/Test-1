package app.dandan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * 친구 승인. 서버 없이 서명으로 동작한다.
 *  - 친구가 링크 페이지에서 열쇠 한 쌍을 만들고, 공개 열쇠(연결 코드)만 나에게 준다.
 *  - 나는 요청 링크를 친구에게 보내고, 친구가 승인하면 나오는 서명 코드를 받아 붙여넣는다.
 *  - 앱은 공개 열쇠로 서명을 검증한다. 친구의 개인 열쇠 없이는 승인 코드를 만들 수 없다.
 */
object Friend {
    const val BASE_DEFAULT = "https://nyj5121.github.io/Test-1/friend.html"

    val SCOPES = listOf(
        Triple("promise_extra", "약속 앱 시간 추가", "약속한 앱을 더 쓰고 싶을 때, 내가 정하지 않고 친구가 정해요."),
        Triple("app_unlock", "앱 잠금 해제", "잠긴 앱을 잠깐 열고 싶을 때 친구에게 허락받아요."),
        Triple("phone_unlock", "폰 잠금 중간 해제", "폰 잠금을 끝나기 전에 풀려면 친구 승인이 필요해요. (패널티 없음)"),
        Triple("sched_edit", "예약 수정·삭제·끄기", "'예약 잠금 전 수정 방지'로 막힌 예약을 고치려면 친구 승인이 필요해요."),
        Triple("relax", "잠금 강도 설정 끄기", "곧 잠기는데 철벽 모드·앱 삭제 방지 등을 끄려면 친구 승인이 필요해요."),
        Triple("dumb_policy", "바보폰 자유 시간을 친구가 정하기", "바보폰에서 벗어날 수 있는 시간대를 친구만 정해요. 바보폰을 끄는 것도 친구 승인이 필요해요."),
        Triple("dumb_release", "바보폰 해제를 친구 승인으로", "바보폰을 끄려면(정한 기간이 남았어도) 친구 승인이 필요해요.")
    )

    /** 옵션을 켠다. 바보폰 자유 시간을 친구가 정하게 하면 내가 정해 둔 시간대는 지워진다. */
    fun enableScope(key: String) {
        Store.put("fs_$key", true)
        if (key == "dumb_policy") {
            Store.put("dumbWins", "")
            Store.put("dumbExp", 0L)
        }
    }

    /** 이 앱 설치마다 다른 짧은 번호. 친구가 만든 정책이 다른 폰에 쓰이지 않게 묶는 데 쓴다. */
    fun deviceId(): String {
        val cur = Store.str("devId")
        if (cur.isNotBlank()) return cur
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        val id = (1..8).map { chars[r.nextInt(chars.length)] }.joinToString("")
        Store.put("devId", id)
        return id
    }

    /** 친구가 자유 시간을 정하는 페이지로 가는 링크. 지금 정책을 함께 실어 보낸다. */
    fun policyLink(reason: String = ""): String {
        val q = listOf(
            "q1", deviceId(), clean(myName()), Store.str("dumbWins"), Store.long("dumbExp").toString(),
            cleanReason(reason)
        ).joinToString("|")
        return base() + "#q=" + enc(q.toByteArray(Charsets.UTF_8))
    }

    /** 친구가 만든 정책 코드("정책.서명")를 검증해 적용한다. 성공하면 null, 실패하면 이유를 돌려준다. */
    fun applyPolicy(code: String): String? {
        val parts = code.trim().split(".")
        if (parts.size != 2) return "코드 형식이 올바르지 않아요."
        val policy = try { String(dec(parts[0]), Charsets.UTF_8) } catch (e: Exception) { return "코드를 읽을 수 없어요." }
        if (!verifyRaw(Store.str("friendPk"), policy, parts[1])) return "친구가 만든 코드가 아니에요."
        val p = policy.split("|")
        if (p.size < 6 || p[0] != "p1") return "정책 형식이 올바르지 않아요."
        if (p[1] != deviceId()) return "이 폰용 코드가 아니에요."
        val ver = p[2].toLongOrNull() ?: return "정책 형식이 올바르지 않아요."
        val exp = p[3].toLongOrNull() ?: return "정책 형식이 올바르지 않아요."
        if (ver <= Store.long("dumbVer")) return "이미 적용했거나 더 오래된 코드예요."
        if (exp <= System.currentTimeMillis() / 1000) return "만료된 코드예요."
        Store.put("dumbWins", Dumb.encode(Dumb.parse(p[4])))
        Store.put("dumbExp", exp)
        Store.put("dumbVer", ver)
        return null
    }

    fun base(): String = Store.str("friendBase", BASE_DEFAULT)
    fun registered(): Boolean = Store.str("friendPk").isNotBlank()
    fun name(): String = Store.str("friendName", "친구")
    fun myName(): String = Store.str("myName", "나")
    fun scopeOn(s: String): Boolean = registered() && Store.bool("fs_$s")

    private const val F = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun enc(b: ByteArray): String = Base64.encodeToString(b, F)

    fun dec(s: String): ByteArray {
        var t = s.filter { !it.isWhitespace() }.replace('+', '-').replace('/', '_')
        while (t.length % 4 != 0) t += "="
        return Base64.decode(t, Base64.URL_SAFE)
    }

    private fun clean(s: String) = s.replace("|", " ").replace("\n", " ").trim().take(40)

    private fun cleanReason(s: String) = s.replace("|", " ").replace("\n", " ").trim().take(120)

    /** 같은 요청이 아직 유효하면 그대로 돌려준다(친구가 이미 받은 링크가 무효가 되지 않게). 없으면 null. */
    fun existing(kind: String, label: String, param: String): String? {
        val cur = Store.str("friendPending")
        if (cur.isBlank()) return null
        val p = cur.split("|")
        val now = System.currentTimeMillis() / 1000
        return if (p.size >= 8 && p[1] == kind && p[2] == clean(label) && p[3] == param &&
            (p[5].toLongOrNull() ?: 0L) > now + 60
        ) cur else null
    }

    /** 새 요청을 만든다. 사유도 서명 대상에 들어가서 친구가 본 사유를 나중에 바꿀 수 없다. */
    fun newRequest(kind: String, label: String, param: String, reason: String): String {
        val now = System.currentTimeMillis() / 1000
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val req = listOf(
            "d1", kind, clean(label), param, enc(nonce), (now + 30 * 60).toString(),
            clean(myName()), cleanReason(reason)
        ).joinToString("|")
        Store.put("friendPending", req)
        return req
    }

    fun reasonOf(req: String): String = req.split("|").getOrNull(7) ?: ""

    /** 알림 제목: "예준의 요청: 인스타 30분 풀기" */
    fun headline(req: String): String = "${myName()}의 요청: ${summary(req)}"

    fun summary(req: String): String {
        val p = req.split("|")
        if (p.size < 7) return "요청"
        return summaryOf(p[1], p[2], p[3])
    }

    fun summaryOf(kind: String, label: String, param: String): String = when (kind) {
        "promise_extra" -> "\"$label\" ${param}분 더 쓰기"
        "app_unlock" -> "\"$label\" 앱 잠금을 ${param}분 동안 풀기"
        "phone_unlock" -> "폰 잠금 중간 해제"
        "sched_edit" -> "잠금 예약($label) 고치기·지우기"
        "relax" -> "잠금 강도 설정 끄기"
        "dumb_free" -> "바보폰에서 ${param}분 벗어나기"
        else -> label
    }

    fun link(req: String): String = base() + "#r=" + enc(req.toByteArray(Charsets.UTF_8))

    fun inviteLink(): String = base() + "#hello=1&from=" + URLEncoder.encode(myName(), "UTF-8")

    fun requestMessage(req: String): String =
        "[단단] ${myName()}의 요청\n${summary(req)}\n사유: ${reasonOf(req).ifBlank { "(없음)" }}\n\n" +
            "승인하려면 링크를 열어 주세요 ↓\n${link(req)}\n\n(승인하면 나오는 코드를 나한테 보내 줘)"

    fun register(name: String, pkCode: String): Boolean = try {
        val bytes = dec(pkCode)
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        Store.put("friendPk", enc(bytes))
        Store.put("friendName", name.trim().ifBlank { "친구" })
        true
    } catch (e: Exception) { false }

    fun unregister() {
        Store.put("friendPk", "")
        Store.put("friendPending", "")
        SCOPES.forEach { Store.put("fs_${it.first}", false) }
    }

    /** 웹의 서명은 r||s 64바이트이고, 자바는 DER를 기대해서 바꿔 준다. */
    fun rawToDer(raw: ByteArray): ByteArray {
        fun part(x: ByteArray): ByteArray {
            var b = x.dropWhile { it == 0.toByte() }.toByteArray()
            if (b.isEmpty()) b = byteArrayOf(0)
            if (b[0] < 0) b = byteArrayOf(0) + b
            return byteArrayOf(0x02, b.size.toByte()) + b
        }
        val r = part(raw.copyOfRange(0, 32))
        val s = part(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, (r.size + s.size).toByte()) + r + s
    }

    fun verifyRaw(pkB64: String, message: String, sigB64: String): Boolean = try {
        val sig = dec(sigB64)
        if (sig.size != 64) false
        else {
            val pk = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(dec(pkB64)))
            val v = Signature.getInstance("SHA256withECDSA")
            v.initVerify(pk)
            v.update(message.toByteArray(Charsets.UTF_8))
            v.verify(rawToDer(sig))
        }
    } catch (e: Exception) { false }

    /** 승인 코드가 지금 걸어 둔 요청에 대한 친구의 서명이면 true. 한 번 쓰면 요청은 폐기된다. */
    fun verify(code: String): Boolean {
        val pending = Store.str("friendPending")
        if (pending.isBlank()) return false
        val p = pending.split("|")
        val exp = p.getOrNull(5)?.toLongOrNull() ?: return false
        if (System.currentTimeMillis() / 1000 > exp) return false
        val ok = verifyRaw(Store.str("friendPk"), pending, code)
        if (ok) Store.put("friendPending", "")
        return ok
    }
}

internal fun readClipboard(ctx: Context): String = try {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val c = cm.primaryClip
    if (c != null && c.itemCount > 0) c.getItemAt(0).coerceToText(ctx).toString() else ""
} catch (e: Exception) { "" }

internal fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("dandan", text))
}

internal fun shareText(ctx: Context, text: String) {
    try {
        ctx.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                "친구에게 보내기"
            )
        )
    } catch (e: Exception) { toast(ctx, "공유할 수 없어요") }
}

/** 분 단위를 직접 정하는 창. +버튼으로 쌓아 가고, 되돌리기로 0에서 다시 시작한다. */
@Composable
fun MinutesDialog(
    title: String,
    note: String,
    confirmLabel: String,
    max: Int = 480,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var minutes by remember { mutableIntStateOf(0) }
    val pad = PaddingValues(horizontal = 10.dp)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(if (minutes == 0) "0분" else Fmt.minutes(minutes), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 5, 10).forEach { a ->
                        OutlinedButton(onClick = { minutes = (minutes + a).coerceAtMost(max) }, contentPadding = pad) { Text("+$a") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30, 60, 120).forEach { a ->
                        OutlinedButton(onClick = { minutes = (minutes + a).coerceAtMost(max) }, contentPadding = pad) { Text("+$a") }
                    }
                }
                TextButton(onClick = { minutes = 0 }) { Text("되돌리기") }
            }
        },
        confirmButton = { Button(enabled = minutes > 0, onClick = { onPick(minutes) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

/**
 * 친구에게 요청을 보내고, 돌려받은 승인 코드를 확인하는 창.
 * 먼저 사유를 적어야 요청이 만들어진다. 사유는 서명 대상에 들어가서 친구에게 그대로 보인다.
 * 요청을 만들면 설정한 알림(문자·푸시)이 자동으로 간다. allowShare=false면 공유 버튼을 숨긴다(폰 잠금 중).
 */
@Composable
fun FriendRequestDialog(
    kind: String,
    label: String,
    param: String,
    allowShare: Boolean = true,
    onDismiss: () -> Unit,
    onApproved: () -> Unit
) {
    val ctx = LocalContext.current
    val existing = remember { Friend.existing(kind, label, param) }
    var req by remember { mutableStateOf(existing) }
    var reason by remember { mutableStateOf(existing?.let { Friend.reasonOf(it) } ?: "") }
    var code by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf("") }
    val r = req

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${Friend.name()}에게 요청") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(Friend.summaryOf(kind, label, param), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(10.dp))
                Text("1. 사유를 적어 주세요. 친구에게 그대로 보여요.", fontSize = 13.sp)
                OutlinedTextField(
                    value = reason, onValueChange = { reason = it.take(120) },
                    enabled = r == null, modifier = Modifier.fillMaxWidth(), maxLines = 3,
                    placeholder = { Text("왜 필요한가요?") }
                )
                if (r == null) {
                    Button(
                        enabled = reason.trim().isNotEmpty(),
                        onClick = {
                            val nr = Friend.newRequest(kind, label, param, reason)
                            req = nr
                            Notify.dispatch(ctx, Friend.headline(nr), "사유: " + Friend.reasonOf(nr), Friend.link(nr)) { sent = it }
                        }
                    ) { Text("요청 만들기") }
                    Text(
                        if (Notify.any()) "만들면 ${Friend.name()}에게 자동으로 알림이 가요."
                        else "자동 알림이 꺼져 있어요. 설정 → 친구 해제 → 알림 방법에서 켜면 자동으로 가요.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val msg = Friend.requestMessage(r)
                    Spacer(Modifier.height(10.dp))
                    Text("2. 친구에게 요청이 가야 해요.", fontSize = 13.sp)
                    if (sent.isNotBlank()) {
                        Text(sent, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                    } else if (!Notify.any()) {
                        Text("자동 알림이 꺼져 있어요. 아래 버튼으로 직접 보내세요.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copyText(ctx, msg); toast(ctx, "복사했어요") }) { Text("링크 복사") }
                        if (allowShare) OutlinedButton(onClick = { kakaoText(ctx, msg) }) { Text("카카오톡") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (allowShare) OutlinedButton(onClick = { shareText(ctx, msg) }) { Text("다른 앱으로") }
                        if (Notify.any()) OutlinedButton(onClick = {
                            sent = "다시 보내는 중…"
                            Notify.dispatch(ctx, Friend.headline(r), "사유: " + Friend.reasonOf(r), Friend.link(r)) { sent = it }
                        }) { Text("알림 다시 보내기") }
                    }
                    if (!allowShare) {
                        Text(
                            "잠금 중에는 허용 앱(예: 카카오톡)에서 붙여넣어 보낼 수 있어요.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { req = null; code = ""; err = ""; sent = "" }) { Text("사유 고쳐서 다시 만들기") }
                    Spacer(Modifier.height(6.dp))
                    Text("3. 친구가 승인하고 보내 준 코드를 붙여넣으세요.", fontSize = 13.sp)
                    OutlinedTextField(
                        value = code, onValueChange = { code = it; err = "" },
                        modifier = Modifier.fillMaxWidth(), maxLines = 4
                    )
                    TextButton(onClick = { code = readClipboard(ctx) }) { Text("붙여넣기") }
                    if (err.isNotBlank()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    Text(
                        "30분 안에 승인받아야 해요.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (r != null) {
                Button(
                    enabled = code.isNotBlank(),
                    onClick = {
                        if (Friend.verify(code)) { onDismiss(); onApproved() }
                        else err = "코드가 맞지 않거나 만료됐어요."
                    }
                ) { Text("확인") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}

/** 친구 등록 창: 내 이름, 초대 링크 보내기, 친구가 준 연결 코드 붙여넣기. */
@Composable
fun FriendSetupDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var me by remember { mutableStateOf(Store.str("myName")) }
    var friend by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("친구 등록") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("1. 이름을 정하고 친구에게 초대 링크를 보내세요.", fontSize = 13.sp)
                OutlinedTextField(me, { me = it }, label = { Text("내 이름 (친구에게 보여요)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(friend, { friend = it }, label = { Text("친구 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        Store.put("myName", me.trim().ifBlank { "나" })
                        copyText(ctx, "[단단] ${Friend.myName()}이(가) 너를 친구로 지정하고 싶대. 링크를 열어서 '친구 되어 주기'를 누르고, 나온 연결 코드를 나한테 보내 줘.\n${Friend.inviteLink()}")
                        toast(ctx, "복사했어요")
                    }) { Text("링크 복사") }
                    OutlinedButton(onClick = {
                        Store.put("myName", me.trim().ifBlank { "나" })
                        shareText(ctx, "[단단] ${Friend.myName()}이(가) 너를 친구로 지정하고 싶대. 링크를 열어서 '친구 되어 주기'를 누르고, 나온 연결 코드를 나한테 보내 줘.\n${Friend.inviteLink()}")
                    }) { Text("공유하기") }
                }
                Spacer(Modifier.height(12.dp))
                Text("2. 친구가 보내 준 연결 코드를 붙여넣으세요.", fontSize = 13.sp)
                OutlinedTextField(code, { code = it; err = "" }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                TextButton(onClick = { code = readClipboard(ctx) }) { Text("붙여넣기") }
                if (err.isNotBlank()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "친구는 크롬이나 삼성 인터넷 같은 일반 브라우저로 링크를 열어야 해요. 카카오톡 안에서 열었다면 '다른 브라우저로 열기'를 쓰세요.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                enabled = code.isNotBlank() && friend.isNotBlank(),
                onClick = {
                    Store.put("myName", me.trim().ifBlank { "나" })
                    if (Friend.register(friend, code)) { toast(ctx, "친구를 등록했어요"); onDismiss() }
                    else err = "연결 코드가 올바르지 않아요."
                }
            ) { Text("등록") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}

/** 카카오톡으로 바로 보내기(받는 사람은 카톡 안에서 고른다). 카톡이 없으면 일반 공유 창을 쓴다. */
internal fun kakaoText(ctx: Context, text: String) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                .setPackage("com.kakao.talk")
        )
    } catch (e: Exception) { shareText(ctx, text) }
}

/** 친구에게 자동으로 알림을 보낸다: 문자(친구 번호)와 푸시(ntfy 주제). 둘 다 설정한 만큼 켜진다. */
object Notify {
    fun smsOn(): Boolean = Store.bool("notifySms") && Store.str("friendPhone").isNotBlank()
    fun pushOn(): Boolean = Store.bool("notifyPush") && Store.str("friendTopic").isNotBlank()
    fun any(): Boolean = smsOn() || pushOn()

    fun newTopic(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val r = SecureRandom()
        return "dandan-" + (1..20).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    fun subscribeText(topic: String): String =
        "[단단] ${Friend.myName()}의 요청이 오면 알림을 받으려면:\n" +
            "1) 무료 앱 'ntfy'를 설치해 주세요 (플레이스토어/앱스토어에서 검색)\n" +
            "2) 앱에서 '+' 를 눌러 주제 이름에 아래를 그대로 입력하고 구독해 주세요\n$topic\n" +
            "(앱 없이 브라우저로 보려면 https://ntfy.sh/$topic )"

    /**
     * 설정된 모든 방법으로 보내고, 결과 줄을 메인 스레드에서 onStatus로 알려 준다.
     * 문자는 두 통(설명 + 링크)으로 나가고, 푸시는 눌러서 바로 승인 페이지가 열린다.
     */
    fun dispatch(ctx: Context, headline: String, detail: String, link: String, onStatus: (String) -> Unit) {
        if (!any()) { onStatus(""); return }
        val lines = ArrayList<String>()
        val main = Handler(Looper.getMainLooper())
        fun publish() {
            val t = synchronized(lines) { lines.joinToString("\n") }
            main.post { onStatus(t) }
        }
        if (smsOn()) {
            val ok = sendSms(ctx, Store.str("friendPhone"), "[단단] $headline\n$detail\n(아래 링크에서 승인해 주세요)", link)
            lines.add(if (ok) "문자 ✓ 보냈어요" else "문자 ✗ 못 보냈어요 (번호·문자 권한 확인)")
        }
        if (pushOn()) {
            val idx = lines.size
            val topic = Store.str("friendTopic")
            lines.add("푸시 … 보내는 중")
            publish()
            Thread {
                val ok = sendPush(topic, headline, detail, link)
                synchronized(lines) { lines[idx] = if (ok) "푸시 ✓ 보냈어요" else "푸시 ✗ 못 보냈어요 (인터넷 확인)" }
                publish()
            }.start()
        } else {
            publish()
        }
    }

    @Suppress("DEPRECATION")
    private fun sendSms(ctx: Context, number: String, text: String, link: String): Boolean = try {
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            false
        } else {
            val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            val to = number.filter { it.isDigit() || it == '+' }
            sm.sendMultipartTextMessage(to, null, sm.divideMessage(text), null, null)
            sm.sendMultipartTextMessage(to, null, sm.divideMessage(link), null, null)
            true
        }
    } catch (e: Exception) { false }

    private fun post(body: JSONObject): Boolean = try {
        val c = URL("https://ntfy.sh/").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        c.disconnect()
        code in 200..299
    } catch (e: Exception) { false }

    private fun sendPush(topic: String, headline: String, detail: String, link: String): Boolean {
        val full = JSONObject()
            .put("topic", topic)
            .put("title", ("단단 · " + headline).take(120))
            .put("message", ("$detail\n\n$link").take(3500))
            .put("priority", 4)
            .put("tags", JSONArray(listOf("lock")))
            .put("click", link)
            .put("actions", JSONArray().put(JSONObject().put("action", "view").put("label", "승인 페이지 열기").put("url", link)))
        if (post(full)) return true
        // 링크가 길어서 거절되면 본문에만 링크를 넣어 다시 보낸다
        val plain = JSONObject()
            .put("topic", topic)
            .put("title", ("단단 · " + headline).take(120))
            .put("message", ("$detail\n\n$link").take(3500))
            .put("priority", 4)
        return post(plain)
    }
}

/** 친구 알림 방법 설정: 문자 자동 전송, 푸시(ntfy). */
@Composable
fun NotifyDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var phone by remember { mutableStateOf(Store.str("friendPhone")) }
    var sms by remember { mutableStateOf(Store.bool("notifySms")) }
    var push by remember { mutableStateOf(Store.bool("notifyPush")) }
    var topic by remember { mutableStateOf(Store.str("friendTopic")) }
    var status by remember { mutableStateOf("") }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) {
            sms = false
            toast(ctx, "문자 권한이 필요해요. 단단 앱 정보에서 ⋮ → '제한된 설정 허용'을 누른 뒤 다시 켜 보세요")
        }
    }
    fun save() {
        Store.put("friendPhone", phone.filter { it.isDigit() || it == '+' })
        Store.put("notifySms", sms)
        Store.put("notifyPush", push)
        Store.put("friendTopic", topic.trim())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("친구 알림 방법") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "요청을 만들면 켜 둔 방법으로 친구에게 자동으로 가요. 둘 다 켜도 돼요.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("문자 자동 전송", fontWeight = FontWeight.Bold)
                        Text(
                            "친구는 설치할 게 없어요. 링크가 길어서 문자가 여러 통 갈 수 있고 요금이 나올 수 있어요.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = sms, onCheckedChange = { want ->
                        sms = want
                        if (want && ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                            perm.launch(Manifest.permission.SEND_SMS)
                        }
                    })
                }
                OutlinedTextField(
                    phone, { phone = it }, label = { Text("친구 전화번호") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("푸시 알림 (ntfy)", fontWeight = FontWeight.Bold)
                        Text(
                            "친구가 무료 앱 ntfy를 깔고 아래 주제를 한 번 구독해야 해요. 내용은 ntfy 서버를 거쳐요.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = push, onCheckedChange = { want ->
                        push = want
                        if (want && topic.isBlank()) topic = Notify.newTopic()
                    })
                }
                OutlinedTextField(
                    topic, { topic = it }, label = { Text("주제 이름 (비밀번호처럼 다루세요)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { topic = Notify.newTopic() }) { Text("새 주제") }
                    OutlinedButton(onClick = {
                        if (topic.isBlank()) topic = Notify.newTopic()
                        save()
                        shareText(ctx, Notify.subscribeText(topic))
                    }) { Text("구독 방법 보내기") }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = {
                    save()
                    status = "보내는 중…"
                    Notify.dispatch(ctx, "테스트", "이 알림이 보이면 연결이 잘 된 거예요.", Friend.base()) {
                        status = it.ifBlank { "켜진 알림 방법이 없어요" }
                    }
                }) { Text("저장하고 테스트 보내기") }
                if (status.isNotBlank()) Text(status, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
            }
        },
        confirmButton = { Button(onClick = { save(); onDismiss() }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}
