package app.dandan

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.util.Calendar
import java.util.Locale
import java.util.UUID

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

    fun days(mask: Int): String = when {
        mask == 0 -> "한 번만"
        mask == 127 -> "매일"
        else -> listOf("월", "화", "수", "목", "금", "토", "일")
            .filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.joinToString(" ")
    }
}

object Engine {
    const val NEAR_MS = 2 * 3600_000L

    fun bootCount(ctx: Context): Int = try {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (e: Exception) { -1 }

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
