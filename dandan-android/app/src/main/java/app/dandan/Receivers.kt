package app.dandan

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "앱 삭제 방지가 꺼져요. 정말 해제할까요?"
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Store.init(c)
        Scheduler.reschedule(c)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Store.init(c)
        if (Engine.phone(c) != null) {
            try {
                c.startActivity(Intent(c, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
        }
        Scheduler.reschedule(c)
    }
}

object Scheduler {
    fun reschedule(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val now = System.currentTimeMillis()
            var t = Long.MAX_VALUE
            for (s in Store.schedules()) {
                if (!s.enabled || s.target != "phone") continue
                val n = Engine.nextStart(s, now) ?: continue
                if (n < t) t = n
            }
            val pi = PendingIntent.getBroadcast(
                ctx, 1, Intent(ctx, AlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
            if (t == Long.MAX_VALUE) return
            try {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms())
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
                else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            }
        } catch (e: Exception) {
        }
    }
}

object Notifier {
    fun post(c: Context, id: Int, title: String, text: String) {
        try {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("lock", "잠금 알림", NotificationManager.IMPORTANCE_DEFAULT))
            val n = Notification.Builder(c, "lock")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            nm.notify(id, n)
        } catch (e: Exception) {
        }
    }
}
