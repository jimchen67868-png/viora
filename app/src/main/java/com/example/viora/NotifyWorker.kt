package com.example.viora

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** True while the app is on screen: no background notification is needed then. */
object AppState {
    @Volatile var foreground = false
}

/** Checks for new messages and activity about every 15 minutes (Android's minimum) and shows a notification. */
fun scheduleNotifyWorker(ctx: Context) {
    val request = PeriodicWorkRequestBuilder<NotifyWorker>(15, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("viora-notify", ExistingPeriodicWorkPolicy.KEEP, request)
}

class NotifyWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = ctx.getSharedPreferences("viora", Context.MODE_PRIVATE)
        val token = prefs.getString("token", null) ?: return Result.success()   // not logged in
        if (AppState.foreground) return Result.success()
        if (prefs.getString("push_token", null) != null) return Result.success()   // real push notifications are active on this phone
        val counts = try { Api(SERVER_URL, token).unreadCounts() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { return Result.success() }
        val messages = counts.first
        val activity = counts.second
        val total = messages + activity
        val last = prefs.getInt("notified_total", 0)
        prefs.edit().putInt("notified_total", total).apply()
        if (total <= last) return Result.success()   // nothing new since the last check or since you last looked
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        val parts = ArrayList<String>()
        if (messages > 0) parts.add(if (messages == 1) "1 new message" else "$messages new messages")
        if (activity > 0) parts.add(if (activity == 1) "1 new notification" else "$activity new notifications")
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val note = NotificationCompat.Builder(ctx, "viora_activity")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Viora")
            .setContentText(parts.joinToString("  ·  "))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(1001, note) } catch (e: SecurityException) { }
        return Result.success()
    }
}
