package com.pokerpgplayer.app.runtime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt
import org.libsdl.app.SDLActivity

/**
 * Sprint 39 — custom, single-View D-Pad replacing the Button-based one
 * from Sprint 36/38.
 *
 * **Why a custom View, not four `Button`s:** Sprint 38 already removed
 * `Button`'s own long-press handling and disallowed parent touch
 * interception, but Ti's own real-device evidence still showed
 * directions releasing after only 54–101ms during an intended
 * continuous hold. Rather than keep chasing individual `Button`/
 * `ViewGroup` touch-dispatch quirks one at a time, this sprint replaces
 * the whole mechanism: **one plain `View`, with `onTouchEvent()`
 * overridden directly**, tracking a single primary pointer ID and an
 * explicit direction state machine by hand — no `Button`, no
 * `OnTouchListener` indirection, no default Android gesture/long-press
 * machinery involved at all. This is a strictly more direct, more
 * controllable mechanism, independent of whatever exact Android-level
 * behavior caused Sprint 38's own fix to be insufficient.
 *
 * **Zone-based hit-testing:** the touch point's offset from the view's
 * own center is classified into one of four directions (whichever axis
 * has the larger absolute offset) or a center dead-zone (no direction)
 * — a standard, simple, predictable single-direction D-Pad model,
 * matching this sprint's own explicit 4-directional (not 8-directional
 * diagonal) scope.
 *
 * **Key injection, exactly per this sprint's own explicit state
 * machine:** [SDLActivity.onNativeKeyDown] is called only on a genuine
 * not-pressed-to-pressed transition; [SDLActivity.onNativeKeyUp] is
 * called only when the tracked pointer is released/cancelled, leaves
 * this view, or the classified direction changes while still held —
 * never on every touch event, and never redundantly for a direction
 * that's already logically down.
 */
class DPadView(context: Context) : View(context) {

    companion object {
        private const val TAG = "DPadView"
        private const val DEAD_ZONE_FRACTION = 0.2f
    }

    /** No pointer currently tracked by this D-Pad. */
    private var trackedPointerId: Int = MotionEvent.INVALID_POINTER_ID

    /** The Android keycode currently logically "down", or null if no direction is active. */
    private var activeDirectionKeyCode: Int? = null
    private var activeDirectionLabel: String = ""
    private var activeDirectionDownAtMs: Long = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 40f
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        alpha = 140
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f
        canvas.drawCircle(cx, cy, radius, bgPaint)

        val arrowOffset = radius * 0.55f
        canvas.drawText("▲", cx, cy - arrowOffset + 15f, paint)
        canvas.drawText("▼", cx, cy + arrowOffset + 15f, paint)
        canvas.drawText("◀", cx - arrowOffset, cy + 15f, paint)
        canvas.drawText("▶", cx + arrowOffset, cy + 15f, paint)
    }

    /** Classifies a touch point (relative to this view's own bounds) into a direction, or null for the center dead-zone. */
    private fun classifyDirection(x: Float, y: Float): Pair<Int, String>? {
        val cx = width / 2f
        val cy = height / 2f
        val dx = x - cx
        val dy = y - cy
        val distance = sqrt(dx * dx + dy * dy)
        val deadZoneRadius = minOf(width, height) / 2f * DEAD_ZONE_FRACTION

        if (distance < deadZoneRadius) return null

        return if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
            if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT to "Right" else KeyEvent.KEYCODE_DPAD_LEFT to "Left"
        } else {
            if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN to "Down" else KeyEvent.KEYCODE_DPAD_UP to "Up"
        }
    }

    private fun logDiag(message: String) {
        Log.i(TAG, "SPRINT39_DIAG: $message")
    }

    /** Transitions to [newDirection] (or to no direction, if null), sending onNativeKeyUp/onNativeKeyDown only for genuine transitions. */
    private fun setActiveDirection(newDirection: Pair<Int, String>?) {
        val current = activeDirectionKeyCode
        if (current == newDirection?.first) return // no change, nothing to do

        if (current != null) {
            val heldMs = SystemClock.elapsedRealtime() - activeDirectionDownAtMs
            logDiag("direction transition — releasing keyCode=$current label=$activeDirectionLabel heldMs=$heldMs")
            SDLActivity.onNativeKeyUp(current)
        }

        if (newDirection != null) {
            activeDirectionKeyCode = newDirection.first
            activeDirectionLabel = newDirection.second
            activeDirectionDownAtMs = SystemClock.elapsedRealtime()
            logDiag("direction transition — pressing keyCode=${newDirection.first} label=${newDirection.second}")
            SDLActivity.onNativeKeyDown(newDirection.first)
        } else {
            activeDirectionKeyCode = null
            activeDirectionLabel = ""
        }
    }

    /**
     * Sprint 39 requirement — force-releases whatever direction is
     * currently active, unconditionally. Called from
     * [onDetachedFromWindow] and from [RuntimeActivity]'s own
     * `onWindowFocusChanged(false)`, so a direction can never remain
     * logically "stuck" down if this view disappears or the window
     * loses focus while a finger is still on it.
     */
    fun forceReleaseAll() {
        val current = activeDirectionKeyCode
        if (current != null) {
            val heldMs = SystemClock.elapsedRealtime() - activeDirectionDownAtMs
            logDiag("forced release — keyCode=$current label=$activeDirectionLabel heldMs=$heldMs")
            SDLActivity.onNativeKeyUp(current)
        }
        activeDirectionKeyCode = null
        activeDirectionLabel = ""
        trackedPointerId = MotionEvent.INVALID_POINTER_ID
    }

    override fun onDetachedFromWindow() {
        forceReleaseAll()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                trackedPointerId = event.getPointerId(0)
                logDiag("ACTION_DOWN pointerId=$trackedPointerId")
                val idx = event.findPointerIndex(trackedPointerId)
                setActiveDirection(classifyDirection(event.getX(idx), event.getY(idx)))
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger touched this same view while the
                // first is already tracked — per this sprint's own
                // single-primary-pointer model, ignored: the D-Pad
                // only ever tracks the first finger that touched it.
                val newPointerId = event.getPointerId(event.actionIndex)
                logDiag("ACTION_POINTER_DOWN pointerId=$newPointerId ignored — already tracking pointerId=$trackedPointerId")
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (trackedPointerId == MotionEvent.INVALID_POINTER_ID) return true
                val idx = event.findPointerIndex(trackedPointerId)
                if (idx < 0) return true
                val direction = classifyDirection(event.getX(idx), event.getY(idx))
                logDiag("ACTION_MOVE pointerId=$trackedPointerId classifiedDirection=${direction?.second ?: "none"}")
                setActiveDirection(direction)
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val liftedPointerId = event.getPointerId(event.actionIndex)
                if (liftedPointerId == trackedPointerId) {
                    logDiag("ACTION_POINTER_UP pointerId=$liftedPointerId — tracked pointer released")
                    setActiveDirection(null)
                    trackedPointerId = MotionEvent.INVALID_POINTER_ID
                    parent?.requestDisallowInterceptTouchEvent(false)
                } else {
                    logDiag("ACTION_POINTER_UP pointerId=$liftedPointerId ignored — not the tracked pointer ($trackedPointerId)")
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                logDiag("ACTION_UP pointerId=$trackedPointerId")
                setActiveDirection(null)
                trackedPointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                logDiag("ACTION_CANCEL pointerId=$trackedPointerId")
                setActiveDirection(null)
                trackedPointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }
}
