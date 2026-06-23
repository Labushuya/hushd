// SPDX-License-Identifier: GPL-3.0-or-later
package dev.labushuya.hushd.boot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.labushuya.hushd.R
import timber.log.Timber

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        Timber.tag(TAG).i("BootCompleted received — checking a11y status")

        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC)
        val ours = enabled.any { svc ->
            svc.resolveInfo?.serviceInfo?.packageName == ctx.packageName
        }
        if (ours) {
            Timber.tag(TAG).i("Service already enabled post-boot — no action")
            return
        }
        postReactivationNotification(ctx)
    }

    private fun postReactivationNotification(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL_ID) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Service-Status", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Hinweis, wenn der Accessibility-Service deaktiviert ist"
                }
            )
        }
        val openA11y = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            ctx, 0, openA11y,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_warn)
            .setContentTitle("Autostart Manager")
            .setContentText("Accessibility-Service ist deaktiviert. Bitte erneut aktivieren.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        ContextCompat.getSystemService(ctx, NotificationManager::class.java)
            ?.notify(NOTIF_ID, notif)
    }

    companion object {
        private const val TAG = "BootRcvr"
        private const val CHANNEL_ID = "service_status"
        private const val NOTIF_ID = 4712
    }
}
