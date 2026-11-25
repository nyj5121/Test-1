package app.dandan

import android.app.AppOpsManager
import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import java.util.Calendar

object Apps {
    data class A(val pkg: String, val label: String)

    fun launchable(ctx: Context): List<A> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { A(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    fun label(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    fun homePackages(ctx: Context): Set<String> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return ctx.packageManager.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName }
            .filter { it != ctx.packageName }
            .toSet()
    }

    fun dialerPkg(ctx: Context): String? = try {
        (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage
    } catch (e: Exception) { null }

    fun smsPkg(ctx: Context): String? = try {
        Telephony.Sms.getDefaultSmsPackage(ctx)
    } catch (e: Exception) { null }

    fun open(ctx: Context, pkg: String) {
        try {
            val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
        }
    }

    fun usageTodayMs(ctx: Context): Long? {
        if (!Perm.usage(ctx)) return null
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val c = Calendar.getInstance()
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            val m = usm.queryAndAggregateUsageStats(c.timeInMillis, System.currentTimeMillis())
            m.values.filter { it.packageName != ctx.packageName }.sumOf { it.totalTimeInForeground }
        } catch (e: Exception) { null }
    }
}

object Perm {
    fun accessibility(c: Context) = GuardService.enabled(c)
    fun overlay(c: Context) = Settings.canDrawOverlays(c)

    fun usage(c: Context): Boolean = try {
        val ao = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) { false }

    fun notif(c: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun battery(c: Context): Boolean =
        (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)

    fun alarm(c: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

    fun admin(c: Context): Boolean =
        (c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
            .isAdminActive(ComponentName(c, AdminReceiver::class.java))

    /** 잠금이 제대로 돌려면 꼭 필요한 두 가지. */
    fun ready(c: Context) = accessibility(c) && overlay(c)
}
