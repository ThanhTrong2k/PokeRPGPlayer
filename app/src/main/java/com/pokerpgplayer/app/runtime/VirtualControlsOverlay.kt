package com.pokerpgplayer.app.runtime

import android.app.Activity
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import org.libsdl.app.SDLActivity

/**
 * Sprint 36 — minimal virtual keyboard-key overlay for RPGXP/Essentials
 * games.
 *
 * **Sprint 38** added touch-cancellation hardening
 * (`requestDisallowInterceptTouchEvent`, `isLongClickable = false`) to
 * the Button-based D-Pad, plus `SPRINT38_DIAG` hold-duration logging.
 *
 * **Sprint 39** — Ti's own real-device evidence showed Sprint 38's fix
 * was insufficient (directions still releasing after only 54–101ms).
 * The D-Pad is now [DPadView] — a single, custom `View` with its own
 * direct `onTouchEvent()` handling, explicit pointer tracking, and an
 * explicit direction state machine, replacing the four separate
 * `Button`s entirely. **X/C/Z/Q and the settings toggle are completely
 * unchanged** — only the D-Pad's own implementation was replaced.
 */
class VirtualControlsOverlay(
    private val activity: Activity,
    private val onSettingsTapped: () -> Unit = {}
) {

    companion object {
        private const val TAG = "VirtualControlsOverlay"
        private const val BUTTON_SIZE_DP = 56
        private const val MARGIN_DP = 8
        private const val BUTTON_ALPHA = 0.55f
    }

    val rootView: FrameLayout = FrameLayout(activity)

    /** Sprint 39 — exposed so [RuntimeActivity] can force-release any held direction on window focus loss. */
    var dPadView: DPadView? = null
        private set

    private fun dp(value: Int): Int {
        val density = activity.resources.displayMetrics.density
        return (value * density).toInt()
    }

    private fun sendKeyDown(keyCode: Int, label: String) {
        Log.i(TAG, "SPRINT36_DIAG: button DOWN label=$label androidKeyCode=$keyCode")
        SDLActivity.onNativeKeyDown(keyCode)
    }

    private fun sendKeyUp(keyCode: Int, label: String, heldMs: Long) {
        Log.i(TAG, "SPRINT36_DIAG: button UP label=$label androidKeyCode=$keyCode")
        Log.i(TAG, "SPRINT38_DIAG: button held for ${heldMs}ms before release — label=$label androidKeyCode=$keyCode")
        SDLActivity.onNativeKeyUp(keyCode)
    }

    /** Unchanged since Sprint 38 — used only for X/C/Z/Q/Alt/Ctrl/Shift now that the D-Pad has its own, separate implementation ([DPadView]). */
    private fun wireButton(button: Button, keyCode: Int, label: String) {
        var downAtMs = 0L
        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    downAtMs = SystemClock.elapsedRealtime()
                    sendKeyDown(keyCode, label)
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val heldMs = SystemClock.elapsedRealtime() - downAtMs
                    sendKeyUp(keyCode, label, heldMs)
                    v.isPressed = false
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> false
            }
        }
    }

    private fun makeButton(label: String): Button {
        return Button(activity).apply {
            text = label
            alpha = BUTTON_ALPHA
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            isAllCaps = false
            isLongClickable = false
        }
    }

    fun attach() {
        val dpadSize = dp(BUTTON_SIZE_DP)
        val margin = dp(MARGIN_DP)

        // --- Left side: D-Pad — Sprint 39's own custom DPadView, replacing the 4 Button-based directions from Sprint 36/38 ---
        val dpad = DPadView(activity)
        dPadView = dpad
        val dpadParams = FrameLayout.LayoutParams(dpadSize * 3, dpadSize * 3, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = margin * 2
            bottomMargin = margin * 2
        }
        rootView.addView(dpad, dpadParams)

        // --- Right side: X / C / Z / Q, stacked in a 2x2 cluster — unchanged since Sprint 36/38 ---
        val actionContainer = FrameLayout(activity)
        val xBtn = makeButton("X")
        val cBtn = makeButton("C")
        val zBtn = makeButton("Z")
        val qBtn = makeButton("Q")
        wireButton(xBtn, KeyEvent.KEYCODE_X, "X")
        wireButton(cBtn, KeyEvent.KEYCODE_C, "C")
        wireButton(zBtn, KeyEvent.KEYCODE_Z, "Z")
        wireButton(qBtn, KeyEvent.KEYCODE_Q, "Q")

        fun actionParams(gravity: Int) = FrameLayout.LayoutParams(dpadSize, dpadSize, gravity)
        actionContainer.addView(xBtn, actionParams(Gravity.TOP or Gravity.END))
        actionContainer.addView(cBtn, actionParams(Gravity.TOP or Gravity.START))
        actionContainer.addView(zBtn, actionParams(Gravity.BOTTOM or Gravity.END))
        actionContainer.addView(qBtn, actionParams(Gravity.BOTTOM or Gravity.START))

        val actionContainerParams = FrameLayout.LayoutParams(dpadSize * 2, dpadSize * 2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = margin * 2
            bottomMargin = margin * 2
        }
        rootView.addView(actionContainer, actionContainerParams)

        // --- Minimal Settings button — unchanged since Sprint 36 ---
        val settingsBtn = makeButton("⚙").apply {
            setOnClickListener {
                Log.i(TAG, "SPRINT50_DIAG: settings button tapped — opening Runtime Quick Menu")
                onSettingsTapped()
            }
        }
        val settingsBtnParams = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
            topMargin = margin * 2
            rightMargin = margin * 2
        }
        rootView.addView(settingsBtn, settingsBtnParams)

        activity.addContentView(
            rootView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        Log.i(TAG, "SPRINT51_DIAG: virtual controls overlay attached — D-Pad (left), X/C/Z/Q (right), Quick Menu settings button (top-right)")
    }
}
