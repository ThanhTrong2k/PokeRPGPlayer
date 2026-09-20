package com.pokerpgplayer.app.runtime

import android.app.Activity
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * Sprint 50 — Runtime Quick Menu, opened from the existing in-game
 * settings (⚙) icon in [VirtualControlsOverlay].
 *
 * **Sprint 50.1 fix**: two real bugs found on real-device testing
 * (OPPO, landscape):
 *
 * 1. **Alt/Ctrl/Shift removed entirely** — per the confirmed product
 *    decision, these belong to a future control-mapping/on-screen
 *    button customization feature, not the Quick Menu. This also
 *    removes the `SDLActivity`/`KeyEvent` dependency this class
 *    previously had, since nothing here injects raw key events
 *    anymore.
 *
 * 2. **Only the first item in each horizontal row was visible**
 *    (x1 showed, x2/x3 didn't; Alt showed, Ctrl/Shift didn't, before
 *    removal) — the well-documented root cause for this exact
 *    symptom pattern on Android is `Button`'s own default Material
 *    style enforcing a `minWidth` (commonly ~88dp) that can override
 *    an explicitly-smaller `LayoutParams` width, pushing later
 *    siblings in a `LinearLayout` row out of the row's own available
 *    space. Fixed by explicitly zeroing `minWidth`/`minHeight` on
 *    every button (so the style's own minimum can no longer compete
 *    with the intended size) and switching each button to
 *    `WRAP_CONTENT` sizing (so it sizes itself naturally to its own
 *    text+padding instead of fighting a fixed dp value against the
 *    style), plus passing explicit `LayoutParams` to every view added
 *    to a `LinearLayout` instead of relying on Android's own
 *    default-params generation for an un-annotated `addView(child)`
 *    call, removing any ambiguity about how each child is sized.
 *
 * **Sprint 50 final-merge fix**: `attach()` now forces `currentSpeed`
 * back to `DEFAULT_SPEED` and writes that to
 * `sprint50_speed_scale.txt` immediately, before anything else runs
 * — guarantees every new session starts at x1 even if the workspace
 * still has a stale speed file left over from a prior session,
 * directly satisfying the "relaunch resets to x1" real-device
 * acceptance requirement.
 *
 * **Honest note**: the Sprint 50.1 layout fix (below) targets the
 * single most common, best-documented cause of the "only the first
 * item in a row renders" symptom — verified logically against
 * Android's own layout behavior, but not against the real OPPO
 * device itself, since no device is available in this session. If
 * that specific fix doesn't fully resolve it, the next thing to
 * check would be whether some ancestor view is constraining
 * available width more than expected (a different class of cause),
 * but that isn't indicated by the reported symptom pattern.
 */
class RuntimeQuickMenuOverlay(
    private val activity: Activity,
    /** Absolute path to the game's own current working directory — where sprint50_speed_scale.txt must be written for the Ruby-side shim to find it. */
    private val workspaceRootPath: String
) {

    companion object {
        private const val TAG = "RuntimeQuickMenuOverlay"
        private const val SPEED_SCALE_FILE_NAME = "sprint50_speed_scale.txt"
        private const val DEFAULT_SPEED = 1
        private val VALID_SPEEDS = listOf(1, 2, 3)
    }

    val rootView: FrameLayout = FrameLayout(activity)

    private var speedButtons: Map<Int, Button> = emptyMap()
    private var isVisible = false
    private var attached = false
    private var currentSpeed = DEFAULT_SPEED

    private fun dp(value: Int): Int {
        val density = activity.resources.displayMetrics.density
        return (value * density).toInt()
    }

    /**
     * Builds a speed-selection button sized to WRAP its own content
     * (text + padding) rather than a fixed dp width, with minWidth/
     * minHeight explicitly zeroed — see this class's own doc comment
     * above for why both of these matter for the Sprint 50.1 fix.
     */
    private fun makeSpeedButton(speed: Int): Button {
        return Button(activity).apply {
            text = "x$speed"
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener { selectSpeed(speed) }
        }
    }

    fun attach() {
        if (attached) {
            Log.w(
                TAG,
                "SPRINT51_DIAG: attach() called again — already attached, ignoring duplicate overlay."
            )
            return
        }
        attached = true
        rootView.visibility = View.GONE

        // Sprint 50 final-merge fix — session-default invariant.
        // The workspace this file lives in is not necessarily wiped
        // between app launches (established persistent-workspace
        // pattern since the earlier sprints), so a stale
        // sprint50_speed_scale.txt left over from a PRIOR session
        // could otherwise carry x2/x3 into a brand-new session
        // silently. Forcing both the in-memory state and the file
        // itself back to DEFAULT_SPEED here, before anything else in
        // attach() (including updateSpeedButtonHighlight() at the end
        // of this method), guarantees every session starts at x1 —
        // directly satisfying the "relaunch resets to x1" acceptance
        // requirement, independent of whatever the file happened to
        // contain when this Activity started.
        currentSpeed = DEFAULT_SPEED
        writeSpeedToFile(DEFAULT_SPEED)

        val panelLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 20, 20, 20))
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }

        val title = TextView(activity).apply {
            text = "Quick Menu"
            setTextColor(Color.WHITE)
            textSize = 18f
        }
        panelLayout.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val speedLabel = TextView(activity).apply {
            text = "Game Speed"
            setTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(0, dp(16), 0, dp(6))
        }
        panelLayout.addView(speedLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val speedRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val buttonMap = mutableMapOf<Int, Button>()
        VALID_SPEEDS.forEach { speed ->
            val button = makeSpeedButton(speed)
            buttonMap[speed] = button
            speedRow.addView(button, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(12) })
        }
        speedButtons = buttonMap
        panelLayout.addView(speedRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val closeButton = Button(activity).apply {
            text = "Close"
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener { hide() }
        }
        panelLayout.addView(closeButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        // Centered — avoids the D-Pad (bottom-left) and the settings
        // icon that opens this menu (top-right).
        val panelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        rootView.addView(panelLayout, panelParams)

        activity.addContentView(
            rootView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        updateSpeedButtonHighlight()
        Log.i(TAG, "SPRINT50_DIAG: Runtime Quick Menu attached (Sprint 50.1 layout fix) — default speed=x$DEFAULT_SPEED")
    }

    fun toggle() {
        if (isVisible) hide() else show()
    }

    fun show() {
        isVisible = true
        rootView.visibility = View.VISIBLE
        Log.i(TAG, "SPRINT50_DIAG: Quick Menu shown")
    }

    fun hide() {
        isVisible = false
        rootView.visibility = View.GONE
        Log.i(TAG, "SPRINT50_DIAG: Quick Menu hidden")
    }

    private fun selectSpeed(speed: Int) {
        if (speed !in VALID_SPEEDS) return
        currentSpeed = speed
        updateSpeedButtonHighlight()
        writeSpeedToFile(speed)
        Log.i(TAG, "SPRINT50_DIAG: speed selected — x$speed")
    }

    private fun updateSpeedButtonHighlight() {
        speedButtons.forEach { (speed, button) ->
            button.setBackgroundColor(if (speed == currentSpeed) Color.rgb(60, 140, 60) else Color.DKGRAY)
            button.setTextColor(Color.WHITE)
        }
    }

    /**
     * Writes the selected speed to the Ruby-side polled file. Every
     * failure path is caught and logged — a failed write here should
     * never crash the app; worst case, the speed simply doesn't
     * change and the Ruby-side shim keeps behaving at whatever speed
     * it last successfully read (or the default, x1, if it never
     * read anything at all).
     */
    private fun writeSpeedToFile(speed: Int) {
        try {
            val file = File(workspaceRootPath, SPEED_SCALE_FILE_NAME)
            file.writeText(speed.toString())
            Log.i(TAG, "SPRINT50_DIAG: wrote speed=$speed to ${file.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "SPRINT50_DIAG: failed to write speed scale file: ${t::class.java.simpleName}: ${t.message}")
        }
    }
}
