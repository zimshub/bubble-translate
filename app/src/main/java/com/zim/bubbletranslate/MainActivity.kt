package com.zim.bubbletranslate

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * The one screen of the app: pick the languages, then start or stop the bubble.
 * The layout is built in code, so there are no separate layout files to read.
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_CAPTURE = 7
        const val REQ_NOTIFY = 8

        // Black and blue, like X in dark mode.
        val BG = Color.BLACK
        val TEXT = Color.rgb(231, 233, 234)
        val MUTED = Color.rgb(113, 118, 123)
        val LINE = Color.rgb(47, 51, 54)
        val ACCENT = Color.rgb(29, 155, 240)
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var startButton: Button
    private lateinit var targets: List<String>
    private lateinit var sources: List<String>
    private var askedNotify = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Every language the translator knows, sorted by name.
        targets = Lang.all().sortedBy { Prefs.name(it) }
        // Only languages the text reader can see on screen, plus "work it out for me".
        sources = listOf(Prefs.AUTO) + targets.filter { it !in Prefs.UNREADABLE }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(32))
        }

        column.addView(text("Bubble Translate", 26f, TEXT, bold = true))
        column.addView(
            text("Translates whatever is on your screen, on top of the app you're reading in.", 15f, MUTED),
            margins(top = 8)
        )

        column.addView(text("Translate into", 13f, MUTED), margins(top = 32))
        column.addView(
            picker(targets.map { Prefs.name(it) }, targets.indexOf(Prefs.target(this)).coerceAtLeast(0)) { i ->
                val tag = targets[i]
                if (tag != Prefs.target(this)) {
                    Prefs.setTarget(this, tag)
                    fetchPack(tag)
                }
            },
            margins(top = 8)
        )

        column.addView(text("Language on the page", 13f, MUTED), margins(top = 24))
        column.addView(
            picker(
                sources.map { if (it == Prefs.AUTO) "Detect automatically" else Prefs.name(it) },
                sources.indexOf(Prefs.source(this)).coerceAtLeast(0)
            ) { i -> Prefs.setSource(this, sources[i]) },
            margins(top = 8)
        )

        startButton = Button(this).apply {
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable().apply {
                setColor(ACCENT)
                cornerRadius = dp(26).toFloat()
            }
            stateListAnimator = null
            setOnClickListener { onStartStop() }
        }
        column.addView(
            startButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(32) }
        )

        status = text("", 14f, MUTED)
        column.addView(status, margins(top = 16))

        column.addView(text("How to use it", 13f, MUTED), margins(top = 40))
        val steps = listOf(
            "1.  Press Start and allow the two permissions it asks for.",
            "2.  Open your comic, novel or document in any app.",
            "3.  Tap the blue bubble. The translation appears over the page.",
            "4.  Tap the screen to close it, then scroll or turn the page.",
            "Drag the bubble if it's in the way."
        )
        for (s in steps) column.addView(text(s, 15f, TEXT), margins(top = 10))

        column.addView(
            text(
                "It can read Latin-alphabet languages, Chinese, Japanese, Korean and Hindi. " +
                    "Each language needs a one-time download of about 30 MB.",
                13f, MUTED
            ),
            margins(top = 28)
        )

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(column)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun refresh() {
        startButton.text = if (BubbleService.running) "Stop bubble" else "Start bubble"
    }

    // ---- Start / stop ------------------------------------------------------

    private fun onStartStop() {
        if (BubbleService.running) {
            startService(Intent(this, BubbleService::class.java).setAction(BubbleService.ACTION_STOP))
            status.text = "Bubble is off."
            main.postDelayed({ refresh() }, 400)
            return
        }
        // Permission 1: draw on top of other apps. Android makes you switch
        // this on by hand in Settings, so send the reader there.
        if (!Settings.canDrawOverlays(this)) {
            status.text = "Switch on \"Display over other apps\" for Bubble Translate, come back, and press Start again."
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        // Optional: show the "bubble is on" notification (Android 13 and up asks first).
        if (Build.VERSION.SDK_INT >= 33 && !askedNotify &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotify = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            return
        }
        askForScreen()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // The bubble works with or without the notification permission.
        if (requestCode == REQ_NOTIFY) askForScreen()
    }

    /** Permission 2: look at the screen. Android shows its own warning box for this. */
    private fun askForScreen() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Ask for the whole screen, not a single app, so it works everywhere.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            val start = Intent(this, BubbleService::class.java)
                .setAction(BubbleService.ACTION_START)
                .putExtra(BubbleService.EXTRA_CODE, resultCode)
                .putExtra(BubbleService.EXTRA_DATA, data)
            startForegroundService(start)
            status.text = "Bubble is on. Open what you're reading and tap it."
            main.postDelayed({ refresh() }, 700)
        } else {
            status.text = "Screen access wasn't allowed, so the bubble has nothing to read."
        }
    }

    /** Downloads the chosen language now, so the first tap of the bubble isn't slow. */
    private fun fetchPack(tag: String) {
        if (tag == "en") {
            status.text = ""
            return
        }
        val name = Prefs.name(tag)
        status.text = "Getting the $name pack, about 30 MB..."
        worker.execute {
            val ok = try {
                Lang.prepare("en", tag)
                true
            } catch (e: Exception) {
                false
            }
            main.post {
                if (Prefs.target(this) == tag) {
                    status.text = if (ok) "$name is ready." else "Couldn't get the $name pack. It will try again when you tap the bubble."
                }
            }
        }
    }

    // ---- Small helpers for building the screen -----------------------------

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            setLineSpacing(0f, 1.25f)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

    private fun margins(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) }

    private fun picker(labels: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val spinner = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(selected)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onPick(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        // A thin outlined box around the picker.
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(BG)
                setStroke(dp(1), LINE)
                cornerRadius = dp(10).toFloat()
            }
            addView(
                spinner,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            )
        }
    }
}
