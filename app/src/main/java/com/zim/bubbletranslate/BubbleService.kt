package com.zim.bubbletranslate

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * The part of the app that stays alive while you read in another app.
 * It owns the floating bubble, the screen picture and the translation layer.
 *
 * Android only lets an app look at the screen from a "foreground service":
 * one that shows a permanent notification so you always know it is running.
 */
class BubbleService : Service() {

    companion object {
        const val ACTION_START = "com.zim.bubbletranslate.START"
        const val ACTION_STOP = "com.zim.bubbletranslate.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "bubble"
        private const val NOTIF_ID = 1

        /** Lets the settings screen show the right button label. */
        @Volatile
        var running = false
            private set
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var projection: MediaProjection? = null
    private var grabber: ScreenGrabber? = null
    private var bubble: BubbleView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var overlay: OverlayView? = null
    private var busy = false

    /** Android calls this when screen access ends, for example when the phone locks. */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post { stopSelf() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_START -> start(intent)
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        // The notification has to be up before Android hands over the screen.
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
        if (projection != null) return

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            stopSelf()
            return
        }
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("no screen access")
            p.registerCallback(projectionCallback, main)
            projection = p
            grabber = ScreenGrabber(this, p, main).also { it.start() }
            addBubble()
            running = true
        } catch (e: Exception) {
            toast("Couldn't start reading the screen. Try Start again.")
            stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        removeOverlay()
        bubble?.let { safeRemove(it) }
        bubble = null
        grabber?.stop()
        grabber = null
        projection?.let {
            try { it.unregisterCallback(projectionCallback) } catch (e: Exception) { }
            try { it.stop() } catch (e: Exception) { }
        }
        projection = null
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The phone was rotated. Old positions no longer line up.
        removeOverlay()
        try { grabber?.resizeIfNeeded() } catch (e: Exception) { }
    }

    // ---- The bubble -------------------------------------------------------

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val d = resources.displayMetrics.density
        val g = grabber
        val screenW = g?.width ?: resources.displayMetrics.widthPixels
        val screenH = g?.height ?: resources.displayMetrics.heightPixels

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = Prefs.bubbleX(this, screenW - (64 * d).toInt())
        lp.y = Prefs.bubbleY(this, (screenH * 0.35f).toInt())

        val view = BubbleView(this)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false

        // Drag to move it out of the way. A touch that barely moves is a tap.
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    dragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragged && (abs(dx) > slop || abs(dy) > slop)) dragged = true
                    if (dragged) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try { wm.updateViewLayout(view, lp) } catch (ex: Exception) { }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) Prefs.setBubblePos(this, lp.x, lp.y) else onBubbleTap()
                }
            }
            true
        }

        wm.addView(view, lp)
        bubble = view
        bubbleLp = lp
    }

    private fun onBubbleTap() {
        // A second tap closes the translation.
        if (overlay != null) {
            removeOverlay()
            return
        }
        if (busy) return
        val g = grabber ?: return
        val b = bubble ?: return
        busy = true

        // Hide the bubble so it isn't in the picture, give the screen a
        // moment to redraw without it, then take the picture.
        b.visibility = View.INVISIBLE
        main.postDelayed({
            val bmp: Bitmap? = try { g.grab() } catch (e: Exception) { null }
            b.visibility = View.VISIBLE
            if (bmp == null) {
                finish("Couldn't see the screen. Stop the bubble and start it again.")
                return@postDelayed
            }
            b.busy = true
            worker.execute {
                try {
                    val result = Pipeline.run(this, bmp) { msg -> main.post { toast(msg) } }
                    main.post { show(result) }
                } catch (e: Pipeline.Stop) {
                    main.post { finish(e.message) }
                } catch (e: Exception) {
                    main.post { finish("Something went wrong. Try again.") }
                } finally {
                    bmp.recycle()
                }
            }
        }, 220)
    }

    private fun finish(message: String?) {
        busy = false
        bubble?.busy = false
        if (message != null) toast(message)
    }

    // ---- The translation layer --------------------------------------------

    private fun show(result: PageResult) {
        finish(null)
        removeOverlay()
        val view = OverlayView(this, result) { removeOverlay() }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm.addView(view, lp)
            overlay = view
        } catch (e: Exception) {
            toast("Couldn't draw over this app.")
            return
        }
        // Put the bubble back on top of the translation layer.
        val b = bubble
        val blp = bubbleLp
        if (b != null && blp != null) {
            safeRemove(b)
            try { wm.addView(b, blp) } catch (e: Exception) { }
        }
    }

    private fun removeOverlay() {
        overlay?.let { safeRemove(it) }
        overlay = null
    }

    private fun safeRemove(v: View) {
        try { wm.removeViewImmediate(v) } catch (e: Exception) { }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ---- The permanent notification ---------------------------------------

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Translate bubble", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val icon = smallIcon()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle("Bubble Translate is on")
            .setContentText("Tap the bubble to translate what's on screen.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(icon, "Stop", stop).build())
            .build()
    }

    /** Status bar icons must be a plain white shape, so this draws a white "A". */
    private fun smallIcon(): Icon {
        val size = (24 * resources.displayMetrics.density).toInt().coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = size * 0.8f
        }
        val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
        Canvas(bmp).drawText("A", size / 2f, baseline, paint)
        return Icon.createWithBitmap(bmp)
    }
}
