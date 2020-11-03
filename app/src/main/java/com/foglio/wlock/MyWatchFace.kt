package com.foglio.wlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.view.SurfaceHolder
import androidx.core.graphics.ColorUtils

import java.lang.ref.WeakReference
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.*

/**
 * Updates rate in milliseconds for interactive mode. We update once a second to advance the
 * second hand.
 */
private const val INTERACTIVE_UPDATE_RATE_MS = 1000

/**
 * Handler message id for updating the time periodically in interactive mode.
 */
private const val MSG_UPDATE_TIME = 0

class MyWatchFace : CanvasWatchFaceService() {

    override fun onCreateEngine(): Engine {
        return Engine()
    }

    private class EngineHandler(reference: MyWatchFace.Engine) : Handler() {
        private val mWeakReference: WeakReference<MyWatchFace.Engine> = WeakReference(reference)

        override fun handleMessage(msg: Message) {
            val engine = mWeakReference.get()
            if (engine != null) {
                when (msg.what) {
                    MSG_UPDATE_TIME -> engine.handleUpdateTimeMessage()
                }
            }
        }
    }

    inner class Engine : CanvasWatchFaceService.Engine() {

        private lateinit var mCalendar: Calendar

        private var mRegisteredTimeZoneReceiver = false
        private var mMuteMode: Boolean = false
        private var mCenterX: Float = 0F
        private var mCenterY: Float = 0F

        private var mIsWeekend: Boolean = false

        private lateinit var mBackgroundPaint: Paint

        private var mAmbient: Boolean = false
        private var mLowBitAmbient: Boolean = false
        private var mBurnInProtection: Boolean = false

        private var mHourOffset: Int = 0
        private var mInactiveDayColor: Int = 0
        private var mWeekColors: Array<Int> = arrayOf(
                Color.rgb(92, 116, 224),
                Color.rgb(252, 230, 106),
                Color.rgb(115, 206, 255),
                Color.rgb(255, 182, 193),
                Color.rgb(171, 115, 235),
                Color.rgb(252, 70, 53),
                Color.rgb(252, 186, 3)
        )

        private val mEvents: Array<Pair<String, Int>> = arrayOf(
                Pair<String, Int>("pausa", 1030),
                Pair<String, Int>("pranzo", 1300),
                Pair<String, Int>("pausa", 1600),
                Pair<String, Int>("uscita", 1730)
        )

        /* Handler to update the time once a second in interactive mode. */
        private val mUpdateTimeHandler = EngineHandler(this)

        private val mTimeZoneReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                mCalendar.timeZone = TimeZone.getDefault()
                invalidate()
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)

            setWatchFaceStyle(WatchFaceStyle.Builder(this@MyWatchFace)
                    .setAcceptsTapEvents(true)
                    .build())

            mCalendar = Calendar.getInstance()

            initializeBackground()
            initializeWatchFace()
        }

        private fun initializeBackground() {
            mBackgroundPaint = Paint().apply {
                color = Color.BLACK
            }
        }

        private fun initializeWatchFace() {
            mInactiveDayColor = Color.argb(100, 112, 128, 144)
        }

        override fun onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            super.onDestroy()
        }

        override fun onPropertiesChanged(properties: Bundle) {
            super.onPropertiesChanged(properties)
            mLowBitAmbient = properties.getBoolean(WatchFaceService.PROPERTY_LOW_BIT_AMBIENT, false)
            mBurnInProtection = properties.getBoolean(WatchFaceService.PROPERTY_BURN_IN_PROTECTION, false)
        }

        override fun onTimeTick() {
            super.onTimeTick()
            invalidate()
        }

        override fun onAmbientModeChanged(inAmbientMode: Boolean) {
            super.onAmbientModeChanged(inAmbientMode)
            mAmbient = inAmbientMode

            // Check and trigger whether or not timer should be running (only
            // in active mode).
            updateTimer()
        }

        private fun getBatteryLevelFactor(): Float {
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent!!.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

            return level.toFloat() / scale.toFloat()
        }

        override fun onInterruptionFilterChanged(interruptionFilter: Int) {
            super.onInterruptionFilterChanged(interruptionFilter)
            val inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE

            /* Dim display in mute mode. */
            if (mMuteMode != inMuteMode) {
                mMuteMode = inMuteMode
                invalidate()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)

            /*
             * Find the coordinates of the center point on the screen, and ignore the window
             * insets, so that, on round watches with a "chin", the watch face is centered on the
             * entire screen, not just the usable portion.
             */
            mCenterX = width / 2f
            mCenterY = height / 2f
        }

        /**
         * Captures tap event (and tap type). The [WatchFaceService.TAP_TYPE_TAP] case can be
         * used for implementing specific logic to handle the gesture.
         */
        override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
            when (tapType) {
                WatchFaceService.TAP_TYPE_TOUCH -> {
                    // The user has started touching the screen.
                }
                WatchFaceService.TAP_TYPE_TOUCH_CANCEL -> {
                    // The user has started a different gesture or otherwise cancelled the tap.
                }
                WatchFaceService.TAP_TYPE_TAP -> {
                    // The user has completed the tap gesture.
                    mHourOffset += (if (x <= mCenterX) -1 else +1)
                }
            }
            invalidate()
        }


        override fun onDraw(canvas: Canvas, bounds: Rect) {
            val now = System.currentTimeMillis()
            mCalendar.timeInMillis = now + (mHourOffset * 1000 * 60 * 60)
            mIsWeekend = (mCalendar.get(Calendar.DAY_OF_WEEK) == 1 || mCalendar.get(Calendar.DAY_OF_WEEK) == 7)
            drawBackground(canvas)
            drawWatchFace(canvas)
        }

        private fun drawBackground(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
        }

        private fun getColorOfTheDay(): Int {
            val day = mCalendar.get(Calendar.DAY_OF_WEEK) - 1
            return getColorForDay(day)
        }

        private fun getColorForDay(day: Int): Int {
            var realDay = day
            if (realDay == 0) realDay = 7
            return mWeekColors[realDay - 1]
        }

        private fun getMonthName(): String {
            return when (mCalendar.get(Calendar.MONTH)) {
                0 -> "Jan"
                1 -> "Feb"
                2 -> "Mar"
                3 -> "Apr"
                4 -> "May"
                5 -> "Jun"
                6 -> "Jul"
                7 -> "Aug"
                8 -> "Sep"
                9 -> "Oct"
                10 -> "Nov"
                11 -> "Dec"
                else -> ""
            }
        }

        private fun getNextEvent(): Pair<String, String> {
            for (pair in mEvents) {
                val eventTime = mCalendar.toInstant().truncatedTo(ChronoUnit.DAYS).plus((pair.second / 100).toLong(), ChronoUnit.HOURS).plus((pair.second%100).toLong(), ChronoUnit.MINUTES)
                val offset = eventTime.epochSecond - mCalendar.toInstant().epochSecond
                val offsetInstant = Instant.ofEpochSecond(offset)
                val hoursLeft = (offsetInstant.atZone(ZoneOffset.UTC).hour + (if (TimeZone.getDefault().observesDaylightTime()) -1 else 0))
                val minutesLeft = offsetInstant.atZone(ZoneOffset.UTC).minute
                val secondsLeft = offsetInstant.atZone(ZoneOffset.UTC).second
                if (hoursLeft * 60 + minutesLeft in 0..90) {
                    val hourString = if (hoursLeft == 0) "" else "$hoursLeft:"
                    val minuteString = minutesLeft.toString().padStart(2, '0') + ":"
                    val secondString = secondsLeft.toString().padStart(2, '0')
                    val offsetStr = if (mAmbient) hourString.replace(":", "h ") + minuteString.replace(":", "m ") else hourString + minuteString + secondString
                    return Pair(pair.first, offsetStr)
                }
            }
            return Pair("", "")
        }

        private fun drawWatchFace(canvas: Canvas) {

            val margin = 8F
            val topMargin = -10

            val seconds = mCalendar.get(Calendar.SECOND) + mCalendar.get(Calendar.MILLISECOND) / 1000f
            val minutes = mCalendar.get(Calendar.MINUTE)
            val hours = mCalendar.get((Calendar.HOUR_OF_DAY))

            val totalSeconds = seconds + (60 * minutes) + (60 * 60 * hours)
            val daySeconds = 24 * 60 * 60f
            val todayFactor = totalSeconds / daySeconds

            canvas.save()

            val angle = 360 / 7F;
            canvas.rotate(-90F + angle, mCenterX, mCenterY)

            var today = mCalendar.get(Calendar.DAY_OF_WEEK) - 1
            if (today == 0) today = 7
            for (dayIdx in 1..7) {
                val col = when {
                    dayIdx < today -> {
                        ColorUtils.setAlphaComponent(getColorForDay(dayIdx), 70)
                    }
                    today == dayIdx -> {
                        getColorOfTheDay()
                    }
                    else -> Color.argb(0, 0, 0, 0)
                }
                if (!mAmbient) {
                    canvas.drawArc(
                            margin,
                            margin,
                            (mCenterX * 2) - margin,
                            (mCenterY * 2) - margin,
                            0F,
                            angle - 1,
                            false,
                            Paint().apply {
                                color = Color.argb(20, 255, 255, 255)
                                strokeWidth = 10F
                                isAntiAlias = true
                                style = Paint.Style.STROKE
                            }
                    )
                }
                canvas.drawArc(
                        margin,
                        margin,
                        (mCenterX * 2) - margin,
                        (mCenterY * 2) - margin,
                        0F,
                        if (dayIdx == today) (angle * todayFactor) - 1 else angle - 1,
                        false,
                        Paint().apply {
                            color = col
                            strokeWidth = 10F
                            isAntiAlias = true
                            style = Paint.Style.STROKE
                            //strokeCap = Paint.Cap.ROUND

                        }
                )
                canvas.rotate(angle, mCenterX, mCenterY)
            }

            canvas.restore()
            // HOUR
            canvas.drawText(mCalendar.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0'), mCenterX - 45, mCenterY + topMargin, Paint().apply {
                textSize = 72F
                color = Color.WHITE
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            })
            // MIN
            canvas.drawText(mCalendar.get(Calendar.MINUTE).toString().padStart(2, '0'), mCenterX + 45, mCenterY + topMargin, Paint().apply {
                textSize = 72F
                color = if (mAmbient) Color.GRAY else getColorOfTheDay()
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            })
            // SEC
            if (!mAmbient) {
                canvas.drawText(mCalendar.get(Calendar.SECOND).toString().padStart(2, '0'), mCenterX, mCenterY + topMargin + 45, Paint().apply {
                    textSize = 32F
                    color = Color.GRAY
                    isAntiAlias = true
                    textAlign = Paint.Align.CENTER
                })
            }


            // DATE
            val topText = mCalendar.get(Calendar.DAY_OF_MONTH).toString() + " " + getMonthName()
            canvas.drawText(topText, mCenterX, mCenterY + topMargin - 80, Paint().apply {
                textSize = 32F
                color = if (mAmbient) Color.GRAY else getColorOfTheDay()
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            })

            // EVENT
            if (!mIsWeekend) {
                val nextEventPair = getNextEvent()

                val nextEventName = nextEventPair.first
                val nextEventTime = nextEventPair.second
                if (nextEventTime != "") {
                    canvas.drawText(nextEventTime, mCenterX, mCenterY + topMargin + 100, Paint().apply {
                        textSize = 32F
                        color = if (mAmbient) Color.GRAY else getColorOfTheDay()
                        isAntiAlias = true
                        textAlign = Paint.Align.CENTER
                    })
                    canvas.drawText(nextEventName, mCenterX, mCenterY + topMargin + 120, Paint().apply {
                        textSize = 18F
                        color = Color.GRAY
                        isAntiAlias = true
                        textAlign = Paint.Align.CENTER
                    })
                }
            }
            // BATT
            canvas.drawText((getBatteryLevelFactor() * 100).toInt().toString(), 69f, mCenterY + 38f, Paint().apply {
                textSize = 24F
                color = Color.GRAY
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            })


            canvas.save()
            canvas.rotate(-90F, mCenterX, mCenterY)

            canvas.translate(-30f, -110f)

            val battSize = 30f
            canvas.drawArc(
                    mCenterX - battSize,
                    mCenterY - battSize,
                    mCenterX + battSize,
                    mCenterY + battSize,
                    0F,
                    360f,
                    false,
                    Paint().apply {
                        color = Color.argb(40, 255, 255, 255)
                        strokeWidth = 3f
                        isAntiAlias = true
                        style = Paint.Style.STROKE
                    }
            )

            canvas.drawArc(
                    mCenterX - battSize,
                    mCenterY - battSize,
                    mCenterX + battSize,
                    mCenterY + battSize,
                    0F,
                    360f * getBatteryLevelFactor(),
                    false,
                    Paint().apply {
                        color = if (mAmbient) Color.GRAY else getColorOfTheDay()
                        strokeWidth = 3f
                        isAntiAlias = true
                        style = Paint.Style.STROKE
                    }
            )
            //canvas.rotate(90F, mCenterX, mCenterY)
            canvas.restore()

        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            if (visible) {
                registerReceiver()
                /* Update time zone in case it changed while we weren't visible. */
                mCalendar.timeZone = TimeZone.getDefault()
                invalidate()
            } else {
                unregisterReceiver()
            }

            /* Check and trigger whether or not timer should be running (only in active mode). */
            updateTimer()
        }

        private fun registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = true
            val filter = IntentFilter(Intent.ACTION_TIMEZONE_CHANGED)
            this@MyWatchFace.registerReceiver(mTimeZoneReceiver, filter)
        }

        private fun unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = false
            this@MyWatchFace.unregisterReceiver(mTimeZoneReceiver)
        }

        /**
         * Starts/stops the [.mUpdateTimeHandler] timer based on the state of the watch face.
         */
        private fun updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME)
            }
        }

        /**
         * Returns whether the [.mUpdateTimeHandler] timer should be running. The timer
         * should only run in active mode.
         */
        private fun shouldTimerBeRunning(): Boolean {
            return isVisible && !mAmbient
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        fun handleUpdateTimeMessage() {
            invalidate()
            if (shouldTimerBeRunning()) {
                val timeMs = System.currentTimeMillis()
                val delayMs = INTERACTIVE_UPDATE_RATE_MS - timeMs % INTERACTIVE_UPDATE_RATE_MS
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs)
            }
        }
    }
}


