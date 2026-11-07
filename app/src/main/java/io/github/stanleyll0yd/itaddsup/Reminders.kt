package io.github.stanleyll0yd.itaddsup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.time.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

object Reminders {
    fun configure(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!enabled) {
            manager.cancelUniqueWork("shift-reminders")
            return
        }
        val work = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.HOURS).build()
        manager.enqueueUniquePeriodicWork(
            "shift-reminders",
            ExistingPeriodicWorkPolicy.UPDATE,
            work,
        )
    }
}

class ReminderWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TrackerApplication
        if (!app.preferences.flow.first().reminders) return Result.success()
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return Result.success()
        val ledger = app.repository.ledger.first()
        val row = ledger.schedule ?: return Result.success()
        val schedule = row.domain()
        val now = Instant.now()
        // Include yesterday: an overnight shift may finish this morning.
        val today = now.atZone(schedule.zone).toLocalDate()
        for (date in listOf(today.minusDays(1), today)) {
            if (
                !schedule.works(date) ||
                    ledger.shifts.any {
                        it.date == date.toString() &&
                            it.domain().status !=
                                io.github.stanleyll0yd.itaddsup.domain.ShiftStatus.PLANNED
                    }
            )
                continue
            val rule =
                io.github.stanleyll0yd.itaddsup.domain.Earnings.ruleAt(
                    date,
                    ledger.rates.map { it.domain() },
                ) ?: continue
            if (app.preferences.wasNotified(date.toString())) continue
            val end = schedule.planned(date, rule).time.end
            if (now < end || Duration.between(end, now).toHours() >= 24) continue
            val pending =
                PendingIntent.getActivity(
                    app,
                    0,
                    Intent(app, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val manager = app.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    "shifts",
                    "Подтверждение смен",
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
            val notification =
                NotificationCompat.Builder(app, "shifts")
                    .setSmallIcon(R.drawable.ic_mark)
                    .setContentTitle("Смена закончилась?")
                    .setContentText("Проверьте фактическое время и подтвердите смену в приложении.")
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build()
            manager.notify(date.toEpochDay().toInt(), notification)
            app.preferences.markNotified(date.toString())
        }
        return Result.success()
    }
}
