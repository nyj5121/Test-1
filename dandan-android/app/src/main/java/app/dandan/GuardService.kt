package app.dandan

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
private val TAB_IDS = listOf("feed_tab", "search_tab", "clips_tab", "profile_tab", "direct_tab", "creation_tab")
private val TAB_DESCS = setOf("홈", "검색 및 탐색", "릴스", "프로필", "메시지", "Home", "Search and Explore", "Reels", "Profile", "Messages")

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

        /** 잠금 화면에서 허용 앱을 연 직후 몇 초 동안은 런처 같은 전환 화면을 봐준다. */
        @Volatile
        var graceUntil = 0L

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
        helperCache.clear()
    }

    // ---------- 1초마다 ----------
    private fun onTick() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRefresh > 60_000) {
            lastRefresh = now
            refreshPkgs()
            Promises.rollover()
            Store.prune()
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
        Approvals.tick(this)

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

        // 마지막으로 본 창이 아니라 "지금 실제로 앞에 있는 창"을 기준으로 한다.
        // (잠깐 떴다 사라진 창 때문에 허용 앱 위에 잠금 화면이 덮이지 않게)
        val act = activePkg()
        val actOk = act != null && act !in imePkgs && act != "com.android.systemui" && act != "android"
        if (actOk) fgPkg = act
        val fg = fgPkg ?: return
        if (phone != null) {
            if (actOk && fg != packageName && !allowedDuring(fg) && !inGrace()) {
                logBlock("tick", fg, "")
                goLock()
            }
        } else {
            if (actOk && Dumb.active(this) && fg != packageName && !dumbAllowed(fg)) { openDumb(); return }
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
        if (pkg == packageName) {
            if (cls.startsWith("app.dandan.")) fgPkg = pkg
            // 바보폰 시간에는 단단 설정 화면에 들어갈 수 없다
            if (cls == "app.dandan.MainActivity" && Dumb.active(this) && Engine.phone(this) == null) openDumb()
            return
        }
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
            // 종료·재부팅 확인 창만 닫는다. 그 밖의 시스템 창(공유 창, 권한 창 등)은 허용 앱이 쓸 수 있게 둔다.
            if (phone != null && Store.ironMode && Store.ironPopup &&
                (cls.contains("Shutdown", true) || cls.contains("Reboot", true))
            ) performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        fgPkg = pkg
        if (pkg != IG) hideIg()

        if (phone != null) {
            if (!allowedDuring(pkg)) violation(pkg, cls)
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
        if (isAlarmApp(pkg)) return true
        if (helperOk(pkg)) return true
        return false
    }

    /** 알람이 울릴 때 끌 수 있어야 해서 시계 앱은 잠금·바보폰 중에도 허용한다. */
    private fun isAlarmApp(pkg: String): Boolean = pkg.contains("deskclock") || pkg.contains("clockpackage")

    private val helperCache = HashMap<String, Boolean>()

    /**
     * 런처 아이콘이 없는 시스템 앱(권한 확인 창, 파일·사진 선택기 등)은 허용 앱이 띄울 수 있어서 허용한다.
     * 홈 화면, 설정, 앱 설치 화면은 어떤 경우에도 허용하지 않는다.
     */
    private fun helperOk(pkg: String): Boolean {
        if (pkg in homePkgs || pkg in Dumb.ALWAYS_BLOCKED) return false
        if (pkg.contains("settings", true) || pkg.contains("installer", true)) return false
        helperCache[pkg]?.let { return it }
        val ok = try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            system && packageManager.getLaunchIntentForPackage(pkg) == null
        } catch (e: Exception) { false }
        helperCache[pkg] = ok
        return ok
    }

    private fun activePkg(): String? = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }

    private fun inGrace(): Boolean = SystemClock.elapsedRealtime() < graceUntil

    private var violationSeq = 0

    private fun isHighRisk(pkg: String): Boolean =
        pkg in homePkgs || pkg in Dumb.ALWAYS_BLOCKED || pkg.contains("settings", true) || pkg.contains("installer", true)

    /** 허용되지 않은 창이 떴을 때. 위험한 곳은 바로, 나머지는 0.35초 뒤에도 앞에 있을 때만 막는다. */
    private fun violation(pkg: String, cls: String) {
        if (inGrace() && pkg in homePkgs) { logBlock("grace", pkg, cls); return }
        if (isHighRisk(pkg)) { logBlock("block", pkg, cls); goLock(); return }
        val seq = ++violationSeq
        handler.postDelayed({
            if (seq != violationSeq) return@postDelayed
            if (Engine.phone(this) == null) return@postDelayed
            val act = activePkg()
            if (act != null && allowedDuring(act)) {
                logBlock("skip", "$pkg (앞=$act)", cls)
                return@postDelayed
            }
            logBlock("block", pkg, cls)
            goLock()
        }, 350)
    }

    private val lockLog = ArrayList<String>()

    /** 잠금 중 막거나 봐준 기록. 설정 → 잠금 차단 기록에서 볼 수 있다(원인 찾기용). */
    private fun logBlock(action: String, pkg: String, cls: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(lockLog) {
            lockLog.add("$t $action $pkg ${cls.substringAfterLast('.')}".trim())
            while (lockLog.size > 40) lockLog.removeAt(0)
            Store.put("lockLog", lockLog.joinToString("\n"))
        }
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
        if (isAlarmApp(pkg)) return true
        if (helperOk(pkg)) return true
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
    /** 화면 위에 씌울 가림막 한 칸. btn: 0 없음, 1 "홈으로", 2 "뒤로". */
    private class Region(val key: String, val rect: Rect, val text: String, val alpha: Int, val btn: Int)

    private val igViews = HashMap<String, FrameLayout>()
    private val igTexts = HashMap<String, TextView>()
    private val igBtns = HashMap<String, Button>()
    private val igRects = HashMap<String, Rect>()
    private var lastIgScan = 0L
    private var lastDiagWrite = 0L
    private val igDumps = LinkedHashMap<String, String>()

    private fun statusBarH(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 90
    }

    /** 내비게이션 바까지 포함한 실제 화면 크기. */
    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            return Pair(b.width(), b.height())
        }
        val m = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(m)
        return Pair(m.widthPixels, m.heightPixels)
    }

    /**
     * 인스타 화면을 훑어서 하단 탭 바가 보이면 탭 상태에 맞게 가림막을 정한다.
     *  - 릴스·탐색·프로필 탭 버튼 위에는 작은 막을 씌워 누르지 못하게 한다. (홈·메시지 탭은 그대로)
     *  - 홈 탭: 스토리 줄 아래 피드를 가린다.
     *  - 메시지 탭(DM 목록): 아무것도 가리지 않는다.
     *  - 릴스·탐색·프로필 화면: 내용을 가리고 "홈으로" 버튼을 보여 준다.
     *  - 탭 바가 없는 화면은 DM 대화·스토리라서 두고, 릴스 재생 화면(DM으로 받은 릴스 등)만 막는다.
     * 못 알아보면 아무것도 하지 않는다.
     */
    private fun igScan() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastIgScan < 400) return
        lastIgScan = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != IG) return
        val (sw, sh) = screenSize()
        val diag = Store.igDiag

        var tabsFound = 0
        var selectedTab: String? = null
        var trayBottom = -1
        var tabBarTop = -1
        var hasDm = false
        var hasStory = false
        var hasClipsViewer = false
        val tabRects = HashMap<String, Rect>()
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
                val key = id.ifEmpty { desc }
                tabRects[key] = Rect(r)
                if (node.isSelected) selectedTab = key
            }
            if (id == "tab_bar") tabBarTop = r.top
            if (id == "clips_viewer_view_pager") hasClipsViewer = true
            // 화면 밖에 미리 만들어 둔 홈 피드의 스토리 줄은 무시한다
            if (id.contains("tray", true) && r.right > 0 && r.left < sw && r.top < sh * 4 / 10 && r.bottom > trayBottom) trayBottom = r.bottom
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
            val key = "tabs=$tabBar sel=${selectedTab ?: "?"} tray=${trayBottom > 0} dm=$hasDm story=$hasStory clips=$hasClipsViewer"
            igDumps[key] = "screen=${sw}x$sh tabsFound=$tabsFound trayBottom=$trayBottom\n$dump"
            if (now - lastDiagWrite > 2000) {
                lastDiagWrite = now
                val sb = StringBuilder()
                igDumps.forEach { (k, v) -> sb.append("=== ").append(k).append(" ===\n").append(v).append('\n') }
                Store.put("igDump", sb.toString())
            }
        }

        if (!Store.igMode) { hideIg(); return }

        val top0 = statusBarH()
        val regions = ArrayList<Region>()
        if (!tabBar) {
            if (hasClipsViewer) {
                if (Store.igReels) regions.addAll(reelLock(top0, sh, sw))
                else regions.add(Region("reel", Rect(0, top0, sw, sh), "릴스는 볼 수 없어요.", 0xF5, 2))
            }
            applyRegions(regions)
            return
        }

        val dmTabs = setOf("direct_tab", "메시지", "Messages")
        val homeTabs = setOf("feed_tab", "홈", "Home")
        val reelTabs = setOf("clips_tab", "릴스", "Reels")
        val reelsLocked = Store.igReels
        val blockedTabs = setOf("clips_tab", "search_tab", "profile_tab", "릴스", "검색 및 탐색", "프로필", "Reels", "Search and Explore", "Profile")
            .let { if (reelsLocked) it - reelTabs else it }
        val bottom = if (tabBarTop > 0) tabBarTop else tabRects.values.minOf { it.top }

        for ((k, rc) in tabRects) {
            if (k in blockedTabs) regions.add(Region("tab_$k", Rect(rc), "🔒", 0xE6, 0))
        }
        val sel = selectedTab
        when {
            sel == null || sel in dmTabs -> { }   // 메시지 탭이거나 못 알아봄: 내용은 그대로 둔다
            reelsLocked && sel in reelTabs -> regions.addAll(reelLock(top0, bottom, sw))
            sel in blockedTabs ->
                regions.add(Region("content", Rect(0, top0, sw, bottom), "이 화면은 쓸 수 없어요.\n아래에서 홈(스토리)이나 메시지 탭을 누르세요.", 0xF5, 1))
            sel in homeTabs -> {
                val manual = if (Store.igTop > 0) sh * Store.igTop / 100 else 0
                val top = when {
                    manual > 0 -> manual
                    trayBottom > 0 -> trayBottom
                    else -> top0 + (bottom - top0) * 28 / 100
                }.coerceIn(top0, bottom - 100)
                regions.add(Region("content", Rect(0, top, sw, bottom), "피드는 가려져 있어요.\n위에서 스토리를 보거나\n아래 메시지 탭으로 가세요.", 0xF2, 0))
            }
        }
        applyRegions(regions)
    }

    /**
     * 릴스 스크롤 막기: 보이는 릴스는 그대로 두되, 그 위에 투명한 막을 덮어 터치를 전부 삼킨다.
     * 위에는 안내 띠를 둔다. 탭 바는 덮지 않아서 홈·메시지로 갈 수 있다.
     */
    private fun reelLock(top0: Int, bottom: Int, sw: Int): List<Region> {
        val bh = (44 * resources.displayMetrics.density).toInt()
        return listOf(
            Region("reelLock", Rect(0, top0 + bh, sw, bottom), "", 0x00, 0),
            Region("reelBanner", Rect(0, top0, sw, top0 + bh), "릴스는 넘길 수 없어요 · 보이는 한 개만", 0xE0, 0)
        )
    }

    /** 원하는 가림막 목록에 맞춰 창을 만들고, 고치고, 없앤다. */
    private fun applyRegions(list: List<Region>) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val keep = list.map { it.key }.toSet()
        for (k in igViews.keys.toList()) {
            if (k !in keep) {
                try { wm.removeView(igViews[k]) } catch (e: Exception) { }
                igViews.remove(k); igTexts.remove(k); igBtns.remove(k); igRects.remove(k)
            }
        }
        val d = resources.displayMetrics.density
        for (rg in list) {
            val w = rg.rect.width()
            val h = rg.rect.height()
            if (w <= 0 || h <= 0) continue
            val color = (rg.alpha shl 24) or 0x101218
            val existing = igViews[rg.key]
            if (existing == null) {
                val f = FrameLayout(this)
                f.setBackgroundColor(color)
                f.setOnTouchListener { _, _ -> true }
                val t = TextView(this)
                t.setTextColor(0xFFECEEF3.toInt())
                t.textSize = 16f
                t.gravity = Gravity.CENTER
                f.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                val b = Button(this)
                b.setOnClickListener { if (b.tag == 2) performGlobalAction(GLOBAL_ACTION_BACK) else igGoHome() }
                val blp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM)
                blp.bottomMargin = (90 * d).toInt()
                f.addView(b, blp)
                val lp = WindowManager.LayoutParams(
                    w, h,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.START
                lp.x = rg.rect.left
                lp.y = rg.rect.top
                try { wm.addView(f, lp) } catch (e: Exception) { continue }
                igViews[rg.key] = f; igTexts[rg.key] = t; igBtns[rg.key] = b; igRects[rg.key] = Rect(rg.rect)
            } else if (igRects[rg.key] != rg.rect) {
                val lp = existing.layoutParams as WindowManager.LayoutParams
                lp.x = rg.rect.left
                lp.y = rg.rect.top
                lp.width = w
                lp.height = h
                try { wm.updateViewLayout(existing, lp) } catch (e: Exception) { }
                igRects[rg.key] = Rect(rg.rect)
            }
            igViews[rg.key]?.setBackgroundColor(color)
            igTexts[rg.key]?.text = rg.text
            igBtns[rg.key]?.let {
                it.tag = rg.btn
                it.text = if (rg.btn == 2) "뒤로" else "홈으로"
                it.visibility = if (rg.btn == 0) View.GONE else View.VISIBLE
            }
        }
    }

    private fun hideIg() {
        if (igViews.isEmpty()) return
        applyRegions(emptyList())
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
