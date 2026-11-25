package app.dandan

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 예약 하나. days: bit0=월 ... bit6=일, 0이면 "한 번만". */
data class Schedule(
    val id: String,
    val startMin: Int,
    val endMin: Int,
    val days: Int,
    val enabled: Boolean,
    val silent: Boolean,
    val target: String,          // "phone" | "app"
    val apps: Set<String>,
    val onceStart: Long,
    val locked: Boolean = false  // 패널티 예약처럼 사용자가 못 고치는 예약
) {
    val durMin: Int
        get() = ((endMin - startMin + 1440) % 1440).let { if (it == 0) 1440 else it }
}

/** 앱별 사용 약속. */
data class Promise(
    val pkg: String,
    val unit: String,            // "time"(분) | "count"(실행 횟수)
    val freq: String,            // "daily" | "weekday" | "weekend"
    val limit: Int,
    val streak: Int,
    val lastEval: String,
    val created: String,
    val sig: String
)

object Store {
    private var _sp: SharedPreferences? = null
    lateinit var app: Context
    val rev = mutableIntStateOf(0)

    fun init(c: Context) {
        if (_sp == null) {
            app = c.applicationContext
            _sp = app.getSharedPreferences("dandan", Context.MODE_PRIVATE)
        }
    }

    private val sp: SharedPreferences get() = _sp!!

    fun bump() { rev.intValue = rev.intValue + 1 }

    fun str(k: String, d: String = ""): String = sp.getString(k, d) ?: d
    fun long(k: String, d: Long = 0L): Long = sp.getLong(k, d)
    fun int(k: String, d: Int = 0): Int = sp.getInt(k, d)
    fun bool(k: String, d: Boolean = false): Boolean = sp.getBoolean(k, d)

    fun put(k: String, v: Any) {
        val e = sp.edit()
        when (v) {
            is String -> e.putString(k, v)
            is Long -> e.putLong(k, v)
            is Int -> e.putInt(k, v)
            is Boolean -> e.putBoolean(k, v)
            else -> {}
        }
        e.apply()
        bump()
    }

    fun strSet(k: String): Set<String> = HashSet(sp.getStringSet(k, emptySet()) ?: emptySet())
    fun putStrSet(k: String, v: Set<String>) {
        sp.edit().putStringSet(k, HashSet(v)).apply()
        bump()
    }

    // ---------- 날짜 ----------
    fun dayKey(t: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(t))

    // ---------- 설정 값 ----------
    var allowed: Set<String>
        get() = strSet("allowed")
        set(v) = putStrSet("allowed", v)
    var appLockApps: Set<String>
        get() = strSet("appLockApps")
        set(v) = putStrSet("appLockApps", v)
    var dumbApps: Set<String>
        get() = strSet("dumbApps")
        set(v) = putStrSet("dumbApps", v)

    var ironMode: Boolean
        get() = bool("iron", true)
        set(v) = put("iron", v)
    var ironRecents: Boolean
        get() = bool("iron_recents", true)
        set(v) = put("iron_recents", v)
    var ironPower: Boolean
        get() = bool("iron_power", true)
        set(v) = put("iron_power", v)
    var ironStatus: Boolean
        get() = bool("iron_status", true)
        set(v) = put("iron_status", v)
    var ironPopup: Boolean
        get() = bool("iron_popup", true)
        set(v) = put("iron_popup", v)

    var editProtect: Boolean
        get() = bool("editProtect", false)
        set(v) = put("editProtect", v)
    var emergencyEnabled: Boolean
        get() = bool("emergency", false)
        set(v) = put("emergency", v)
    var dumbOn: Boolean
        get() = bool("dumbOn", false)
        set(v) = put("dumbOn", v)
    var shortsYt: Boolean
        get() = bool("shortsYt", false)
        set(v) = put("shortsYt", v)
    var igMode: Boolean
        get() = bool("igMode", false)
        set(v) = put("igMode", v)
    /** 인스타 홈 피드를 가리는 대신, 보이는 그대로 두고 스크롤만 못 하게 막는다. */
    var igFeed: Boolean
        get() = bool("igFeed", false)
        set(v) = put("igFeed", v)
    var igDiag: Boolean
        get() = bool("igDiag", false)
        set(v) = put("igDiag", v)
    /** 홈에서 피드 가림막이 시작되는 위치(화면 높이의 %). 0이면 자동. */
    var igTop: Int
        get() = int("igTop", 0)
        set(v) = put("igTop", v)
    var bgUri: String
        get() = str("bgUri")
        set(v) = put("bgUri", v)
    var ringColor: Int
        get() = int("ringColor", 0xFF8C9BFF.toInt())
        set(v) = put("ringColor", v)

    // ---------- 예약 ----------
    private var schedCache: List<Schedule>? = null
    private var promCache: List<Promise>? = null

    fun schedules(): List<Schedule> {
        schedCache?.let { return it }
        val arr = try { JSONArray(str("schedules", "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Schedule>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val apps = HashSet<String>()
            val a = o.optJSONArray("apps")
            if (a != null) for (j in 0 until a.length()) apps.add(a.getString(j))
            out.add(
                Schedule(
                    o.getString("id"), o.getInt("s"), o.getInt("e"), o.getInt("d"),
                    o.getBoolean("on"), o.optBoolean("sil"), o.optString("t", "phone"),
                    apps, o.optLong("once"), o.optBoolean("lk")
                )
            )
        }
        schedCache = out
        return out
    }

    fun saveSchedules(l: List<Schedule>) {
        val arr = JSONArray()
        for (s in l) {
            arr.put(
                JSONObject().put("id", s.id).put("s", s.startMin).put("e", s.endMin)
                    .put("d", s.days).put("on", s.enabled).put("sil", s.silent)
                    .put("t", s.target).put("apps", JSONArray(s.apps.toList()))
                    .put("once", s.onceStart).put("lk", s.locked)
            )
        }
        schedCache = l.toList()
        put("schedules", arr.toString())
        Scheduler.reschedule(app)
    }

    // ---------- 약속 ----------
    fun promises(): List<Promise> {
        promCache?.let { return it }
        val arr = try { JSONArray(str("promises", "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Promise>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Promise(
                    o.getString("pkg"), o.optString("unit", "time"), o.optString("freq", "daily"),
                    o.getInt("limit"), o.optInt("streak"), o.optString("lastEval"),
                    o.optString("created"), o.optString("sig")
                )
            )
        }
        promCache = out
        return out
    }

    fun savePromises(l: List<Promise>) {
        val arr = JSONArray()
        for (p in l) {
            arr.put(
                JSONObject().put("pkg", p.pkg).put("unit", p.unit).put("freq", p.freq)
                    .put("limit", p.limit).put("streak", p.streak).put("lastEval", p.lastEval)
                    .put("created", p.created).put("sig", p.sig)
            )
        }
        promCache = l.toList()
        put("promises", arr.toString())
    }

    /** 14일이 지난 날짜별 기록(사용시간, 열기 횟수, 잠금 기록 등)을 지운다. 하루에 한 번만 돈다. */
    fun prune() {
        val today = dayKey()
        if (str("prunedDay") == today) return
        val cutoff = dayKey(System.currentTimeMillis() - 14 * 86_400_000L)
        val e = sp.edit()
        val re = Regex("^(?:u_|o_|bonus_|dUsed_|lm_|dl_)(\\d{8})")
        for (k in sp.all.keys) {
            val m = re.find(k) ?: continue
            if (m.groupValues[1] < cutoff) e.remove(k)
        }
        e.putString("prunedDay", today).apply()
    }

    // ---------- 사용 기록 ----------
    fun useSec(day: String, pkg: String): Long = long("u_${day}_$pkg")
    fun addUse(pkg: String, sec: Long) {
        val k = "u_${dayKey()}_$pkg"
        sp.edit().putLong(k, long(k) + sec).apply()
    }
    fun opens(day: String, pkg: String): Int = int("o_${day}_$pkg")
    fun addOpen(pkg: String) {
        val k = "o_${dayKey()}_$pkg"
        put(k, int(k) + 1)
    }

    // ---------- 잠금 기록 / 연속일 ----------
    fun lockedMsToday(): Long = long("lm_${dayKey()}")
    fun addLockedMs(ms: Long) = put("lm_${dayKey()}", lockedMsToday() + ms)

    fun creditDay() {
        val d = dayKey()
        val last = str("sDay")
        if (last == d) return
        val y = dayKey(System.currentTimeMillis() - 86_400_000L)
        put("streak", if (last == y) int("streak") + 1 else 1)
        put("sDay", d)
    }

    fun streak(): Int {
        val last = str("sDay")
        val d = dayKey()
        val y = dayKey(System.currentTimeMillis() - 86_400_000L)
        return if (last == d || last == y) int("streak") else 0
    }
}
