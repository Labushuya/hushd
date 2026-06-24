// SPDX-License-Identifier: GPL-3.0-or-later
package dev.labushuya.hushd.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import dev.labushuya.hushd.service.accessibility.AutostopAccessibilityService

object PermissionHelper {

    /**
     * Returns true if [AutostopAccessibilityService] is currently enabled and bound.
     * Checks via AccessibilityManager's list of enabled accessibility services.
     */
    fun isAccessibilityServiceEnabled(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val targetPkg = ctx.packageName
        val targetClass = AutostopAccessibilityService::class.java.name
        return enabled.any { info ->
            val id = info.id // format: "packageName/.ServiceClass"
            id.startsWith(targetPkg) && id.contains(targetClass.substringAfterLast('.').let { ".$it" }.let { it })
                || info.resolveInfo?.serviceInfo?.let {
                it.packageName == targetPkg && it.name == targetClass
            } == true
        }
    }

    /**
     * Returns true if Settings.canDrawOverlays() grants TYPE_APPLICATION_OVERLAY permission.
     */
    fun isOverlayPermissionGranted(ctx: Context): Boolean =
        Settings.canDrawOverlays(ctx)

    /**
     * Opens Android Accessibility Settings so the user can enable the service.
     */
    fun openAccessibilitySettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /**
     * Opens the Manage Overlay Permission screen for this app.
     */
    fun openOverlaySettings(ctx: Context) {
        ctx.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${ctx.packageName}"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
