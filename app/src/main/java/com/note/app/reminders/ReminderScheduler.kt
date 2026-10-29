package com.note.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.note.app.data.Note

object ReminderScheduler {
    fun schedule(context: Context, note: Note) {
        val time = note.reminderAt ?: return
        if (time <= System.currentTimeMillis()) return

        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pendingIntent(context, note.id, note.title, note.content, note.repeat)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !alarm.canScheduleExactAlarms()
            ) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
            } else {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
            }
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
        }
    }

    fun cancel(context: Context, noteId: Long) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pendingIntent(context, noteId, "", "", "NONE"))
    }

    private fun pendingIntent(
        context: Context,
        id: Long,
        title: String,
        content: String,
        repeat: String
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("note_id", id)
            putExtra("title", title.ifBlank { "Note reminder" })
            putExtra("content", content)
            putExtra("repeat", repeat)
        }
        return PendingIntent.getBroadcast(
            context, id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
