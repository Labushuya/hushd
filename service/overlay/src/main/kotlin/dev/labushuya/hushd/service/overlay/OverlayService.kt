// SPDX-License-Identifier: GPL-3.0-or-later
package dev.labushuya.hushd.service.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dagger.hilt.android.AndroidEntryPoint
import dev.labushuya.hushd.core.automation.BulkAutostopEngine
import dev.labushuya.hushd.service.overlay.ui.OverlayContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground Service (SPECIAL_USE), der ein TYPE_APPLICATION_OVERLAY-Fenster mit
 * Compose-Progress-UI über dem Phone-Manager anzeigt.
 *
 * STOLPERFALLE: Compose im Window-Overlay funktioniert nur, wenn der ComposeView
 * eigene ViewTree*-Owners hat. Der Service implementiert deshalb selbst
 * LifecycleOwner, SavedStateRegistryOwner und ViewModelStoreOwner.
 */
@AndroidEntryPoint
class OverlayService :
    Service(),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    @Inject lateinit var engine: BulkAutostopEngine

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null

    /** Guards against double-remove which throws an exception from WindowManager. */
    private var viewAdded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        startInForeground()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        attachOverlay()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        observeEngineState()
        Timber.tag(TAG).i("OverlayService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            engine.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        serviceScope.cancel()
        removeOverlayView()
        store.clear()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private fun attachOverlay() {
        val cv = ComposeView(this).apply {
            // STOLPERFALLE: ViewTree-Owners MÜSSEN vor setContent gesetzt sein,
            // sonst crasht Compose mit "ViewTreeLifecycleOwner not found".
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                MaterialTheme {
                    Surface {
                        val state by engine.state.collectAsState()
                        OverlayContent(
                            state = state,
                            onCancel = {
                                // cancel() resets state to Idle → observeEngineState()
                                // picks up the Idle emission and calls stopSelf().
                                engine.cancel()
                            },
                            onDismiss = {
                                // Called by OverlayContent after the auto-dismiss delay
                                // (State.Done). The service stops itself which triggers
                                // onDestroy → removeOverlayView().
                                stopSelf()
                            },
                        )
                    }
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WRAP_CONTENT,
            WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (resources.displayMetrics.density * 12).toInt()
            y = (resources.displayMetrics.density * 64).toInt()
        }
        windowManager.addView(cv, params)
        overlayView = cv
        viewAdded = true
    }

    /**
     * Removes the overlay window safely. Idempotent — safe to call multiple times.
     */
    private fun removeOverlayView() {
        if (viewAdded) {
            try {
                windowManager.removeView(overlayView)
            } catch (_: Exception) {
                // View may already be detached (e.g. process kill race); ignore.
            }
            viewAdded = false
        }
        overlayView = null
    }

    /**
     * Watches engine state on a background coroutine.
     *
     * - [BulkAutostopEngine.State.Idle]: Engine was cancelled → remove overlay immediately.
     * - [BulkAutostopEngine.State.Done] / [BulkAutostopEngine.State.Error]: Automation finished
     *   or errored. The OverlayContent handles the 2500 ms auto-dismiss for Done by calling
     *   [onDismiss] → stopSelf(). For Error we add a safety 2500 ms fallback here so the
     *   service always stops even if OverlayContent's close button is never tapped.
     *
     * We skip the initial Idle emission with a flag to avoid immediately self-stopping
     * before any run has started.
     */
    private fun observeEngineState() {
        serviceScope.launch {
            var seenNonIdle = false
            engine.state.collect { state ->
                when (state) {
                    is BulkAutostopEngine.State.Idle -> {
                        if (seenNonIdle) {
                            Timber.tag(TAG).i("Engine back to Idle — stopping OverlayService")
                            stopSelf()
                        }
                    }
                    is BulkAutostopEngine.State.Done -> {
                        seenNonIdle = true
                        // OverlayContent will call onDismiss → stopSelf() after 2500 ms.
                        // This is a safety net: if the view is somehow gone, stop after 3 s.
                        delay(3_000)
                        Timber.tag(TAG).i("Done safety timeout — stopping OverlayService")
                        stopSelf()
                    }
                    is BulkAutostopEngine.State.Error -> {
                        seenNonIdle = true
                        // Show the error briefly, then stop automatically.
                        delay(2_500)
                        Timber.tag(TAG).i("Error auto-dismiss — stopping OverlayService")
                        stopSelf()
                    }
                    else -> seenNonIdle = true
                }
            }
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name_automation),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.notification_channel_description_automation)
                    setShowBadge(false)
                }
            )
        }
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.notification_title_running))
            .setContentText(getString(R.string.notification_text_running))
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .addAction(0, getString(R.string.notification_action_cancel), stopPi)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val TAG = "OverlaySvc"
        private const val CHANNEL_ID = "overlay_progress"
        private const val NOTIF_ID = 4711
        const val ACTION_STOP = "de.delos.autostartmgr.OVERLAY_STOP"

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))
        fun stop(ctx: Context) =
            ctx.stopService(Intent(ctx, OverlayService::class.java))
    }
}
