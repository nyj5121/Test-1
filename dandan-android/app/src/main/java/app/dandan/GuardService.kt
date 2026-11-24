package app.dandan

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
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

private const val IG = "com.instagram.android"
private val TAB_IDS = listOf("feed_tab", "search_tab", "clips_tab", "profile_tab", "creation_tab")
private val TAB_DESCS = setOf("홈", "검색 및 탐색", "릴스", "프로필", "Home", "Search and Explore", "Reels", "Profile")

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
        Dumb.tick(this)

        val phone = Engine.phone(this)
        val prev = lastPhone
        if (prev != null && phone == null && !prev.manual) Engine.credit(prev.totalMs)
        lastPhone = phone

        updateStrip(phone != null && Store.ironMode && Store.ironStatus)
        if (fgPkg != IG || !Store.igMode) hideIg()

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
            if (Dumb.active(this) && fg != packageName && !dumbAllowed(fg)) { openDumb(); return }
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
            if (pkg == IG && (Store.igMode || Store.igDiag)) igScan()
        } catch (t: Throwable) { }
    }

    private fun onWindow(pkg: String, cls: String) {
        // 우리 앱의 액티비티만 "앞에 있는 앱"으로 친다 (가림막 같은 창은 제외)
        if (pkg == packageName) { if (cls.startsWith("app.dandan.")) fgPkg = pkg; return }
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
        if (pkg != IG) hideIg()

        if (phone != null) {
            if (!allowedDuring(pkg)) goLock()
            return
        }

        if (Dumb.active(this) && (pkg in homePkgs || !dumbAllowed(pkg))) {
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

    /** 바보폰 시간에 열 수 있는 앱: 전화·문자·고른 앱·단단. 설정 앱과 앱 설치/삭제 화면은 고른 앱이어도 막는다. */
    private fun dumbAllowed(pkg: String): Boolean {
        if (pkg in Dumb.ALWAYS_BLOCKED) return false
        if (pkg == packageName || pkg in imePkgs) return true
        if (pkg in Store.dumbApps) return true
        if (pkg == dialerPkg || pkg == smsPkg) return true
        if (pkg.contains("incallui") || pkg.contains("telecom") || pkg.contains("emergency") || pkg == "com.android.phone") return true
        if (pkg.contains("permissioncontroller")) return true
        return false
    }

    private var lastDumbAt = 0L

    private fun openDumb() {
        val n = SystemClock.elapsedRealtime()
        if (n - lastDumbAt < 400) return
        lastDumbAt = n
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
        val limit = Promises.limitOf(pr)
        val timeOver = pr.unit == "time" && Store.useSec(today, pkg) >= limit * 60L
        if (timeOver) { showGate(pkg, "limit"); return }
        if (Store.long("grant_$pkg") > SystemClock.elapsedRealtime()) return
        val countOver = pr.unit == "count" && Store.opens(today, pkg) >= limit
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
    private val backTimes = ArrayList<Long>()

    /** 뒤로가기를 5초에 3번까지만 눌러 무한 반복(앱이 안 열리는 상황)을 막는다. */
    private fun safeBack() {
        val n = SystemClock.elapsedRealtime()
        backTimes.removeAll { n - it > 5000 }
        if (backTimes.size >= 3) return
        backTimes.add(n)
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun onContent(pkg: String) {
        if (!(pkg == "com.google.android.youtube" && Store.shortsYt)) return
        val n = SystemClock.elapsedRealtime()
        if (n - lastShortsAt < 700) return
        lastShortsAt = n
        val root = rootInActiveWindow ?: return
        val ids = listOf(
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_player_page_container"
        )
        for (id in ids) {
            val l = root.findAccessibilityNodeInfosByViewId(id)
            if (l != null && l.isNotEmpty()) {
                safeBack()
                return
            }
        }
    }

    // ---------- 인스타그램: 스토리·DM만 ----------
    private var igView: FrameLayout? = null
    private var igText: TextView? = null
    private var igBtn: Button? = null
    private var igTopApplied = -1
    private var lastIgScan = 0L
    private var lastDiagWrite = 0L
    private val igDumps = LinkedHashMap<String, String>()

    private fun statusBarH(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 90
    }

    /** 인스타 화면을 훑어서 하단 탭 바가 보이면(=피드/릴스/탐색/프로필) 가림막을 씌운다. 못 알아보면 아무것도 안 한다. */
    private fun igScan() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastIgScan < 400) return
        lastIgScan = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != IG) return
        val sh = resources.displayMetrics.heightPixels
        val sw = resources.displayMetrics.widthPixels
        val diag = Store.igDiag

        var tabsFound = 0
        var selectedTab: String? = null
        var trayBottom = -1
        var hasDm = false
        var hasStory = false
        val dump = StringBuilder()
        val r = Rect()
        val stack = ArrayList<AccessibilityNodeInfo>()
        stack.add(root)
        var count = 0
        while (stack.isNotEmpty() && count < 700) {
            val node = stack.removeAt(stack.size - 1)
            count++
            val id = node.viewIdResourceName?.substringAfter(":id/") ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            node.getBoundsInScreen(r)
            val isTab = id in TAB_IDS || (id.isEmpty() && desc in TAB_DESCS && r.top > sh * 8 / 10)
            if (isTab) {
                tabsFound++
                if (node.isSelected) selectedTab = id.ifEmpty { desc }
            }
            if (id.contains("tray", true) && r.top < sh * 4 / 10 && r.bottom > trayBottom) trayBottom = r.bottom
            if (id.contains("direct", true) || id.contains("inbox", true)) hasDm = true
            if (id.contains("reel_viewer", true) || id.contains("story", true)) hasStory = true
            if (diag && (id.isNotEmpty() || desc.isNotEmpty()) && dump.length < 6000) {
                dump.append(id.ifEmpty { "-" }).append(" | ")
                    .append(node.className?.toString()?.substringAfterLast('.') ?: "").append(" | ")
                    .append(desc.take(24)).append(" | ")
                    .append(r.left).append(',').append(r.top).append(',').append(r.right).append(',').append(r.bottom)
                    .append(if (node.isSelected) " | sel" else "").append('\n')
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.add(it) }
        }

        val tabBar = tabsFound >= 3
        if (diag) {
            val key = "tabs=$tabBar sel=${selectedTab ?: "?"} tray=${trayBottom > 0} dm=$hasDm story=$hasStory"
            igDumps[key] = "screen=${sw}x$sh tabsFound=$tabsFound trayBottom=$trayBottom\n$dump"
            if (now - lastDiagWrite > 2000) {
                lastDiagWrite = now
                val sb = StringBuilder()
                igDumps.forEach { (k, v) -> sb.append("=== ").append(k).append(" ===\n").append(v).append('\n') }
                Store.put("igDump", sb.toString())
            }
        }

        if (!Store.igMode || !tabBar) { hideIg(); return }

        val homeSelected = selectedTab == null || selectedTab == "feed_tab" || selectedTab == "홈" || selectedTab == "Home"
        if (homeSelected) {
            val manual = if (Store.igTop > 0) sh * Store.igTop / 100 else 0
            val top = when {
                manual > 0 -> manual
                trayBottom > 0 -> trayBottom
                else -> sh * 28 / 100
            }.coerceIn(sh / 10, sh * 6 / 10)
            showIg(top, false)
        } else {
            showIg(statusBarH(), true)
        }
    }

    private fun showIg(top: Int, fullCover: Boolean) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val h = resources.displayMetrics.heightPixels - top
        val d = resources.displayMetrics.density
        val existing = igView
        if (existing == null) {
            val f = FrameLayout(this)
            f.setBackgroundColor(0xF2101218.toInt())
            f.setOnTouchListener { _, _ -> true }
            val t = TextView(this)
            t.setTextColor(0xFFECEEF3.toInt())
            t.textSize = 16f
            t.gravity = Gravity.CENTER
            f.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            val b = Button(this)
            b.text = "홈으로"
            b.setOnClickListener { igGoHome() }
            val blp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM)
            blp.bottomMargin = (120 * d).toInt()
            f.addView(b, blp)
            val lp = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.y = top
            try { wm.addView(f, lp) } catch (e: Exception) { return }
            igView = f; igText = t; igBtn = b; igTopApplied = top
        } else if (igTopApplied != top) {
            val lp = existing.layoutParams as WindowManager.LayoutParams
            lp.y = top
            lp.height = h
            try { wm.updateViewLayout(existing, lp) } catch (e: Exception) { }
            igTopApplied = top
        }
        igText?.text = if (fullCover) "릴스·탐색·프로필은 쓸 수 없어요.\n스토리와 DM만 쓸 수 있어요."
        else "피드는 가려져 있어요.\n위에서 스토리와 DM만 쓰세요."
        igBtn?.visibility = if (fullCover) View.VISIBLE else View.GONE
    }

    private fun hideIg() {
        val v = igView ?: return
        try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) { }
        igView = null; igText = null; igBtn = null; igTopApplied = -1
    }

    private fun igGoHome() {
        val root = rootInActiveWindow ?: return
        val l = root.findAccessibilityNodeInfosByViewId("$IG:id/feed_tab")
        var n: AccessibilityNodeInfo? = if (l != null && l.isNotEmpty()) l[0] else null
        while (n != null && !n.isClickable) n = n.parent
        n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
