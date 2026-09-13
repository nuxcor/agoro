package com.agoro.tv.recording

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Programme reminders: a one-shot alarm a minute before something starts.
 *
 * All that is left of what was the recording package. Recording itself was
 * removed on 2026-08-27 — it wrote unbounded files to a box with little room,
 * and it started on a single unconfirmed press from the player's options menu,
 * so it was as easy to begin by accident as on purpose.
 *
 * Reminders survive because they are a different thing that happened to live
 * here: they cost nothing on disk, they were already the fallback wherever a
 * recording could not be made, and they are now what the guide's OK does in
 * every case rather than only some.
 */
object RecordingScheduler {

    /**
     * The alarm's identity, built in exactly one place.
     *
     * Action and data are what make PendingIntents distinct — extras are NOT
     * part of the comparison — so without them every reminder is the same
     * PendingIntent and FLAG_UPDATE_CURRENT quietly replaces the previous one.
     * Keyed on the programme's id, not its title: "BBC News at Ten" tonight and
     * tomorrow are two reminders, and the same programme on two channels is two
     * more.
     *
     * Setting and cancelling must agree on this to the byte, or cancel silently
     * matches nothing and the alarm still fires after the app has told the
     * viewer it is gone. That is why it is a function rather than two copies.
     */
    private fun reminderIntent(
        context: Context,
        channelName: String,
        program: com.agoro.tv.data.EpgProgram,
    ) = Intent(context, ReminderReceiver::class.java)
        .setAction("com.agoro.tv.REMINDER")
        .setData(android.net.Uri.parse("dzidzi://reminder/${program.id.hashCode()}"))
        .putExtra("title", program.title)
        .putExtra("channel", channelName)

    fun scheduleReminder(context: Context, channelName: String, program: com.agoro.tv.data.EpgProgram) {
        // Kept verbatim from before recording was removed. Rewriting it from
        // scratch lost three things at once, all of them silent: the exact-alarm
        // permission guard, the identity that keeps two reminders apart, and the
        // clamp that lets one fire for a programme already inside the minute.
        val intent = reminderIntent(context, channelName, program)
        val pi = PendingIntent.getBroadcast(
            context,
            program.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Clamped, not skipped. A programme starting in thirty seconds still
        // gets a reminder — it just fires now — where returning early would
        // leave the caller saying "Reminder set" over nothing.
        val triggerAt = (program.startMs - 60_000).coerceAtLeast(System.currentTimeMillis())
        val am = alarmManager(context)
        // targetSdk 36, so on 31+ this app does not hold SCHEDULE_EXACT_ALARM by
        // default. Without the fallback setExactAndAllowWhileIdle throws
        // SecurityException, and a runCatching around it turns every reminder
        // into a toast and no alarm.
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            // Inexact still fires, but Doze on a box left idling overnight can
            // defer it well past kick-off — so ask for the permission as well,
            // once, and let the next reminder be exact.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            requestExactAlarms(context)
        }
    }

    /**
     * Takes a reminder back off the alarm queue.
     *
     * Returns whether there was one to cancel, so the caller can say
     * "Reminder removed" only when something was actually removed.
     *
     * FLAG_NO_CREATE is the whole trick: it asks the system for the existing
     * PendingIntent and answers null when none matches, which is both the
     * cancel handle and the check. Cancelling twice — the alarm AND the
     * PendingIntent itself — because [AlarmManager.cancel] drops the scheduled
     * fire but leaves the token behind for the next FLAG_NO_CREATE lookup to
     * find, which would make a cancelled reminder look like a live one.
     */
    fun cancelReminder(
        context: Context,
        channelName: String,
        program: com.agoro.tv.data.EpgProgram,
    ): Boolean {
        val pi = PendingIntent.getBroadcast(
            context,
            program.id.hashCode(),
            reminderIntent(context, channelName, program),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return false
        alarmManager(context).cancel(pi)
        pi.cancel()
        return true
    }

    /**
     * Sends the viewer to the system's exact-alarm switch.
     *
     * SCHEDULE_EXACT_ALARM is declared in the manifest and denied by default
     * from API 31, and the only code that ever asked for it lived in the
     * recording scheduler that was deleted. Without this the permission could
     * never be granted, canScheduleExactAlarms() answered false for ever, and
     * every reminder quietly downgraded to an alarm Doze may sit on — while
     * the caller still said "Reminder set".
     *
     * Once per process. A viewer who declines should not be asked again every
     * time they set a reminder.
     */
    @Volatile private var asked = false

    private fun requestExactAlarms(context: Context) {
        if (Build.VERSION.SDK_INT < 31 || asked) return
        asked = true
        runCatching {
            context.startActivity(
                Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM")
                    .setData(android.net.Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun alarmManager(context: Context) =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: return
        val channel = intent.getStringExtra("channel") ?: ""
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    "reminders",
                    "Programme reminders",
                    android.app.NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }
        val notification = androidx.core.app.NotificationCompat.Builder(context, "reminders")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Starting soon: $title")
            .setContentText("On $channel in about a minute")
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(title.hashCode(), notification) }
    }
}
