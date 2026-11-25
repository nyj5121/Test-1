package app.dandan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * 이 폰의 열쇠. 안드로이드 키스토어 안에서 만들어서 밖으로 꺼낼 수 없다.
 * 단단 앱끼리 연결된 친구의 요청을 앱 안에서 승인할 때 이 열쇠로 서명한다(웹 페이지의 서명과 같은 형식).
 */
object Me {
    private const val ALIAS = "dandan_me"

    private fun entry(): KeyStore.PrivateKeyEntry? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            g.initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            g.generateKeyPair()
        }
        ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
    } catch (e: Exception) { null }

    private var pkCache = ""

    /** 내 공개 열쇠(SPKI, base64url). 실패하면 빈 문자열. */
    fun publicKey(): String {
        if (pkCache.isNotBlank()) return pkCache
        pkCache = entry()?.certificate?.publicKey?.encoded?.let { Friend.enc(it) } ?: ""
        return pkCache
    }

    /** 웹과 같은 형식(r||s 64바이트, base64url)으로 서명한다. */
    fun sign(msg: String): String? = try {
        val e = entry()
        if (e == null) null else {
            val s = Signature.getInstance("SHA256withECDSA")
            s.initSign(e.privateKey)
            s.update(msg.toByteArray(Charsets.UTF_8))
            Friend.enc(derToRaw(s.sign()))
        }
    } catch (ex: Exception) { null }

    /** 내 받은 요청함 주소. 처음 한 번 만들고 계속 쓴다. 아는 사람만 요청을 넣을 수 있다. */
    fun inbox(): String {
        val cur = Store.str("myInbox")
        if (cur.isNotBlank()) return cur
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val r = SecureRandom()
        val t = "dandan-i-" + (1..24).map { chars[r.nextInt(chars.length)] }.joinToString("")
        Store.put("myInbox", t)
        return t
    }

    /** 자바의 DER 서명을 웹이 쓰는 r||s 64바이트로 바꾼다. */
    fun derToRaw(der: ByteArray): ByteArray {
        var i = if (der[1].toInt() and 0x80 != 0) 2 + (der[1].toInt() and 0x7f) else 2
        fun next(): ByteArray {
            require(der[i] == 0x02.toByte())
            val len = der[i + 1].toInt() and 0xff
            val v = der.copyOfRange(i + 2, i + 2 + len).dropWhile { it == 0.toByte() }.toByteArray()
            i += 2 + len
            require(v.size <= 32)
            return ByteArray(32 - v.size) + v
        }
        val r = next()
        val s = next()
        return r + s
    }
}

/**
 * 단단 앱끼리 연결.
 *  - A가 연결 코드를 보내면 B의 단단이 A를 친구로 넣고, 자기 카드(이름·공개 열쇠·받은 요청함)를 답장으로 보낸다.
 *  - A의 단단은 그 답장을 받아 B를 친구로 넣는다. 그러면 서로 요청을 앱 안으로 보내고 앱 안에서 승인할 수 있다.
 *  - 승인 코드는 웹 페이지와 같은 형식이라, 앱으로 승인하든 링크로 승인하든 똑같이 풀린다.
 */
object Link {
    data class Card(val name: String, val pk: String, val inbox: String, val reply: String)

    private fun cardJson(reply: String): JSONObject {
        val o = JSONObject().put("v", 1).put("n", Friend.myName()).put("k", Me.publicKey()).put("i", Me.inbox())
        if (reply.isNotBlank()) o.put("t", reply)
        return o
    }

    private fun token(reply: String) = Friend.enc(cardJson(reply).toString().toByteArray(Charsets.UTF_8))

    fun ready(): Boolean = Me.publicKey().isNotBlank()

    fun offerText(reply: String): String {
        val tk = token(reply)
        return "[단단] ${Friend.myName()}이(가) 단단 앱끼리 연결하재. 연결하면 서로의 잠금 해제 요청을 앱에서 바로 승인할 수 있어.\n\n" +
            "1) 아래 링크를 열고 '단단 앱으로 열기'를 눌러 줘\n${Friend.base()}#pair=$tk\n\n" +
            "2) 안 되면 단단 앱 → 친구 → '코드로 연결'에 아래 코드를 붙여넣어 줘\ndandan-pair:$tk"
    }

    /** 연결 코드·링크·답장 어디서든 카드를 읽는다. */
    fun parse(text: String): Card? = try {
        val m = Regex("(?:dandan-pair:|dandan-card:|pair=|[?&]c=)([A-Za-z0-9_-]{20,})").find(text)
        if (m == null) null else {
            val o = JSONObject(String(Friend.dec(m.groupValues[1]), Charsets.UTF_8))
            val pk = o.optString("k")
            val inbox = o.optString("i")
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Friend.dec(pk)))
            if (!Relay.valid(inbox)) null
            else Card(o.optString("n", "친구").take(40).ifBlank { "친구" }, pk, inbox, o.optString("t"))
        }
    } catch (e: Exception) { null }

    fun isMe(c: Card): Boolean = c.pk == Me.publicKey()

    /** 카드의 주인을 친구로 넣는다(받은 요청함 주소까지). */
    fun add(c: Card): Boolean {
        if (isMe(c) || !Friend.register(c.name, c.pk)) return false
        Friend.setInbox(Friend.enc(Friend.dec(c.pk)), c.inbox)
        return true
    }

    /** 받은 연결 코드를 수락: 상대를 친구로 넣고 내 카드를 답장으로 보낸다. */
    fun accept(c: Card): Boolean {
        if (!add(c)) return false
        if (c.reply.isNotBlank()) Relay.postAsync(c.reply, "dandan-card:" + token(""))
        return true
    }

    /** 앱으로 보낼 요청: 요청 문자열 + 답장 주소 + 내 서명(요청이 정말 나에게서 왔다는 표시). */
    fun requestPayload(req: String): String {
        val sig = Me.sign(req) ?: return ""
        val o = JSONObject().put("v", 1).put("r", req).put("t", Store.str("friendReply")).put("k", Me.publicKey()).put("s", sig)
        return "dandan-req:" + Friend.enc(o.toString().toByteArray(Charsets.UTF_8))
    }

    fun policyPayload(q: String, topic: String): String {
        val sig = Me.sign(q) ?: return ""
        val o = JSONObject().put("v", 1).put("q", q).put("t", topic).put("k", Me.publicKey()).put("s", sig)
        return "dandan-pol:" + Friend.enc(o.toString().toByteArray(Charsets.UTF_8))
    }
}

/** 친구가 앱으로 보낸, 내가 승인할 요청. kind: "r"(일반 요청) 또는 "q"(바보폰 자유 시간 정하기) */
data class InItem(val kind: String, val body: String, val t: String, val from: String, val pk: String, val at: Long) {
    private val p get() = body.split("|")
    val reason: String get() = if (kind == "r") p.getOrNull(7) ?: "" else p.getOrNull(5) ?: ""
    val exp: Long get() = if (kind == "r") p.getOrNull(5)?.toLongOrNull() ?: 0L else (at / 1000 + Friend.REQUEST_TTL)
    val need: Int get() = if (kind == "r") (p.getOrNull(8)?.toIntOrNull() ?: 1) else 1
    val title: String get() = if (kind == "r") "$from: ${Friend.summary(body)}" else "$from: 바보폰 자유 시간을 정해 달래요"
}

/** 받은 요청함. 백그라운드에서 내 주소를 확인하고, 새 요청이 오면 알림을 띄운다. */
object Inbox {
    private var last = 0L
    @Volatile private var busy = false
    @Volatile var fastUntil = 0L   // 친구 탭이 열려 있으면 더 자주 확인한다

    fun items(): List<InItem> = try {
        val a = JSONArray(Store.str("inbox").ifBlank { "[]" })
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            InItem(o.optString("k"), o.optString("b"), o.optString("t"), o.optString("f"), o.optString("p"), o.optLong("a"))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(l: List<InItem>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("k", it.kind).put("b", it.body).put("t", it.t).put("f", it.from).put("p", it.pk).put("a", it.at)) }
        Store.put("inbox", a.toString())
    }

    fun count(): Int = items().size

    private fun seen(): List<String> = Store.str("inboxSeen").split(",").filter { it.isNotBlank() }
    private fun markSeen(t: String) {
        val l = (seen() + t).distinct().takeLast(150)
        Store.put("inboxSeen", l.joinToString(","))
    }

    fun remove(it: InItem) {
        markSeen(it.t)
        save(items().filter { x -> x.t != it.t })
    }

    /** GuardService가 1초마다 부른다. 30초(친구 탭이 열려 있으면 8초)마다 확인한다. */
    fun tick(ctx: Context) {
        if (!Friend.list().any { it.app }) return
        val el = SystemClock.elapsedRealtime()
        val gap = if (el < fastUntil) 8_000 else 30_000
        if (busy || el - last < gap) return
        last = el
        busy = true
        val app = ctx.applicationContext
        val box = Me.inbox()
        val check = items().take(3).map { it.t }
        Thread {
            val msgs = Relay.fetch(box)
            // 이미 다른 친구가 처리했거나 요청자가 닫은 요청은 목록에서 치운다
            val done = check.filter { t -> Relay.fetch(t).any { it == "DONE" } }
            Handler(Looper.getMainLooper()).post {
                busy = false
                handle(app, msgs, done)
            }
        }.start()
    }

    private fun handle(ctx: Context, msgs: List<String>, done: List<String>) {
        val now = System.currentTimeMillis()
        var cur = items().filter { it.t !in done && it.exp > now / 1000 }
        val seen = seen().toMutableSet()
        val fresh = ArrayList<InItem>()
        for (m in msgs) {
            val kind = when {
                m.startsWith("dandan-req:") -> "r"
                m.startsWith("dandan-pol:") -> "q"
                else -> continue
            }
            try {
                val o = JSONObject(String(Friend.dec(m.substringAfter(':')), Charsets.UTF_8))
                val body = o.optString(if (kind == "r") "r" else "q")
                val t = o.optString("t")
                val k = o.optString("k")
                if (!Relay.valid(t) || t in seen || cur.any { it.t == t }) continue
                val f = Friend.list().firstOrNull { it.pk == k } ?: continue   // 친구가 아닌 사람의 요청은 버린다
                if (!Friend.verifyRaw(k, body, o.optString("s"))) continue
                val item = InItem(kind, body, t, f.name, k, now)
                if (item.exp <= now / 1000) { seen.add(t); continue }
                fresh.add(item)
                seen.add(t)
            } catch (e: Exception) { }
        }
        cur = cur + fresh
        save(cur)
        fresh.forEach { notify(ctx, it) }
    }

    private fun notify(ctx: Context, it: InItem) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("inbox", "친구 요청", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(
                ctx, 7,
                Intent(ctx, MainActivity::class.java).putExtra("tab", MAIN_TAB_FRIEND)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(ctx, "inbox")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("단단 · ${it.from}의 요청")
                .setContentText(it.title.substringAfter(": ") + if (it.reason.isNotBlank()) " — ${it.reason}" else "")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(it.t.hashCode(), n)
        } catch (e: Exception) { }
    }

    /** 일반 요청 승인: 내 열쇠로 서명해 요청자의 답장 주소로 보낸다. */
    fun approve(it: InItem): Boolean {
        val sig = Me.sign(it.body) ?: return false
        Relay.postAsync(it.t, sig)
        remove(it)
        return true
    }

    /** 바보폰 자유 시간 정하기 승인: 정한 시간대와 기간을 서명해서 보낸다. */
    fun approvePolicy(it: InItem, wins: List<FreeWin>, days: Int): Boolean {
        val p = it.body.split("|")
        if (p.size < 3) return false
        val ver = System.currentTimeMillis() / 1000
        val policy = listOf("p1", p[1], ver.toString(), (ver + days * 86400L).toString(), Dumb.encode(wins), p[2]).joinToString("|")
        val sig = Me.sign(policy) ?: return false
        Relay.postAsync(it.t, Friend.enc(policy.toByteArray(Charsets.UTF_8)) + "." + sig)
        remove(it)
        return true
    }
}

const val MAIN_TAB_FRIEND = 4

/** 받은 요청 목록. 친구 탭, 바보폰 홈, 잠금 화면에서 같이 쓴다. dark=true면 검은 배경용 색. */
@Composable
fun InboxList(dark: Boolean = false) {
    val ctx = LocalContext.current
    Store.rev.intValue
    val items = Inbox.items()
    var confirm by remember { mutableStateOf<InItem?>(null) }
    var policy by remember { mutableStateOf<InItem?>(null) }
    val fg = if (dark) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface
    val sub = if (dark) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant
    LaunchedEffect(Unit) {
        while (true) { Inbox.fastUntil = SystemClock.elapsedRealtime() + 15_000; delay(5000) }
    }
    if (items.isEmpty()) {
        Text("받은 요청이 없어요", color = sub, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
    }
    items.forEach { item ->
        Column(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                .background(if (dark) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp)
        ) {
            Text(item.title, color = fg, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("사유: " + item.reason.ifBlank { "(없음)" }, color = sub, fontSize = 13.sp)
            if (item.need > 1) Text("친구 ${item.need}명이 동의해야 풀리는 요청이에요", color = sub, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (item.kind == "r") confirm = item else policy = item }) { Text(if (item.kind == "r") "승인" else "시간 정하기") }
                OutlinedButton(onClick = { Inbox.remove(item) }) { Text("거절") }
            }
        }
    }
    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("정말 승인할까요?") },
            text = { Text("${c.title}\n사유: ${c.reason.ifBlank { "(없음)" }}\n\n승인하면 ${c.from}의 잠금이 풀려요.") },
            confirmButton = {
                Button(onClick = {
                    toast(ctx, if (Inbox.approve(c)) "승인했어요" else "서명에 실패했어요")
                    confirm = null
                }) { Text("승인") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("다시 생각할게요") } }
        )
    }
    policy?.let { q -> PolicyApproveDialog(q) { policy = null } }
}

private val PRESETS = listOf(
    Triple("평일 저녁 1시간", FreeWin(19 * 60, 20 * 60, 31), 0),
    Triple("매일 저녁 1시간", FreeWin(20 * 60, 21 * 60, 127), 0),
    Triple("주말 낮 2시간", FreeWin(13 * 60, 15 * 60, 96), 0),
    Triple("매일 점심 30분", FreeWin(12 * 60, 12 * 60 + 30, 127), 0)
)

/** 친구의 바보폰 자유 시간을 앱에서 정해 주는 창. 지금 시간대를 보여 주고, 빼거나 더해서 승인한다. */
@Composable
private fun PolicyApproveDialog(item: InItem, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val wins = remember { mutableStateListOf<FreeWin>().also { it.addAll(Dumb.parse(item.body.split("|").getOrNull(3) ?: "")) } }
    var days by remember { mutableIntStateOf(7) }
    val dayNames = listOf("월", "화", "수", "목", "금", "토", "일")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${item.from}의 자유 시간") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("사유: " + item.reason.ifBlank { "(없음)" }, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text("이 시간에만 바보폰에서 벗어나요. 나머지는 하루 종일 바보폰이에요.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (wins.isEmpty()) Text("자유 시간 없음", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                wins.toList().forEachIndexed { i, w ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        val d = (0..6).filter { (w.days shr it) and 1 == 1 }.joinToString("") { dayNames[it] }
                        Text("${Fmt.clock(w.s)}–${Fmt.clock(w.e)} · $d", modifier = Modifier.weight(1f))
                        TextButton(onClick = { wins.removeAt(i) }) { Text("빼기") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("빠르게 추가", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                PRESETS.forEach { (label, w, _) ->
                    Text(
                        "+ $label", color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { if (wins.size < 8) wins.add(w) }.padding(vertical = 6.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text("유효 기간", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1, 3, 7, 14, 30).forEach { d ->
                        val on = d == days
                        Text(
                            "${d}일",
                            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { days = d }.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                toast(ctx, if (Inbox.approvePolicy(item, wins.toList(), days)) "정해 줬어요" else "서명에 실패했어요")
                onDismiss()
            }) { Text("이대로 승인") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}

/** 바보폰 홈·잠금 화면에서 받은 요청을 여는 창 */
@Composable
fun InboxDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("받은 요청") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { InboxList() } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}

/** 단단 앱끼리 연결: 내 연결 코드를 보내고, 상대가 수락하면 자동으로 서로 친구가 된다. */
@Composable
fun PairOfferDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val reply = remember { Relay.newTopic() }
    var me by remember { mutableStateOf(Store.str("myName")) }
    LaunchedEffect(Unit) {
        while (true) {
            val msgs: List<String> = withContext(Dispatchers.IO) { Relay.fetch(reply) }
            for (m in msgs) {
                val c = Link.parse(m) ?: continue
                if (Link.add(c)) {
                    toast(ctx, "${c.name}와(과) 연결됐어요")
                    onDismiss()
                    return@LaunchedEffect
                }
            }
            delay(5000)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("단단 앱끼리 연결") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("친구도 단단을 쓰고 있을 때 써요. 연결하면 서로의 요청이 앱 안으로 바로 가고, 앱에서 승인만 누르면 돼요.", fontSize = 13.sp)
                OutlinedTextField(me, { me = it.take(20) }, label = { Text("내 이름 (친구에게 보여요)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("1. 연결 코드를 친구에게 보내세요.", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        Store.put("myName", me.trim().ifBlank { "나" })
                        kakaoText(ctx, Link.offerText(reply))
                    }) { Text("카카오톡") }
                    OutlinedButton(onClick = {
                        Store.put("myName", me.trim().ifBlank { "나" })
                        shareText(ctx, Link.offerText(reply))
                    }) { Text("다른 앱") }
                    OutlinedButton(onClick = {
                        Store.put("myName", me.trim().ifBlank { "나" })
                        copyText(ctx, Link.offerText(reply)); toast(ctx, "복사했어요")
                    }) { Text("복사") }
                }
                Spacer(Modifier.height(8.dp))
                Text("2. 친구가 단단에서 수락하면 자동으로 연결돼요. 이 창을 열어 두세요.", fontSize = 13.sp)
                if (!Link.ready()) Text("이 폰에서 열쇠를 만들 수 없어요.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}

/** 받은 연결 코드를 붙여넣거나(코드로 연결), 링크로 열렸을 때 수락하는 창 */
@Composable
fun PairAcceptDialog(initial: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(initial) }
    val card = Link.parse(text)
    val gate = rememberScopeGate("friend_add")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("코드로 연결") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (initial.isBlank() || card == null) {
                    OutlinedTextField(text, { text = it }, label = { Text("받은 연결 코드") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                    TextButton(onClick = { text = readClipboard(ctx) }) { Text("붙여넣기") }
                }
                when {
                    card == null && text.isNotBlank() -> Text("연결 코드를 읽을 수 없어요.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    card != null && Link.isMe(card) -> Text("내가 만든 코드예요. 친구에게 보내 주세요.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    card != null -> Text(
                        "'${card.name}'와(과) 단단 앱끼리 연결할까요?\n서로의 잠금 해제 요청을 승인해 줄 수 있게 돼요." +
                            if (Friend.registered()) "\n\n이미 친구가 있어서, 친구를 더하려면 지금 친구의 승인이 필요해요." else "",
                        fontSize = 14.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(enabled = card != null && !Link.isMe(card), onClick = {
                val c = card ?: return@Button
                gate.run("친구 추가: ${c.name} (앱 연결)") {
                    if (Link.accept(c)) { toast(ctx, "${c.name}와(과) 연결됐어요"); onDismiss() }
                    else toast(ctx, if (Friend.count() >= Friend.MAX_FRIENDS) "친구는 ${Friend.MAX_FRIENDS}명까지예요" else "연결하지 못했어요")
                }
            }) { Text("연결") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
    gate.Dialog()
}

/** 친구 탭: 받은 요청(앱 경로), 친구 목록, 연결 방법 두 가지(앱끼리 / 앱 밖 링크). */
@Composable
fun FriendTab() {
    val ctx = LocalContext.current
    Store.rev.intValue
    var offer by remember { mutableStateOf(false) }
    var paste by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf(false) }
    var notifyDlg by remember { mutableStateOf(false) }
    val gate = rememberScopeGate("friend_add")
    val pending = Store.str("pairPending")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text("친구", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
        Text(
            "친구와 서로 잠금 해제를 승인해 줘요. 단단끼리 연결한 친구는 앱 안에서, 앱이 없는 친구는 링크로 승인해요. 어느 쪽으로 승인해도 똑같이 풀려요.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))
        Text("받은 요청 (${Inbox.count()})", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        if (!Friend.list().any { it.app }) {
            Text("단단끼리 연결한 친구가 생기면 친구의 요청이 여기로 와요.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            InboxList()
            if (!GuardService.enabled(ctx)) {
                Text("접근성이 꺼져 있으면 단단이 꺼져 있을 때 새 요청 알림이 안 와요.", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("내 친구 ${Friend.count()}/${Friend.MAX_FRIENDS}", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        if (!Friend.registered()) Text("아직 친구가 없어요.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Friend.list().forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, fontWeight = FontWeight.Bold)
                    Text(
                        if (f.app) "단단 앱 연결 · 서로 승인" else "링크로 승인 (앱 밖)",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { gate.run("친구 끊기: ${f.name}") { Friend.remove(f.pk) } }) { Text("끊기") }
            }
        }
        if (Friend.count() >= 2) {
            Text(
                "승인에 필요한 친구 수: ${Friend.quorum()}명 (설정 → 친구 해제에서 바꿔요)",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(20.dp))
        Text("친구 추가", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Button(onClick = { gate.run("친구 추가 (앱 연결)") { offer = true } }, modifier = Modifier.fillMaxWidth()) { Text("단단 앱끼리 연결하기") }
        OutlinedButton(onClick = { paste = true }, modifier = Modifier.fillMaxWidth()) { Text("받은 연결 코드로 연결") }
        OutlinedButton(onClick = { gate.run("친구 추가 (링크)") { web = true } }, modifier = Modifier.fillMaxWidth()) { Text("앱 없는 친구 — 링크로 초대") }
        Text(
            "친구가 있으면 친구를 더하거나 끊을 때 지금 친구의 승인이 필요해요.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (Friend.registered()) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { notifyDlg = true }, modifier = Modifier.fillMaxWidth()) { Text("앱 밖 알림 방법 (문자·푸시)") }
            Text(
                "어떤 상황에서 친구 승인이 필요한지는 설정 → 친구 해제에서 정해요.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(40.dp))
    }

    if (offer) PairOfferDialog { offer = false }
    if (paste || pending.isNotBlank()) {
        PairAcceptDialog(pending) { paste = false; Store.put("pairPending", "") }
    }
    if (web) FriendSetupDialog { web = false }
    if (notifyDlg) NotifyDialog { notifyDlg = false }
    gate.Dialog()
}
