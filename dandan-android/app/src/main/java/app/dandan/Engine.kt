package app.dandan

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

class PhoneLock(
    val remainMs: Long,
    val totalMs: Long,
    val trial: Boolean,
    val manual: Boolean
)

object Fmt {
    fun hms(ms: Long): String {
        val s = (ms + 999) / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    }

    fun dur(ms: Long): String {
        val m = ms / 60_000
        val h = m / 60
        val mm = m % 60
        return if (h > 0) "${h}시간 ${mm}분" else "${mm}분"
    }

    fun minutes(min: Int): String {
        val h = min / 60
        val m = min % 60
        return when {
            h > 0 && m > 0 -> "${h}시간 ${m}분"
            h > 0 -> "${h}시간"
            else -> "${m}분"
        }
    }

    fun clock(min: Int): String = String.format(Locale.US, "%02d:%02d", min / 60, min % 60)

    /** 긴 시간을 "3일 4시간" 같이 보여 준다. */
    fun span(ms: Long): String {
        val m = ms / 60_000
        val d = m / 1440
        val h = m % 1440 / 60
        val mm = m % 60
        return when {
            d > 0 -> "${d}일 ${h}시간"
            h > 0 -> "${h}시간 ${mm}분"
            else -> "${mm}분"
        }
    }

    fun days(mask: Int): String = when {
        mask == 0 -> "한 번만"
        mask == 127 -> "매일"
        else -> listOf("월", "화", "수", "목", "금", "토", "일")
            .filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.joinToString(" ")
    }
}

object Engine {
    const val NEAR_MS = 2 * 3600_000L

    private var bootCache = Int.MIN_VALUE

    /** 부팅 횟수. 재부팅하면 프로세스도 새로 뜨므로 한 번 읽어 두고 재사용한다. */
    fun bootCount(ctx: Context): Int {
        if (bootCache != Int.MIN_VALUE) return bootCache
        bootCache = try {
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (e: Exception) { -1 }
        return bootCache
    }

    // ---------- 즉시 잠금 ----------
    fun manualRemain(ctx: Context): Long {
        val endW = Store.long("lockEndW")
        if (endW == 0L) return 0
        val boot = bootCount(ctx)
        val sameBoot = boot != -1 && Store.int("lockBoot", -2) == boot
        val rem = if (sameBoot) Store.long("lockEndE") - SystemClock.elapsedRealtime()
        else endW - System.currentTimeMillis()
        return maxOf(0L, rem)
    }

    fun startLock(ctx: Context, durMs: Long, kind: String, trial: Boolean, apps: Set<String>) {
        val now = System.currentTimeMillis()
        Store.put("lockStartW", now)
        Store.put("lockEndW", now + durMs)
        Store.put("lockEndE", SystemClock.elapsedRealtime() + durMs)
        Store.put("lockBoot", bootCount(ctx))
        Store.put("lockKind", kind)
        Store.put("lockTrial", trial)
        Store.put("lockTotal", durMs)
        Store.putStrSet("lockApps", apps)
        Store.put("ignoreUntil", 0L)
    }

    // ---------- 예약 계산 ----------
    /** 지금 이 예약이 걸려 있으면 (시작, 끝)을 돌려준다. */
    fun schedWindow(s: Schedule, now: Long): Pair<Long, Long>? {
        if (!s.enabled) return null
        val dur = s.durMin * 60_000L
        if (s.days == 0) {
            return if (s.onceStart > 0 && now >= s.onceStart && now < s.onceStart + dur)
                Pair(s.onceStart, s.onceStart + dur) else null
        }
        for (off in 0 downTo -1) {
            val c = Calendar.getInstance()
            c.timeInMillis = now
            c.add(Calendar.DAY_OF_YEAR, off)
            c.set(Calendar.HOUR_OF_DAY, s.startMin / 60)
            c.set(Calendar.MINUTE, s.startMin % 60)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            val bit = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7   // 월=0 ... 일=6
            if (((s.days shr bit) and 1) == 0) continue
            val st = c.timeInMillis
            val en = st + dur
            if (now >= st && now < en) return Pair(st, en)
        }
        return null
    }

    fun nextStart(s: Schedule, now: Long): Long? {
        if (!s.enabled) return null
        if (s.days == 0) return if (s.onceStart > now) s.onceStart else null
        for (off in 0..7) {
            val c = Calendar.getInstance()
            c.timeInMillis = now
            c.add(Calendar.DAY_OF_YEAR, off)
            c.set(Calendar.HOUR_OF_DAY, s.startMin / 60)
            c.set(Calendar.MINUTE, s.startMin % 60)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            if (c.timeInMillis <= now) continue
            val bit = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
            if (((s.days shr bit) and 1) == 0) continue
            return c.timeInMillis
        }
        return null
    }

    /** "한 번만" 예약이 다음에 시작할 시각. */
    fun nextOccurrence(startMin: Int, now: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, startMin / 60)
        c.set(Calendar.MINUTE, startMin % 60)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    // ---------- 현재 잠금 상태 ----------
    fun phone(ctx: Context): PhoneLock? {
        val now = System.currentTimeMillis()
        var best: PhoneLock? = null
        val mr = manualRemain(ctx)
        if (mr > 0 && Store.str("lockKind") == "phone") {
            best = PhoneLock(mr, Store.long("lockTotal"), Store.bool("lockTrial"), true)
        }
        if (now >= Store.long("ignoreUntil")) {
            for (s in Store.schedules()) {
                if (s.target != "phone") continue
                val w = schedWindow(s, now) ?: continue
                val rem = w.second - now
                if (best == null || rem > best.remainMs) {
                    best = PhoneLock(rem, w.second - w.first, false, false)
                }
            }
        }
        return best
    }

    /** 지금 못 여는 앱들(앱 잠금 + 예약 앱 잠금 + 오늘 아예 잠근 앱). */
    fun blockedApps(ctx: Context): Set<String> {
        val out = HashSet<String>()
        if (manualRemain(ctx) > 0 && Store.str("lockKind") == "app") out.addAll(Store.strSet("lockApps"))
        val now = System.currentTimeMillis()
        for (s in Store.schedules()) {
            if (s.target == "app" && schedWindow(s, now) != null) out.addAll(s.apps)
        }
        out.addAll(Store.strSet("dl_${Store.dayKey()}"))
        // 친구가 잠깐 풀어 준 앱은 제외
        val el = SystemClock.elapsedRealtime()
        out.removeAll { Store.long("pass_$it") > el }
        return out
    }

    fun appLockRemain(ctx: Context): Long =
        if (Store.str("lockKind") == "app") manualRemain(ctx) else 0L

    /** 즉시 잠금이 끝났으면 정리하고 true. */
    fun finalizeIfDone(ctx: Context): Boolean {
        if (Store.long("lockEndW") == 0L) return false
        if (manualRemain(ctx) > 0) return false
        val trial = Store.bool("lockTrial")
        val total = Store.long("lockTotal")
        Store.put("lockEndW", 0L)
        if (!trial) credit(total)
        Store.put("finishedTrial", trial)
        Store.put("finishedFlag", true)
        return true
    }

    fun credit(totalMs: Long) {
        if (totalMs < 60_000L) return
        Store.addLockedMs(totalMs)
        Store.creditDay()
    }

    fun cleanupOnce() {
        val now = System.currentTimeMillis()
        val list = Store.schedules()
        var changed = false
        val out = list.filter { s ->
            val done = s.days == 0 && s.onceStart > 0 && now >= s.onceStart + s.durMin * 60_000L
            if (done && s.locked) { changed = true; false }
            else true
        }.map { s ->
            if (s.days == 0 && s.enabled && s.onceStart > 0 && now >= s.onceStart + s.durMin * 60_000L) {
                changed = true
                s.copy(enabled = false)
            } else s
        }
        if (changed) Store.saveSchedules(out)
    }

    fun lockedTodayMs(ctx: Context): Long {
        var t = Store.lockedMsToday()
        val p = phone(ctx)
        if (p != null) t += (p.totalMs - p.remainMs).coerceAtLeast(0)
        return t
    }

    // ---------- 수정 방지 ----------
    fun editLocked(s: Schedule): Boolean {
        if (Store.long("schedPass_${s.id}") > System.currentTimeMillis()) return false   // 친구가 허락함
        if (s.locked) return true
        if (!Store.editProtect || !s.enabled) return false
        val now = System.currentTimeMillis()
        if (schedWindow(s, now) != null) return true
        val n = nextStart(s, now) ?: return false
        return n - now < NEAR_MS
    }

    /** 지금 잠겨 있거나 곧(2시간 안) 잠금이 시작되는가. 강도 설정을 못 끄게 막는 데 쓴다. */
    fun nearLock(ctx: Context): Boolean {
        if (Store.long("relaxPass") > System.currentTimeMillis()) return false   // 친구가 허락함
        if (Dumb.active(ctx)) return true
        if (phone(ctx) != null || manualRemain(ctx) > 0) return true
        val now = System.currentTimeMillis()
        for (s in Store.schedules()) {
            if (!s.enabled) continue
            if (schedWindow(s, now) != null) return true
            val n = nextStart(s, now) ?: continue
            if (n - now < NEAR_MS) return true
        }
        return false
    }

    /** 친구가 승인한 폰 잠금 해제. 패널티는 없다. */
    fun releaseByFriend(ctx: Context) {
        val now = System.currentTimeMillis()
        val p = phone(ctx) ?: return
        if (manualRemain(ctx) > 0 && Store.str("lockKind") == "phone") Store.put("lockEndW", 0L)
        Store.put("ignoreUntil", now + p.remainMs)
    }

    // ---------- 비상 해제 ----------
    fun emergencyRelease(ctx: Context) {
        val now = System.currentTimeMillis()
        val p = phone(ctx) ?: return
        val remain = p.remainMs
        if (manualRemain(ctx) > 0 && Store.str("lockKind") == "phone") Store.put("lockEndW", 0L)
        Store.put("ignoreUntil", now + remain)
        // 패널티: 연속 기록 초기화, 오늘 잠금 기록 삭제, 내일 같은 시각에 남은 시간의 2배를 다시 잠금
        Store.put("streak", 0)
        Store.put("sDay", "")
        Store.put("lm_${Store.dayKey()}", 0L)
        Store.put("emCount", Store.int("emCount") + 1)
        val c = Calendar.getInstance()
        val startMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val durMin = (remain * 2 / 60_000L).toInt().coerceIn(60, 720)
        val onceStart = (now + 86_400_000L) / 60_000L * 60_000L
        val s = Schedule(
            UUID.randomUUID().toString(), startMin, (startMin + durMin) % 1440, 0,
            enabled = true, silent = true, target = "phone", apps = emptySet(),
            onceStart = onceStart, locked = true
        )
        Store.saveSchedules(Store.schedules() + s)
    }
}

object Promises {
    fun applies(p: Promise): Boolean {
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return when (p.freq) {
            "weekday" -> dow in 2..6
            "weekend" -> dow == 1 || dow == 7
            else -> true
        }
    }

    /** 친구가 늘려 준 오늘의 추가 한도(시간이면 분, 횟수면 번). */
    fun bonus(p: Promise, day: String = Store.dayKey()): Int = Store.int("bonus_${day}_${p.pkg}")

    fun limitOf(p: Promise, day: String = Store.dayKey()): Int = p.limit + bonus(p, day)

    fun used(p: Promise, day: String = Store.dayKey()): Int =
        if (p.unit == "time") (Store.useSec(day, p.pkg) / 60).toInt() else Store.opens(day, p.pkg)

    /** 어제 약속을 지켰는지 따져 연속일을 갱신한다. */
    fun rollover() {
        val today = Store.dayKey()
        val y = Store.dayKey(System.currentTimeMillis() - 86_400_000L)
        var changed = false
        val out = Store.promises().map { p ->
            if (p.lastEval == today) p
            else {
                changed = true
                val kept = if (p.unit == "time") Store.useSec(y, p.pkg) <= limitOf(p, y) * 60L
                else Store.opens(y, p.pkg) <= limitOf(p, y)
                p.copy(lastEval = today, streak = if (kept) p.streak + 1 else 0)
            }
        }
        if (changed) Store.savePromises(out)
    }
}

/** 바보폰 자유 시간 한 칸. days: bit0=월 ... bit6=일. */
data class FreeWin(val s: Int, val e: Int, val days: Int)

/**
 * 바보폰을 하루 종일 켜 두고, 자유 시간에만 벗어난다.
 * 자유 시간은 "세션"으로 다룬다: 시작할 때 남은 시간을 부팅 후 경과시간(elapsedRealtime)으로 못 박아서
 * 나중에 폰 시계를 바꿔도 늘어나지 않는다. 같은 시간대는 하루에 한 번만 시작된다.
 */
object Dumb {
    val ALWAYS_BLOCKED = setOf(
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller"
    )

    private var lastOff = 0L
    private var lastBoot = -2

    fun parse(str: String): List<FreeWin> {
        if (str.isBlank()) return emptyList()
        val out = ArrayList<FreeWin>()
        for (item in str.split(",")) {
            try {
                val (range, mask) = item.split(":")
                val (a, b) = range.split("-")
                val s = a.substring(0, 2).toInt() * 60 + a.substring(2, 4).toInt()
                val e = b.substring(0, 2).toInt() * 60 + b.substring(2, 4).toInt()
                val d = mask.toInt() and 127
                if (s in 0..1439 && e in 0..1439 && s != e && d != 0) out.add(FreeWin(s, e, d))
            } catch (ex: Exception) { }
        }
        return out.take(8)
    }

    fun encode(l: List<FreeWin>): String = l.joinToString(",") {
        String.format(Locale.US, "%02d%02d-%02d%02d:%d", it.s / 60, it.s % 60, it.e / 60, it.e % 60, it.days)
    }

    fun friendControlled(): Boolean = Friend.scopeOn("dumb_policy")

    /** 지금 유효한 자유 시간 목록. 친구가 정하는 모드에서는 정책이 만료되면 비어 있다(=하루 종일 바보폰). */
    fun windows(): List<FreeWin> {
        if (friendControlled() && Store.long("dumbExp") <= System.currentTimeMillis() / 1000) return emptyList()
        return parse(Store.str("dumbWins"))
    }

    private fun asSched(w: FreeWin) =
        Schedule("dumb", w.s, w.e, w.days, true, true, "dumb", emptySet(), 0L)

    fun nextFreeStart(now: Long): Long? =
        windows().mapNotNull { Engine.nextStart(asSched(it), now) }.minOrNull()

    fun freeRemainMs(ctx: Context): Long {
        val el = SystemClock.elapsedRealtime()
        val same = Store.int("dfBoot", -2) == Engine.bootCount(ctx)
        return if (same && Store.long("dfEndE") > el) Store.long("dfEndE") - el else 0L
    }

    fun free(ctx: Context): Boolean = freeRemainMs(ctx) > 0

    /** 바보폰이 켜져 있고 지금이 자유 시간이 아니다. */
    fun active(ctx: Context): Boolean = Store.dumbOn && !free(ctx)

    fun strict(ctx: Context): Boolean =
        Store.int("dsBoot", -2) == Engine.bootCount(ctx) && Store.long("dsEndE") > SystemClock.elapsedRealtime()

    // ---------- 바보폰 기간 ----------
    /** 켤 때 정한 기간. days가 0이면 기간 없음. 부팅 후 경과시간으로 못 박아서 시계를 바꿔도 줄지 않는다. */
    fun startPeriod(ctx: Context, days: Int) {
        if (days <= 0) { Store.put("dumbEndW", 0L); return }
        val ms = days * 86_400_000L
        Store.put("dumbEndW", System.currentTimeMillis() + ms)
        Store.put("dumbEndE", SystemClock.elapsedRealtime() + ms)
        Store.put("dumbBoot", Engine.bootCount(ctx))
    }

    fun periodSet(): Boolean = Store.long("dumbEndW") != 0L

    fun periodRemainMs(ctx: Context): Long {
        val endW = Store.long("dumbEndW")
        if (endW == 0L) return 0L
        val boot = Engine.bootCount(ctx)
        val same = boot != -1 && Store.int("dumbBoot", -2) == boot
        val rem = if (same) Store.long("dumbEndE") - SystemClock.elapsedRealtime() else endW - System.currentTimeMillis()
        return maxOf(0L, rem)
    }

    fun startSession(ctx: Context, ms: Long) {
        val el = SystemClock.elapsedRealtime()
        val boot = Engine.bootCount(ctx)
        val cur = if (Store.int("dfBoot", -2) == boot) Store.long("dfEndE") else 0L
        Store.put("dfEndE", maxOf(cur, el + ms))
        Store.put("dfBoot", boot)
    }

    /** 1초마다: 시계 조작 감지, 자유 시간 시작 판단. */
    fun tick(ctx: Context) {
        val el = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val boot = Engine.bootCount(ctx)
        val off = wall - el
        if (lastBoot == boot && lastOff != 0L && abs(off - lastOff) > 120_000L) {
            // 시계가 갑자기 바뀜 → 6시간 동안 새 자유 시간을 시작하지 않는다
            Store.put("dsEndE", el + 6 * 3600_000L)
            Store.put("dsBoot", boot)
        }
        lastOff = off
        lastBoot = boot

        // 정한 기간이 끝나면 바보폰이 저절로 꺼진다
        if (Store.dumbOn && periodSet() && periodRemainMs(ctx) <= 0L) {
            Store.dumbOn = false
            Store.put("dumbEndW", 0L)
            Notifier.post(ctx, 7001, "바보폰이 끝났어요", "정한 기간이 지나서 바보폰 모드가 꺼졌어요.")
            return
        }

        if (!Store.dumbOn || free(ctx) || strict(ctx)) return
        val ws = windows()
        for ((i, w) in ws.withIndex()) {
            val win = Engine.schedWindow(asSched(w), wall) ?: continue
            val key = "dUsed_${Store.dayKey(win.first)}_${i}_${w.s}"
            if (Store.bool(key)) continue
            Store.put(key, true)
            startSession(ctx, win.second - wall)
            return
        }
    }
}
