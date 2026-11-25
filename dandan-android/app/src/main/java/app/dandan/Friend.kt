package app.dandan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
        Triple("sched_edit", "예약 수정·삭제·끄기", "예약 잠금을 고치거나 지우거나 끄려면 언제든 친구 승인이 필요해요. (새로 추가·켜기는 그냥 돼요)"),
        Triple("promise_edit", "약속·숏폼 차단 수정·끄기", "약속을 고치거나 지울 때, 유튜브 쇼츠·인스타 차단을 끌 때 친구 승인이 필요해요. (새 약속 추가는 그냥 돼요)"),
        Triple("list_edit", "허용 앱·잠글 앱 목록 바꾸기", "잠금 중에 쓸 허용 앱과 잠글 앱 목록을 바꾸려면 친구 승인이 필요해요."),
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
    fun policyQ(reason: String = ""): String = listOf(
        "q1", deviceId(), clean(myName()), Store.str("dumbWins"), Store.long("dumbExp").toString(),
        cleanReason(reason)
    ).joinToString("|")

    fun policyLink(reason: String = "", topic: String = ""): String {
        val q = policyQ(reason)
        return base() + "#q=" + enc(q.toByteArray(Charsets.UTF_8)) + if (topic.isNotBlank()) "&t=$topic" else ""
    }

    /** 친구가 만든 정책 코드("정책.서명")를 검증해 적용한다. 성공하면 null, 실패하면 이유를 돌려준다. */
    fun applyPolicy(code: String, topic: String = ""): String? {
        val parts = code.trim().split(".")
        if (parts.size != 2) return "코드 형식이 올바르지 않아요."
        val policy = try { String(dec(parts[0]), Charsets.UTF_8) } catch (e: Exception) { return "코드를 읽을 수 없어요." }
        if (signer(policy, parts[1]) == null) return "친구가 만든 코드가 아니에요."
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
        if (topic.isNotBlank() && Store.str("policyTopic") == topic) Store.put("policyTopic", "")
        Relay.postAsync(topic, "DONE")
        return null
    }

    fun base(): String = Store.str("friendBase", BASE_DEFAULT)
    /** 등록된 친구. 열쇠(pk)로 구분하고, 알림 받을 번호·주제도 친구마다 따로 둔다. */
    data class Pal(val name: String, val pk: String, val phone: String = "", val topic: String = "", val inbox: String = "") {
        /** 단단 앱끼리 연결된 친구(앱 안으로 요청이 간다) */
        val app: Boolean get() = inbox.isNotBlank()
    }

    const val MAX_FRIENDS = 5

    /** 요청이 유효한 시간(초). 친구가 바로 못 볼 수도 있어서 하루로 둔다. */
    const val REQUEST_TTL = 24 * 3600L
    private var palRaw = "\u0000"
    private var palCache: List<Pal> = emptyList()

    fun list(): List<Pal> {
        var raw = Store.str("friends")
        if (raw.isBlank() && Store.str("friendPk").isNotBlank()) {
            // 예전 버전(친구 한 명) 정보를 목록으로 옮긴다
            val one = Pal(
                Store.str("friendName", "친구"), Store.str("friendPk"),
                Store.str("friendPhone"), Store.str("friendTopic")
            )
            saveList(listOf(one))
            Store.put("friendPk", "")
            raw = Store.str("friends")
        }
        if (raw == palRaw) return palCache
        val out = ArrayList<Pal>()
        try {
            val a = JSONArray(raw.ifBlank { "[]" })
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Pal(o.optString("n", "친구"), o.optString("k"), o.optString("p"), o.optString("t"), o.optString("i")))
            }
        } catch (e: Exception) { }
        palRaw = raw
        palCache = out.filter { it.pk.isNotBlank() }
        return palCache
    }

    fun saveList(l: List<Pal>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("n", it.name).put("k", it.pk).put("p", it.phone).put("t", it.topic).put("i", it.inbox)) }
        Store.put("friends", a.toString())
    }

    fun count(): Int = list().size
    fun registered(): Boolean = list().isNotEmpty()

    /** 화면에 쓰는 이름: 한 명이면 이름, 두 명이면 "A·B", 그 이상이면 "친구 N명". */
    fun name(): String {
        val l = list()
        return when {
            l.isEmpty() -> "친구"
            l.size == 1 -> l[0].name
            l.size == 2 -> l[0].name + "·" + l[1].name
            else -> "친구 ${l.size}명"
        }
    }

    /** 서명이 등록된 친구 중 누구의 것인지 찾는다. 아무도 아니면 null. */
    fun signer(message: String, sigB64: String): Pal? = list().firstOrNull { verifyRaw(it.pk, message, sigB64) }
    fun myName(): String = Store.str("myName", "나")
    fun scopeOn(s: String): Boolean = registered() && Store.bool("fs_$s")

    /** 이 옵션이 꺼져 있거나, 친구가 최근(10분 안에) 승인해 줬으면 true. 바꿔도 되는 상태다. */
    fun passed(scope: String): Boolean {
        val pass = Store.long("scopePass_$scope") > System.currentTimeMillis()
        // 친구 추가·끊기는 옵션과 상관없이, 이미 친구가 있으면 항상 승인이 필요하다
        if (scope == "friend_add") return !registered() || pass
        return !scopeOn(scope) || pass
    }

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
    fun newRequest(kind: String, label: String, param: String, reason: String, effect: String = ""): String {
        val now = System.currentTimeMillis() / 1000
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val req = listOf(
            "d1", kind, clean(label), param, enc(nonce), (now + REQUEST_TTL).toString(),
            clean(myName()), cleanReason(reason), quorum().toString()
        ).joinToString("|")
        // 이전 요청이 아직 열려 있었다면 그 링크는 더 못 쓰게 닫는다
        if (Store.str("friendPending").isNotBlank()) Relay.postAsync(Store.str("friendReply"), "DONE")
        Store.put("friendPending", req)
        Store.put("friendAppr", "")
        Store.put("friendEffect", effect)
        Store.put("friendReply", Relay.newTopic())
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

    /** 요청 링크. 뒤에 붙는 t는 친구가 승인하면 답을 돌려보낼 임시 주소다. */
    fun link(req: String): String {
        val t = Store.str("friendReply")
        return base() + "#r=" + enc(req.toByteArray(Charsets.UTF_8)) + if (t.isNotBlank()) "&t=$t" else ""
    }

    fun inviteLink(topic: String = ""): String =
        base() + "#hello=1&from=" + URLEncoder.encode(myName(), "UTF-8") + if (topic.isNotBlank()) "&t=$topic" else ""

    fun requestMessage(req: String): String =
        "[단단] ${myName()}의 요청\n${summary(req)}\n사유: ${reasonOf(req).ifBlank { "(없음)" }}\n\n" +
            "승인하려면 링크를 열어 주세요 ↓\n${link(req)}\n\n(승인하면 나오는 코드를 나한테 보내 줘)"

    /** 친구를 추가한다. 같은 열쇠가 이미 있으면 이름만 바꾸고, 다섯 명이 넘으면 거절한다. */
    fun register(name: String, pkCode: String): Boolean = try {
        val bytes = dec(pkCode)
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        val pk = enc(bytes)
        val nm = clean(name).ifBlank { "친구" }
        val cur = list()
        val same = cur.indexOfFirst { it.pk == pk }
        if (same >= 0) {
            saveList(cur.mapIndexed { i, f -> if (i == same) f.copy(name = nm) else f })
            true
        } else if (cur.size >= MAX_FRIENDS) {
            false
        } else {
            saveList(cur + Pal(nm, pk))
            true
        }
    } catch (e: Exception) { false }

    /** 알림 번호·주제를 저장한다(열쇠로 맞춰서 갱신). */
    fun updateContacts(rows: List<Pal>) {
        saveList(list().map { f -> rows.firstOrNull { it.pk == f.pk }?.copy(name = f.name, inbox = f.inbox) ?: f })
    }

    /** 단단 앱끼리 연결된 친구의 받은 요청함 주소를 저장한다. */
    fun setInbox(pk: String, inbox: String) {
        saveList(list().map { if (it.pk == pk) it.copy(inbox = inbox) else it })
    }

    fun remove(pk: String) {
        val left = list().filter { it.pk != pk }
        saveList(left)
        if (left.isEmpty()) clearAll()
    }

    fun unregister() { saveList(emptyList()); clearAll() }

    private fun clearAll() {
        Store.put("friendPk", "")
        Store.put("friendPending", "")
        SCOPES.forEach { Store.put("fs_${it.first}", false) }
    }

    /** 지금 걸어 둔 요청을 취소한다. 친구들이 받은 링크도 '처리됨'으로 닫힌다. */
    fun cancel() {
        if (Store.str("friendPending").isNotBlank()) Relay.postAsync(Store.str("friendReply"), "DONE")
        Store.put("friendPending", "")
        Store.put("friendEffect", "")
        Store.put("friendAppr", "")
    }

    /** 승인에 필요한 서로 다른 친구 수. 설정값이지만 등록된 친구 수를 넘을 수는 없다. */
    fun quorum(): Int = Store.int("quorum", 1).coerceIn(1, maxOf(1, count()))

    /** 이 요청에 필요한 동의 수. 요청에 서명돼 실린 값이라 나중에 바꿀 수 없다(친구 수가 줄었으면 그만큼만). */
    fun need(req: String): Int =
        (req.split("|").getOrNull(8)?.toIntOrNull() ?: 1).coerceIn(1, maxOf(1, count()))

    /** 지금 요청에 동의한(아직 등록된) 친구 수 */
    fun have(): Int {
        val keys = list().map { it.pk }.toSet()
        return Store.str("friendAppr").split(",").filter { it.isNotBlank() && it in keys }.toSet().size
    }

    /**
     * 승인되면 저절로 적용되는 일. 요청 창을 닫아도 [Approvals]가 승인을 받아 이걸 실행한다.
     * 형식: "종류:값:값". 모르는 종류는 무시한다.
     */
    fun applyEffect(ctx: Context, effect: String) {
        val p = effect.split(":")
        try {
            when (p[0]) {
                "extra" -> {
                    val pkg = p[1]
                    val minutes = p[2].toInt()
                    val pr = Store.promises().firstOrNull { it.pkg == pkg }
                    if (pr != null) {
                        val k = "bonus_${Store.dayKey()}_$pkg"
                        Store.put(k, Store.int(k) + if (pr.unit == "time") minutes else 1)
                    }
                    Store.put("grant_$pkg", SystemClock.elapsedRealtime() + minutes * 60_000L)
                    Store.addOpen(pkg)
                }
                "unlock" -> Store.put("pass_${p[1]}", SystemClock.elapsedRealtime() + p[2].toInt() * 60_000L)
                "phone" -> Engine.releaseByFriend(ctx)
                "sched" -> Store.put("schedPass_${p[1]}", System.currentTimeMillis() + 10 * 60_000L)
                "relax" -> Store.put("relaxPass", System.currentTimeMillis() + 10 * 60_000L)
                "scope" -> Store.put("scopePass_${p[1]}", System.currentTimeMillis() + 10 * 60_000L)
                "dumbfree" -> Dumb.startSession(ctx, p[1].toInt() * 60_000L)
                "dumboff" -> { Store.dumbOn = false; Store.put("dumbEndW", 0L) }
            }
        } catch (e: Exception) { }
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

    /** 마지막으로 승인한 친구의 이름(안내 문구용) */
    var lastApprover: String = ""
        private set

    /**
     * 승인 코드가 지금 걸어 둔 요청에 대한 등록된 친구 중 누군가의 서명이면 true.
     * 한 번 쓰면 요청은 폐기되고, 같은 요청을 받은 다른 친구들의 링크도 '처리됨'으로 닫힌다.
     */
    fun verify(code: String): Boolean {
        val pending = Store.str("friendPending")
        if (pending.isBlank()) return false
        val p = pending.split("|")
        val exp = p.getOrNull(5)?.toLongOrNull() ?: return false
        if (System.currentTimeMillis() / 1000 > exp) return false
        val who = signer(pending, code) ?: return false
        lastApprover = who.name
        // 서로 다른 친구의 동의를 모은다. 같은 친구가 여러 번 보내도 한 번으로 센다.
        val got = Store.str("friendAppr").split(",").filter { it.isNotBlank() }.toMutableList()
        if (who.pk !in got) { got.add(who.pk); Store.put("friendAppr", got.joinToString(",")) }
        if (have() < need(pending)) return false
        Store.put("friendPending", "")
        Store.put("friendEffect", "")
        Store.put("friendAppr", "")
        Relay.postAsync(Store.str("friendReply"), "DONE")
        return true
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
 * 친구에게 요청을 보내고, 친구의 승인을 기다리는 창.
 * 먼저 사유를 적어야 요청이 만들어진다. 사유는 서명 대상에 들어가서 친구에게 그대로 보인다.
 * 친구가 링크에서 '승인'을 누르면 승인 코드가 임시 주소(ntfy)로 오고, 이 창이 자동으로 받아 적용한다.
 * 자동 전달이 안 될 때를 위해 코드 직접 입력도 접어서 남겨 둔다.
 * allowShare=false면 공유 버튼을 숨긴다(폰 잠금 중).
 */
@Composable
fun FriendRequestDialog(
    kind: String,
    label: String,
    param: String,
    allowShare: Boolean = true,
    effect: String = "",
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
    var manual by remember { mutableStateOf(false) }
    var waitNote by remember { mutableStateOf("친구의 승인을 기다리는 중…") }
    var tickProgress by remember { mutableIntStateOf(0) }
    val r = req

    LaunchedEffect(r) {
        if (r == null) return@LaunchedEffect
        val exp = r.split("|").getOrNull(5)?.toLongOrNull() ?: 0L
        while (true) {
            val cur = Store.str("friendPending")
            if (cur.isBlank()) {
                // 백그라운드에서 이미 승인돼 적용됐다
                onApproved(); onDismiss(); return@LaunchedEffect
            }
            if (cur != r) { onDismiss(); return@LaunchedEffect }
            if (System.currentTimeMillis() / 1000 > exp) {
                waitNote = "요청 시간이 지났어요. 아래 '사유 고쳐서 다시 만들기'를 눌러 주세요."
                return@LaunchedEffect
            }
            Approvals.uiTs = SystemClock.elapsedRealtime()
            val topic = Store.str("friendReply")
            val msgs: List<String> = if (topic.isBlank()) emptyList() else withContext(Dispatchers.IO) { Relay.fetch(topic) }
            for (m in msgs) {
                if (Friend.verify(m)) {
                    if (effect.isNotBlank()) Friend.applyEffect(ctx, effect)
                    if (Friend.count() > 1) toast(ctx, "${Friend.lastApprover}이(가) 승인했어요")
                    onApproved(); onDismiss(); return@LaunchedEffect
                }
            }
            tickProgress++
            delay(6000)
        }
    }

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
                            val nr = Friend.newRequest(kind, label, param, reason, effect)
                            req = nr
                            waitNote = "친구의 승인을 기다리는 중…"
                            Notify.dispatch(ctx, Friend.headline(nr), "사유: " + Friend.reasonOf(nr), Friend.link(nr), Link.requestPayload(nr)) { sent = it }
                        }
                    ) { Text("요청 만들기") }
                    Text(
                        if (Notify.any()) "만들면 ${Friend.name()}에게 자동으로 알림이 가요."
                        else "자동 알림이 꺼져 있어요. 설정 → 친구 해제 → 알림 방법에서 켜면 자동으로 가요.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (Friend.count() > 1) Text(
                        "친구가 여럿이라 모두에게 같은 링크가 가요. 한 명이 승인하면 나머지 링크는 '이미 처리됨'으로 닫혀요.",
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
                            Notify.dispatch(ctx, Friend.headline(r), "사유: " + Friend.reasonOf(r), Friend.link(r), Link.requestPayload(r)) { sent = it }
                        }) { Text("알림 다시 보내기") }
                    }
                    if (!allowShare) {
                        Text(
                            "잠금 중에는 허용 앱(예: 카카오톡)에서 붙여넣어 보낼 수 있어요.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        TextButton(onClick = { req = null; code = ""; err = ""; sent = ""; manual = false }) { Text("사유 고쳐서 다시 만들기") }
                        TextButton(onClick = { Friend.cancel(); onDismiss() }) { Text("요청 취소") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (effect.isNotBlank()) "3. 친구가 링크에서 '승인'을 누르면 자동으로 적용돼요. 이 창은 닫아도 돼요."
                        else "3. 친구가 링크에서 '승인'을 누르면 자동으로 적용돼요. 이 창을 열어 두세요.",
                        fontSize = 13.sp
                    )
                    Text(waitNote, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                    tickProgress
                    if (Friend.need(r) > 1) {
                        Text(
                            "친구 ${Friend.need(r)}명이 동의해야 해요 · 지금 ${Friend.have()}명",
                            fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    TextButton(onClick = { manual = !manual }) {
                        Text(if (manual) "코드 직접 입력 접기" else "자동으로 안 되면 코드 직접 입력")
                    }
                    if (manual) {
                        OutlinedTextField(
                            value = code, onValueChange = { code = it; err = "" },
                            modifier = Modifier.fillMaxWidth(), maxLines = 4
                        )
                        TextButton(onClick = { code = readClipboard(ctx) }) { Text("붙여넣기") }
                    }
                    if (err.isNotBlank()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    Text(
                        "요청은 24시간 동안 유효해요. 이 창을 닫아도 승인되면 단단이 알아서 적용해요.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (r != null && manual) {
                Button(
                    enabled = code.isNotBlank(),
                    onClick = {
                        if (Friend.verify(code)) {
                            if (effect.isNotBlank()) Friend.applyEffect(ctx, effect)
                            onApproved(); onDismiss()
                        }
                        else err = if (Friend.have() > 0) "동의 ${Friend.have()}/${Friend.need(r)}명 — 다른 친구의 코드도 필요해요." else "코드가 맞지 않거나 만료됐어요."
                    }
                ) { Text("확인") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
}

/**
 * "친구 승인이 있어야 바꿀 수 있는" 변경을 한곳에서 처리한다.
 * gate.run("무엇을") { 바꾸는 일 } — 옵션이 꺼져 있거나 최근 승인받았으면 바로 하고, 아니면 요청 창을 띄운다.
 * 화면 끝에서 gate.Dialog()를 한 번 불러 줘야 한다.
 */
class ScopeGate(val scope: String) {
    var pending by mutableStateOf<Pair<String, () -> Unit>?>(null)

    fun run(label: String, then: () -> Unit) {
        if (Friend.passed(scope)) then() else pending = Pair(label, then)
    }

    @Composable
    fun Dialog() {
        val p = pending ?: return
        FriendRequestDialog(
            kind = "cfg_change", label = p.first, param = "0", effect = "scope:$scope",
            onDismiss = { pending = null },
            onApproved = { pending = null; p.second() }
        )
    }
}

@Composable
fun rememberScopeGate(scope: String): ScopeGate = remember(scope) { ScopeGate(scope) }

/** 친구 등록 창: 내 이름, 초대 링크 보내기, 친구가 준 연결 코드 붙여넣기. */
@Composable
fun FriendSetupDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var me by remember { mutableStateOf(Store.str("myName")) }
    var friend by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    val topic = remember { Relay.newTopic() }

    // 친구가 링크에서 '친구 되어 주기'를 누르면 연결 코드가 임시 주소로 오고, 여기서 받아 자동으로 등록한다
    LaunchedEffect(Unit) {
        while (true) {
            val msgs: List<String> = withContext(Dispatchers.IO) { Relay.fetch(topic) }
            for (m in msgs) {
                if (Friend.register(friend.ifBlank { "친구" }, m)) {
                    Store.put("myName", me.trim().ifBlank { "나" })
                    toast(ctx, "친구를 등록했어요")
                    onDismiss()
                    return@LaunchedEffect
                }
            }
            delay(5000)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (Friend.registered()) "친구 추가 (${Friend.count()}/${Friend.MAX_FRIENDS})" else "친구 등록") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("1. 이름을 정하고 친구에게 초대 링크를 보내세요.", fontSize = 13.sp)
                OutlinedTextField(me, { me = it }, label = { Text("내 이름 (친구에게 보여요)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(friend, { friend = it }, label = { Text("친구 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        Store.put("myName", me.trim().ifBlank { "나" })
                        copyText(ctx, "[단단] ${Friend.myName()}이(가) 너를 친구로 지정하고 싶대. 링크를 열어서 '친구 되어 주기'만 눌러 줘.\n${Friend.inviteLink(topic)}")
                        toast(ctx, "복사했어요")
                    }) { Text("링크 복사") }
                    OutlinedButton(onClick = {
                        Store.put("myName", me.trim().ifBlank { "나" })
                        shareText(ctx, "[단단] ${Friend.myName()}이(가) 너를 친구로 지정하고 싶대. 링크를 열어서 '친구 되어 주기'만 눌러 줘.\n${Friend.inviteLink(topic)}")
                    }) { Text("공유하기") }
                }
                Spacer(Modifier.height(12.dp))
                Text("2. 친구가 링크에서 '친구 되어 주기'를 누르면 자동으로 등록돼요. 친구 이름을 먼저 적어 두세요. 이 창을 열어 두세요.", fontSize = 13.sp)
                TextButton(onClick = { manual = !manual }) { Text(if (manual) "코드 직접 입력 접기" else "자동으로 안 되면 코드 직접 입력") }
                if (manual) {
                    OutlinedTextField(code, { code = it; err = "" }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                    TextButton(onClick = { code = readClipboard(ctx) }) { Text("붙여넣기") }
                }
                if (err.isNotBlank()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "친구는 크롬이나 삼성 인터넷 같은 일반 브라우저로 링크를 열어야 해요. 카카오톡 안에서 열었다면 '다른 브라우저로 열기'를 쓰세요.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            if (manual) Button(
                enabled = code.isNotBlank() && friend.isNotBlank(),
                onClick = {
                    Store.put("myName", me.trim().ifBlank { "나" })
                    if (Friend.register(friend, code)) { toast(ctx, "친구를 등록했어요"); onDismiss() }
                    else err = if (Friend.count() >= Friend.MAX_FRIENDS) "친구는 ${Friend.MAX_FRIENDS}명까지 등록할 수 있어요." else "연결 코드가 올바르지 않아요."
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
    fun smsOn(): Boolean = Store.bool("notifySms") && Friend.list().any { it.phone.isNotBlank() }
    fun pushOn(): Boolean = Store.bool("notifyPush") && Friend.list().any { it.topic.isNotBlank() }
    fun appOn(): Boolean = Friend.list().any { it.app }
    fun any(): Boolean = smsOn() || pushOn() || appOn()

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
    fun dispatch(ctx: Context, headline: String, detail: String, link: String, app: String = "", onStatus: (String) -> Unit) {
        if (!any()) { onStatus(""); return }
        val lines = ArrayList<String>()
        val main = Handler(Looper.getMainLooper())
        val friends = Friend.list()
        val many = friends.size > 1
        fun publish() {
            val t = synchronized(lines) { lines.joinToString("\n") }
            main.post { onStatus(t) }
        }
        if (Store.bool("notifySms")) {
            friends.filter { it.phone.isNotBlank() }.forEach { f ->
                val ok = sendSms(ctx, f.phone, "[단단] $headline\n$detail\n(아래 링크에서 승인해 주세요)", link)
                val who = if (many) " (${f.name})" else ""
                synchronized(lines) { lines.add(if (ok) "문자 ✓ 보냈어요$who" else "문자 ✗ 못 보냈어요$who (번호·문자 권한 확인)") }
            }
        }
        if (Store.bool("notifyPush")) {
            friends.filter { it.topic.isNotBlank() }.forEach { f ->
                val who = if (many) " (${f.name})" else ""
                val idx = synchronized(lines) { lines.add("푸시 … 보내는 중$who"); lines.size - 1 }
                Thread {
                    val ok = sendPush(f.topic, headline, detail, link)
                    synchronized(lines) { lines[idx] = if (ok) "푸시 ✓ 보냈어요$who" else "푸시 ✗ 못 보냈어요$who (인터넷 확인)" }
                    publish()
                }.start()
            }
        }
        if (app.isNotBlank()) {
            // 단단 앱끼리 연결된 친구: 받은 요청함으로 바로 보낸다
            friends.filter { it.app }.forEach { f ->
                val who = if (many) " (${f.name})" else ""
                val idx = synchronized(lines) { lines.add("앱 … 보내는 중$who"); lines.size - 1 }
                Thread {
                    val ok = Relay.post(f.inbox, app)
                    synchronized(lines) { lines[idx] = if (ok) "앱 ✓ 보냈어요$who" else "앱 ✗ 못 보냈어요$who (인터넷 확인)" }
                    publish()
                }.start()
            }
        }
        publish()
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

/** 친구 알림 방법 설정: 문자 자동 전송, 푸시(ntfy). 친구마다 번호와 주제를 따로 둔다. */
@Composable
fun NotifyDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val rows = remember { mutableStateListOf<Friend.Pal>().also { it.addAll(Friend.list()) } }
    var sms by remember { mutableStateOf(Store.bool("notifySms")) }
    var push by remember { mutableStateOf(Store.bool("notifyPush")) }
    var status by remember { mutableStateOf("") }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) {
            sms = false
            toast(ctx, "문자 권한이 필요해요. 단단 앱 정보에서 ⋮ → '제한된 설정 허용'을 누른 뒤 다시 켜 보세요")
        }
    }
    fun save() {
        Store.put("notifySms", sms)
        Store.put("notifyPush", push)
        Friend.updateContacts(rows.map { it.copy(phone = it.phone.filter { c -> c.isDigit() || c == '+' }, topic = it.topic.trim()) })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("친구 알림 방법") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "요청을 만들면 켜 둔 방법으로 친구 모두에게 자동으로 가요. 둘 다 켜도 돼요.",
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
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("푸시 알림 (ntfy)", fontWeight = FontWeight.Bold)
                        Text(
                            "친구가 무료 앱 ntfy를 깔고 자기 주제를 한 번 구독해야 해요. 내용은 ntfy 서버를 거쳐요.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = push, onCheckedChange = { want ->
                        push = want
                        if (want) for (i in rows.indices) if (rows[i].topic.isBlank()) rows[i] = rows[i].copy(topic = Notify.newTopic())
                    })
                }
                rows.forEachIndexed { i, f ->
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                    Text(f.name, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    OutlinedTextField(
                        f.phone, { rows[i] = f.copy(phone = it) }, label = { Text("${f.name} 전화번호") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        f.topic, { rows[i] = f.copy(topic = it) }, label = { Text("${f.name} 푸시 주제 (비밀번호처럼)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { rows[i] = f.copy(topic = Notify.newTopic()) }) { Text("새 주제") }
                        OutlinedButton(onClick = {
                            val t = f.topic.ifBlank { Notify.newTopic().also { n -> rows[i] = f.copy(topic = n) } }
                            shareText(ctx, Notify.subscribeText(t))
                        }) { Text("구독 방법 보내기") }
                    }
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

/**
 * 요청 창을 닫아도 승인을 받아 적용하는 백그라운드 확인기. GuardService가 1초마다 부른다.
 * 요청 창이 열려 있으면 거기서 직접 받으니 쉬어서 서버에 부담을 주지 않는다.
 */
object Approvals {
    @Volatile var uiTs = 0L
    private var last = 0L
    private var busy = false

    fun tick(ctx: Context) {
        Inbox.tick(ctx)
        val el = SystemClock.elapsedRealtime()
        if (busy || el - last < 10_000 || el - uiTs < 15_000) return
        val wall = System.currentTimeMillis()
        val pending = Store.str("friendPending")
        val exp = pending.split("|").getOrNull(5)?.toLongOrNull() ?: 0L
        val doReq = pending.isNotBlank() && Store.str("friendEffect").isNotBlank() && exp > wall / 1000
        val polTopic = Store.str("policyTopic")
        val doPol = polTopic.isNotBlank() && Store.long("policyExp") > wall / 1000
        if (!doReq && !doPol) return
        last = el
        busy = true
        val app = ctx.applicationContext
        val reqTopic = Store.str("friendReply")
        Thread {
            val a: List<String> = if (doReq) Relay.fetch(reqTopic) else emptyList()
            val b: List<String> = if (doPol) Relay.fetch(polTopic) else emptyList()
            Handler(Looper.getMainLooper()).post {
                busy = false
                for (m in a) {
                    val eff = Store.str("friendEffect")
                    if (eff.isBlank()) break
                    if (Friend.verify(m)) {
                        Friend.applyEffect(app, eff)
                        toast(app, if (Friend.count() > 1) "${Friend.lastApprover}이(가) 승인했어요" else "친구가 승인했어요")
                        break
                    }
                }
                for (m in b) {
                    if (Store.str("policyTopic") != polTopic) break
                    if (Friend.applyPolicy(m, polTopic) == null) {
                        Store.put("policyTopic", "")
                        toast(app, "친구가 자유 시간을 정해 줬어요")
                        break
                    }
                }
            }
        }.start()
    }
}

/** 요청마다 새로 만드는 임시 주소(ntfy 주제)로 친구의 답을 받아 온다. 주소를 아는 사람만 읽고 쓸 수 있다. */
object Relay {
    fun newTopic(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val r = SecureRandom()
        return "dandan-r-" + (1..24).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    /** 주제에 한 줄을 남긴다(백그라운드). 주제가 비었으면 아무것도 안 한다. */
    fun postAsync(topic: String, text: String) {
        if (!valid(topic)) return
        Thread { post(topic, text) }.start()
    }

    fun valid(topic: String): Boolean = Regex("[A-Za-z0-9_-]{8,64}").matches(topic)

    /** 주제에 한 줄을 남긴다(이 스레드에서). 성공하면 true. */
    fun post(topic: String, text: String): Boolean {
        if (!valid(topic)) return false
        return try {
            val c = URL("https://ntfy.sh/$topic").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.doOutput = true
            c.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            c.disconnect()
            code in 200..299
        } catch (e: Exception) { false }
    }

    /** 주제에 쌓인 메시지를 읽어 온다. 실패하면 빈 목록. */
    fun fetch(topic: String): List<String> = try {
        val c = URL("https://ntfy.sh/$topic/json?poll=1&since=all").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        val out = ArrayList<String>()
        if (c.responseCode in 200..299) {
            c.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { l ->
                    try {
                        val o = JSONObject(l)
                        if (o.optString("event") == "message") out.add(o.optString("message").trim())
                    } catch (e: Exception) { }
                }
            }
        }
        c.disconnect()
        out
    } catch (e: Exception) { emptyList() }
}
