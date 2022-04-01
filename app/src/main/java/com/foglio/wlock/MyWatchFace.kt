package com.foglio.wlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Rect
import android.os.*
import android.provider.Settings
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.view.SurfaceHolder

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
        private lateinit var mDrawer: CanvasDrawer

        private lateinit var mVibrator: Vibrator

        private var mIsWeekend: Boolean = false

        private val mPulseDuration: Long = 40
        private val mShortPausesDuration: Long = 40
        private val mLongPausesDuration: Long = 200

        private val mWaveform15: VibrationEffect = VibrationEffect.createWaveform(
            longArrayOf(
                0, mPulseDuration,
                mShortPausesDuration, mPulseDuration,
                mShortPausesDuration, mPulseDuration
            ), -1
        )
        private val mWaveform30: VibrationEffect = VibrationEffect.createWaveform(
            longArrayOf(
                0, mPulseDuration,
                mShortPausesDuration, mPulseDuration,
                mShortPausesDuration, mPulseDuration,
                mLongPausesDuration, mPulseDuration,
                mShortPausesDuration, mPulseDuration,
                mShortPausesDuration, mPulseDuration
            ), -1
        )
        private val mWaveform60: VibrationEffect = VibrationEffect.createWaveform(
            longArrayOf(
                0, mPulseDuration,
                mShortPausesDuration, mPulseDuration,
                mShortPausesDuration, mPulseDuration,
                mLongPausesDuration, mPulseDuration,
                mShortPausesDuration, mPulseDuration,
                mShortPausesDuration, mPulseDuration,
                mLongPausesDuration, mPulseDuration,
                mShortPausesDuration, mPulseDuration,
                mShortPausesDuration, mPulseDuration
            ), -1
        )
        private val mWaveform0: VibrationEffect = VibrationEffect.createWaveform(
            longArrayOf(
                0, mPulseDuration,
                mShortPausesDuration, mPulseDuration,
                mShortPausesDuration, mPulseDuration,
                mShortPausesDuration, mPulseDuration,
                mShortPausesDuration, mPulseDuration,
                mShortPausesDuration, mPulseDuration,
                mShortPausesDuration, mPulseDuration
            ), -1
        )

        private var mRegisteredTimeZoneReceiver = false
        private var mMuteMode: Boolean = false
        private var mCenterX: Float = 0F
        private var mCenterY: Float = 0F

        private var mAmbient: Boolean = false
        private var mLowBitAmbient: Boolean = false
        private var mBurnInProtection: Boolean = false

        private var mHourOffset: Int = 0

        private val mEvents: Array<EventData> = arrayOf(
            EventData(getString(R.string.work_time), 830, 45, true, false),
            EventData(getString(R.string.work_time), 830, 45, true, false),
            EventData(getString(R.string.lunch_time), 1230, 240, true, false),
            EventData(getString(R.string.work_time), 1400, 45, true, false),
            EventData(getString(R.string.exit_time), 1800, 240, true, false),
            EventData(getString(R.string.bed_time), 2300, 60, true, true),
            EventData(getString(R.string.lunch_time), 1300, 60, false, true),
            EventData(getString(R.string.dinner_time), 1930, 60, true, true)
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

            setWatchFaceStyle(
                WatchFaceStyle.Builder(this@MyWatchFace)
                    .setAcceptsTapEvents(true)
                    .build()
            )

            mCalendar = Calendar.getInstance()
            mVibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            mDrawer = CanvasDrawer()
        }

        override fun onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            super.onDestroy()
        }

        override fun onPropertiesChanged(properties: Bundle) {
            super.onPropertiesChanged(properties)
            mLowBitAmbient = properties.getBoolean(WatchFaceService.PROPERTY_LOW_BIT_AMBIENT, false)
            mBurnInProtection =
                properties.getBoolean(WatchFaceService.PROPERTY_BURN_IN_PROTECTION, false)
        }

        override fun onTimeTick() {
            super.onTimeTick()
            invalidate()
        }

        override fun onAmbientModeChanged(inAmbientMode: Boolean) {
            super.onAmbientModeChanged(inAmbientMode)
            mAmbient = inAmbientMode
            mDrawer.mAmbient = inAmbientMode

            // Check and trigger whether or not timer should be running (only
            // in active mode).
            updateTimer()
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
            mDrawer.setCenter(mCenterX, mCenterY)
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
//                    if (y <= mCenterY) mHourOffset += (if (x <= mCenterX) -1 else +1)
//                    else mHourOffset += (if (x <= mCenterX) -24 else +24)
                }
            }
            invalidate()
        }


        override fun onDraw(canvas: Canvas, bounds: Rect) {
            val now = System.currentTimeMillis()
            mCalendar.timeInMillis = now + (mHourOffset * 1000 * 60 * 60)
            mDrawer.drawBackground(canvas)
            mIsWeekend =
                (mCalendar.get(Calendar.DAY_OF_WEEK) == 1 || mCalendar.get(Calendar.DAY_OF_WEEK) == 7)

            val event = getNextEvent()
            mDrawer.drawWatchFace(canvas, getBatteryLevelFactor(), mHourOffset, event, mIsWeekend)
        }

        private fun canFireEvent(event: EventData): Boolean {
            return if(mIsWeekend){
                event.showOnWeekEnds
            } else{
                event.showOnWorkDays
            }
        }

        private fun getNextEvent(): Pair<String, String> {

            for (event in mEvents) {
                if(!canFireEvent(event)) continue
                val eventTime = mCalendar.toInstant().truncatedTo(ChronoUnit.DAYS)
                    .plus((event.time / 100).toLong(), ChronoUnit.HOURS)
                    .plus((event.time % 100).toLong(), ChronoUnit.MINUTES)
                val offset = eventTime.epochSecond - mCalendar.toInstant().epochSecond
                val offsetInstant = Instant.ofEpochSecond(offset)
                val hoursLeft =offsetInstant.atZone(ZoneOffset.UTC).hour - 1;
//                    (offsetInstant.atZone(ZoneOffset.UTC).hour + (if (TimeZone.getDefault()
//                            .observesDaylightTime()
//                    ) -2 else -1))
                val minutesLeft = offsetInstant.atZone(ZoneOffset.UTC).minute
                val secondsLeft = offsetInstant.atZone(ZoneOffset.UTC).second
                val totalMinutesLeft = hoursLeft * 60 + minutesLeft

                if (secondsLeft == 0) {
                    when (totalMinutesLeft) {
                        15 -> mVibrator.vibrate(mWaveform15)
                        30 -> mVibrator.vibrate(mWaveform30)
                        60 -> mVibrator.vibrate(mWaveform60)
                        0 -> mVibrator.vibrate(mWaveform0)
                    }
                }

                if (totalMinutesLeft in 0..event.showAt) {
                    val hourString = if (hoursLeft == 0) "" else "$hoursLeft:"
                    val minuteString =
                        minutesLeft.toString().padStart(if (hoursLeft == 0) 0 else 2, '0') + ":"
                    val secondString = secondsLeft.toString().padStart(2, '0')
                    val offsetStr =
                        if (mAmbient) hourString.replace(":", "h ") + minuteString.replace(
                            ":",
                            "m"
                        ) else hourString + minuteString + secondString
                    return Pair(event.name, offsetStr)
                }
            }
            return Pair("", "")
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

        private fun getBatteryLevelFactor(): Float {
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent!!.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

            return level.toFloat() / scale.toFloat()
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


