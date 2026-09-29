package com.example.aikeyboardoverlay.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.aikeyboardoverlay.MainActivity
import com.example.aikeyboardoverlay.R
import com.example.aikeyboardoverlay.common.AppBroadcasts
import kotlin.math.abs

class FloatingUIService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var downTime = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AppBroadcasts.ACTION_PROCESSING_STATE_CHANGED) {
                val state = intent.getIntExtra(AppBroadcasts.EXTRA_STATE, AppBroadcasts.STATE_IDLE)
                applyState(state)
            }
        }
    }

    companion object {
        private const val TAG = "FloatingUIService"
        private const val NOTIFICATION_CHANNEL_ID = "floating_ui_service_channel"
        private const val NOTIFICATION_ID = 1001
        private const val CLICK_DRAG_TOLERANCE = 10
        private const val CLICK_MAX_DURATION_MS = 200L
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addFloatingButton()

        LocalBroadcastManager.getInstance(this).registerReceiver(
            stateReceiver,
            IntentFilter(AppBroadcasts.ACTION_PROCESSING_STATE_CHANGED)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun addFloatingButton() {
        if (floatingView != null) return

        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_button, null)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        val buttonView = floatingView?.findViewById<ImageView>(R.id.ivFloatingButton)
        buttonView?.setOnTouchListener(createDragTouchListener())

        try {
            windowManager.addView(floatingView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating view", e)
        }
    }

    private fun createDragTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { view, event ->
            val currentParams = params ?: return@OnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = currentParams.x
                    initialY = currentParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    currentParams.x = initialX + dx
                    currentParams.y = initialY + dy
                    windowManager.updateViewLayout(floatingView, currentParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)
                    val elapsed = System.currentTimeMillis() - downTime
                    val wasClick = dx < CLICK_DRAG_TOLERANCE &&
                        dy < CLICK_DRAG_TOLERANCE &&
                        elapsed < CLICK_MAX_DURATION_MS
                    if (wasClick) {
                        view.performClick()
                        onFloatingButtonClicked()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onFloatingButtonClicked() {
        Log.d(TAG, "Floating button clicked")
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent(AppBroadcasts.ACTION_FLOATING_BUTTON_CLICKED))
        applyState(AppBroadcasts.STATE_PROCESSING)
    }

    private fun applyState(state: Int) {
        val buttonView = floatingView?.findViewById<ImageView>(R.id.ivFloatingButton) ?: return
        val progressBar = floatingView?.findViewById<ProgressBar>(R.id.progressBar) ?: return

        when (state) {
            AppBroadcasts.STATE_PROCESSING -> {
                buttonView.setBackgroundResource(R.drawable.bg_floating_button_processing)
                progressBar.visibility = View.VISIBLE
            }
            AppBroadcasts.STATE_ERROR -> {
                buttonView.setBackgroundResource(R.drawable.bg_floating_button_error)
                progressBar.visibility = View.GONE
                mainHandler.postDelayed({ applyState(AppBroadcasts.STATE_IDLE) }, 1200L)
            }
            else -> {
                buttonView.setBackgroundResource(R.drawable.bg_floating_button_idle)
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun startForegroundWithNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "Floating Assistant", NotificationManager.IMPORTANCE_MIN
            ).apply { description = "Keeps the floating assistant button active" }
            notificationManager.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Floating Assistant Active")
            .setContentText("Tap to open the app")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
        removeFloatingView()
    }

    private fun removeFloatingView() {
        floatingView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "View already removed or not attached", e)
            }
        }
        floatingView = null
        params = null
    }
}
