package com.leads365.apk.calls

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.leads365.apk.notifications.LeadsNotificationManager
import com.leads365.apk.ui.MainActivity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class CallerOverlayManager @Inject constructor() {

    private var activeOverlayView: View? = null
    private var windowManager: WindowManager? = null

    open fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * Shows a non-blocking caller ID card overlay over the dialer.
     * Returns true if overlay was successfully shown, or false if permission is missing / display failed.
     */
    open fun showCallerOverlay(
        context: Context,
        leadId: String,
        leadName: String,
        leadStatus: String,
        lastNote: String?,
        normalizedNumber: String
    ): Boolean {
        if (!canDrawOverlays(context)) {
            Log.d(TAG, "Cannot draw overlay: SYSTEM_ALERT_WINDOW permission is not granted")
            return false
        }

        try {
            dismissOverlay()

            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
            windowManager = wm

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dpToPx(context, 48)
            }

            val cardView = buildOverlayView(
                context = context,
                leadId = leadId,
                leadName = leadName,
                leadStatus = leadStatus,
                lastNote = lastNote,
                normalizedNumber = normalizedNumber,
                onOpenLead = {
                    dismissOverlay()
                    val intent = Intent(context, MainActivity::class.java).apply {
                        action = LeadsNotificationManager.ACTION_VIEW_LEAD
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(LeadsNotificationManager.EXTRA_LEAD_ID, leadId)
                        putExtra(LeadsNotificationManager.EXTRA_DESTINATION, "leads")
                    }
                    context.startActivity(intent)
                },
                onDismiss = {
                    dismissOverlay()
                }
            )

            wm.addView(cardView, params)
            activeOverlayView = cardView
            Log.i(TAG, "Caller overlay successfully displayed for lead: $leadId")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to display caller overlay: ${e.message}", e)
            activeOverlayView = null
            return false
        }
    }

    open fun dismissOverlay() {
        try {
            activeOverlayView?.let { view ->
                windowManager?.removeView(view)
                activeOverlayView = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error removing overlay view: ${e.message}")
        } finally {
            activeOverlayView = null
        }
    }

    private fun buildOverlayView(
        context: Context,
        leadId: String,
        leadName: String,
        leadStatus: String,
        lastNote: String?,
        normalizedNumber: String,
        onOpenLead: () -> Unit,
        onDismiss: () -> Unit
    ): View {
        val container = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val margin = dpToPx(context, 16)
            setPadding(margin, 0, margin, 0)
        }

        // Inner Card
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val cardPadding = dpToPx(context, 16)
            setPadding(cardPadding, cardPadding, cardPadding, cardPadding)

            // Card background styling: Dark sleek card with subtle border
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B")) // Slate 800
                cornerRadius = dpToPx(context, 16).toFloat()
                setStroke(dpToPx(context, 1), Color.parseColor("#38BDF8")) // Sky blue border
            }
            elevation = dpToPx(context, 8).toFloat()
        }

        // Top Row: Lead Name + Status Chip + Dismiss 'X'
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val nameView = TextView(context).apply {
            text = leadName
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val statusChip = TextView(context).apply {
            text = leadStatus
            setTextColor(Color.parseColor("#0284C7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            val padH = dpToPx(context, 8)
            val padV = dpToPx(context, 3)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E0F2FE"))
                cornerRadius = dpToPx(context, 12).toFloat()
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dpToPx(context, 8)
            }
            layoutParams = params
        }

        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            val pad = dpToPx(context, 6)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { onDismiss() }
        }

        headerRow.addView(nameView)
        headerRow.addView(statusChip)
        headerRow.addView(closeBtn)
        card.addView(headerRow)

        // Subtitle: Phone number
        val phoneView = TextView(context).apply {
            text = normalizedNumber
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(context, 4)
            }
            layoutParams = params
        }
        card.addView(phoneView)

        // Last Note section
        val noteContent = if (!lastNote.isNullOrBlank()) lastNote else "No notes recorded yet."
        val noteView = TextView(context).apply {
            text = "Note: $noteContent"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(context, 8)
            }
            layoutParams = params
        }
        card.addView(noteView)

        // Bottom Actions: "Open Lead" Button
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(context, 12)
            }
            layoutParams = params
        }

        val openBtn = Button(context).apply {
            text = "Open Lead"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2563EB")) // Blue 600
                cornerRadius = dpToPx(context, 8).toFloat()
            }
            setOnClickListener { onOpenLead() }
        }

        actionRow.addView(openBtn)
        card.addView(actionRow)

        container.addView(card)
        return container
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    companion object {
        const val TAG = "CallerOverlayManager"
    }
}
