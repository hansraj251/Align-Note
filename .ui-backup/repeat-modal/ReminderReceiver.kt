package com.note.app.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.note.app.MainActivity
import com.note.app.data.NoteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("note_id", 0L)
        val title = intent.getStringExtra("title") ?: "Note reminder"
        val content = intent.getStringExtra("content").orEmpty()
        val repeat = intent.getStringExtra("repeat") ?: "NONE"

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel("note_reminders", "Note reminders",
                    NotificationManager.IMPORTANCE_HIGH)
            )
        }

        val openApp = PendingIntent.getActivity(
            context, id.toInt(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, "note_reminders")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(content.ifBlank { "Your reminder is due." })
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.ifBlank {
                "Your reminder is due."
            }))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(id.toInt(), notification)
        } catch (_: SecurityException) {
            // Notification permission may not have been granted.
        }

        if (repeat != "NONE") {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = System.currentTimeMillis()
                when (repeat) {
                    "DAILY" -> add(Calendar.DAY_OF_YEAR, 1)
                    "WEEKLY" -> add(Calendar.WEEK_OF_YEAR, 1)
                }
            }
            CoroutineScope(Dispatchers.IO).launch {
                val dao = NoteDatabase.get(context).noteDao()
                val note = dao.getById(id) ?: return@launch
                dao.update(note.copy(reminderAt = calendar.timeInMillis))
                ReminderScheduler.schedule(context, note.copy(reminderAt = calendar.timeInMillis))
            }
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                val dao = NoteDatabase.get(context).noteDao()
                dao.getById(id)?.let { dao.update(it.copy(reminderAt = null)) }
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        CoroutineScope(Dispatchers.IO).launch {
            NoteDatabase.get(context).noteDao().getAll().forEach {
                if (it.reminderAt != null && it.reminderAt > System.currentTimeMillis()) {
                    ReminderScheduler.schedule(context, it)
                }
            }
        }
    }
}
