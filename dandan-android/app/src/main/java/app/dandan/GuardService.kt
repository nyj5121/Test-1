package app.dandan

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * 잠금의 중심. 접근성 서비스로 지금 앞에 있는 앱을 감지해서
 *  - 폰 잠금 중이면 허용되지 않은 앱을 잠금 화면으로 되돌리고,
 *  - 철벽 모드면 알림창, 최근 앱, 전원 메뉴, 설정 앱을 막고,
 *  - 앱 잠금/사용시간 약속에 걸린 앱 위에 안내 화면을 띄운다.
 */
class GuardService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GuardService? = null

        fun enabled(ctx: Context): Boolean {
            val v = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val cn = ComponentName(ctx, GuardService::class.java)
            val full = cn.flattenToString()
            val short = cn.flattenToShortString()
            return v.split(':').any { it.equals(full, true) || it.equals(short, true) }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            try { onTick() } catch (e: Throwable) { }
            handler.postDelayed(this, 1000)
        }
    }

    private var fgPkg: String? = null
    private var lastGateAt = 0L
    private var lastLockAt = 0L
    private var lastShortsAt = 0L
    private var lastRefresh = 0L
    private var lastNotifyCheck = 0L
    private var lastPhone: PhoneLock? = null
    private var strip: View? = null

    private var homePkgs: Set<String> = emptySet()
    private var imePkgs: Set<String> = emptySet()
    private var dialerPkg: String? = null
    private var smsPkg: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Store.init(this)
        instance = this
        refreshPkgs()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        handler.removeCallbacks(tick)
        updateStrip(false)
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    private fun refreshPkgs() {
        homePkgs = Apps.homePackages(this)
        imePkgs = try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .inputMethodList.map { it.packageName }.toSet()
        } catch (e: Exception) { emptySet() }
        dialerPkg = Apps.dialerPkg(this)
        smsPkg = Apps.smsPkg(this)
    }

    // ---------- 1초마다 ----------
    private fun onTick() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRefresh > 60_000) {
            lastRefresh = now
            refreshPkgs()
            Promises.rollover()
        }

        if (Engine.finalizeIfDone(this)) {
            try {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra("finished", true)
                )
            } catch (e: Exception) { }
        }
        Engine.cleanupOnce()

        val phone = Engine.phone(this)
        val prev = lastPhone
        if (prev != null && phone == null && !prev.manual) Engine.credit(prev.totalMs)
        lastPhone = phone

        updateStrip(phone != null && Store.ironMode && Store.ironStatus)

        if (now - lastNotifyCheck > 30_000) {
            lastNotifyCheck = now
            notifyUpcoming()
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return

        val fg = fgPkg ?: return
        if (phone != null) {
            if (fg != packageName && !allowedDuring(fg)) goLock()
        } else {
            tickPromise(fg)
        }
    }

    private fun notifyUpcoming() {
        val now = System.currentTimeMillis()
        for (s in Store.schedules()) {
            if (s.silent || s.target != "phone" || !s.enabled) continue
            val n = Engine.nextStart(s, now) ?: continue
            val left = n - now
            if (left in 1..300_000L && Store.long("notified_${s.id}") != n) {
                Store.put("notified_${s.id}", n)
                Notifier.post(this, s.id.hashCode(), "곧 잠겨요", "${Fmt.clock(s.startMin)}에 예약 잠금이 시작돼요.")
            }
        }
    }

    // ---------- 창 이벤트 ----------
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        e ?: return
        val pkg = e.packageName?.toString() ?: return
        try {
            when (e.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindow(pkg, e.className?.toString() ?: "")
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> onContent(pkg)
            }
        } catch (t: Throwable) { }
    }

    private fun onWindow(pkg: String, cls: String) {
        if (pkg == packageName) { fgPkg = pkg; return }
        if (pkg in imePkgs) return
        val phone = Engine.phone(this)

        if (pkg == "com.android.systemui") {
            if (phone != null && Store.ironMode) {
                if (Store.ironPower &&
                    (cls.contains("GlobalActions", true) || cls.contains("PowerMenu", true) || cls.contains("Shutdown", true))
                ) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    goLock()
                    return
                }
                if (Store.ironRecents && cls.contains("Recents", true)) {
                    goLock()
                    return
                }
                if (Store.ironStatus) dismissShade()
            }
            return
        }

        if (pkg == "android") {
            if (phone != null && Store.ironMode && Store.ironPopup) performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        fgPkg = pkg

        if (phone != null) {
            if (!allowedDuring(pkg)) goLock()
            return
        }

        if (Store.dumbOn && pkg in homePkgs) {
            openDumb()
            return
        }
        gateCheck(pkg)
    }

    private fun allowedDuring(pkg: String): Boolean {
        if (pkg == packageName || pkg in imePkgs) return true
        if (pkg in Store.allowed) return true
        if (pkg == dialerPkg || pkg == smsPkg) return true
        if (pkg.contains("incallui") || pkg.contains("telecom") || pkg.contains("emergency")) return true
        if (pkg == "com.android.phone") return true
        return false
    }

    private fun dismissShade() {
        if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        else performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun goLock() {
        val n = SystemClock.elapsedRealtime()
        if (n - lastLockAt < 400) return
        lastLockAt = n
        try {
            startActivity(
                Intent(this, LockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (e: Exception) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun openDumb() {
        try {
            startActivity(
                Intent(this, DumbPhoneActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (e: Exception) { }
    }

    // ---------- 앱 잠금 / 약속 ----------
    private fun gateCheck(pkg: String) {
        if (pkg in Engine.blockedApps(this)) { showGate(pkg, "blocked"); return }
        val pr = Store.promises().firstOrNull { it.pkg == pkg } ?: return
        if (!Promises.applies(pr)) return
        val today = Store.dayKey()
        val timeOver = pr.unit == "time" && Store.useSec(today, pkg) >= pr.limit * 60L
        if (timeOver) { showGate(pkg, "limit"); return }
        if (Store.long("grant_$pkg") > SystemClock.elapsedRealtime()) return
        val countOver = pr.unit == "count" && Store.opens(today, pkg) >= pr.limit
        showGate(pkg, if (countOver) "limit" else "intervene")
    }

    private fun tickPromise(fg: String) {
        if (fg == packageName) return
        if (fg in Engine.blockedApps(this)) { showGate(fg, "blocked"); return }
        val pr = Store.promises().firstOrNull { it.pkg == fg } ?: return
        if (!Promises.applies(pr)) return
        if (pr.unit == "time") Store.addUse(fg, 1)
        gateCheck(fg)
    }

    private fun showGate(pkg: String, mode: String) {
        val n = SystemClock.elapsedRealtime()
        if (n - lastGateAt < 1500) return
        lastGateAt = n
        try {
            startActivity(
                Intent(this, GateActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("pkg", pkg).putExtra("mode", mode)
            )
        } catch (e: Exception) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    // ---------- 숏폼 차단 (베스트 에포트) ----------
    private fun onContent(pkg: String) {
        val yt = pkg == "com.google.android.youtube" && Store.shortsYt
        val ig = pkg == "com.instagram.android" && Store.reelsIg
        if (!yt && !ig) return
        val n = SystemClock.elapsedRealtime()
        if (n - lastShortsAt < 700) return
        lastShortsAt = n
        val root = rootInActiveWindow ?: return
        val ids = if (yt) listOf(
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_player_page_container"
        ) else listOf(
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/clips_swipe_refresh_container"
        )
        for (id in ids) {
            val l = root.findAccessibilityNodeInfosByViewId(id)
            if (l != null && l.isNotEmpty()) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }
        }
    }

    // ---------- 상태바 차단용 투명 막 ----------
    private fun updateStrip(on: Boolean) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (on && strip == null) {
            val v = View(this)
            v.setOnTouchListener { _, _ -> true }
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            val h = if (id > 0) resources.getDimensionPixelSize(id) else 90
            val lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP
            try { wm.addView(v, lp); strip = v } catch (e: Exception) { }
        } else if (!on && strip != null) {
            try { wm.removeView(strip) } catch (e: Exception) { }
            strip = null
        }
    }
}
