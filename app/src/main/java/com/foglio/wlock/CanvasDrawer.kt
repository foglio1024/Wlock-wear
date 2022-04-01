package com.foglio.wlock

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.Settings.Global.getString
import androidx.core.graphics.ColorUtils
import java.util.*

class CanvasDrawer {

    private var mBatteryLevelFactor: Float = 0F
    private var mCalendar: Calendar = Calendar.getInstance()
    private var mIsWeekend: Boolean = false

    private var mCenterX: Float = 0F
    private var mCenterY: Float = 0F
    private val mStrokeWidth: Float = 5F
    private lateinit var mBackgroundPaint: Paint
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

    var mAmbient: Boolean = false

    constructor() {

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

    private fun getColorForDay(day: Int): Int {
        var realDay = day
        if (realDay == 0) realDay = 7
        return mWeekColors[realDay - 1]
    }

    private fun getColorOfTheDay(): Int {
        val day = mCalendar.get(Calendar.DAY_OF_WEEK) - 1
        return getColorForDay(day)
    }

    fun setCenter(x: Float, y: Float) {
        mCenterX = x
        mCenterY = y
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

    fun drawDayArc(
        canvas: Canvas,
        angle: Float,
        dayIdx: Int,
        today: Int,
        margin: Float,
        todayFactor: Float
    ) {
        val col = when {
            dayIdx < today -> {
                ColorUtils.setAlphaComponent(getColorForDay(dayIdx), 50)
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
                    strokeWidth = mStrokeWidth
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
                strokeWidth = mStrokeWidth
                isAntiAlias = true
                style = Paint.Style.STROKE
                //strokeCap = Paint.Cap.ROUND

            }
        )

        canvas.rotate(angle, mCenterX, mCenterY)
    }


    fun drawDayArcs(canvas: Canvas) {
        val margin = 8F

        val seconds =
            mCalendar.get(Calendar.SECOND) + mCalendar.get(Calendar.MILLISECOND) / 1000f
        val minutes = mCalendar.get(Calendar.MINUTE)
        val hours = mCalendar.get((Calendar.HOUR_OF_DAY))

        val totalSeconds = seconds + (60 * minutes) + (60 * 60 * hours)
        val daySeconds = 24 * 60 * 60f
        val todayFactor = totalSeconds / daySeconds

        var today = mCalendar.get(Calendar.DAY_OF_WEEK) - 1
        if (today == 0) today = 7

        var angle = if (mIsWeekend) 360 / 2F else 360 / 5F
        var rot = if (mIsWeekend) angle + 90F else -90F
        canvas.rotate(rot, mCenterX, mCenterY)
        val range = if (mIsWeekend) 6..7 else 1..5

        for (dayIdx in range) {
            drawDayArc(canvas, angle, dayIdx, today, margin, todayFactor)
        }
    }

    fun drawWatchFace(
        canvas: Canvas,
        batteryLevelFactor: Float,
        hourOffset: Int,
        nextEventPair: Pair<String, String>,
        isWeekend: Boolean
    ) {
        val now = System.currentTimeMillis()

        mCalendar.timeInMillis = now + (hourOffset * 1000 * 60 * 60)

        mIsWeekend = isWeekend

        mBatteryLevelFactor = batteryLevelFactor

        val topMargin = -10

        canvas.save()

        drawDayArcs(canvas)

        canvas.restore()
        // HOUR
        canvas.drawText(
            mCalendar.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0'),
            mCenterX - 45,
            mCenterY + topMargin + if (mAmbient || (!mAmbient && nextEventPair.second == "")) 30 else 0,
            Paint().apply {
                textSize = 72F
                color = /*if (mAmbient) Color.GRAY else*/ Color.WHITE
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            })

        // MIN
        canvas.drawText(
            mCalendar.get(Calendar.MINUTE).toString().padStart(2, '0'),
            mCenterX + 45,
            mCenterY + topMargin + if (mAmbient || (!mAmbient && nextEventPair.second == "")) 30 else 0,
            Paint().apply {
                textSize = 72F
                color = if (mAmbient) ColorUtils.setAlphaComponent(
                    Color.WHITE,
                    180
                ) else getColorOfTheDay()
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            })
        // SEC
        if (!mAmbient) {
            canvas.drawText(
                mCalendar.get(Calendar.SECOND).toString().padStart(2, '0'),
                mCenterX,
                mCenterY + topMargin + 45 + if (nextEventPair.second == "") 40 else 0,
                Paint().apply {
                    textSize = 32F
                    color = Color.GRAY
                    isAntiAlias = true
                    textAlign = Paint.Align.CENTER
                })
        }

        // DATE
        val topText = mCalendar.get(Calendar.DAY_OF_MONTH).toString() + " " + getMonthName()
        canvas.drawText(topText, mCenterX, mCenterY + topMargin - 80 + if (mAmbient || (!mAmbient && nextEventPair.second == "")) 20 else 0,
            Paint().apply {
                textSize = 32F
                color =
                    if (mAmbient) ColorUtils.setAlphaComponent(
                        Color.WHITE,
                        180
                    ) else getColorOfTheDay()
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            })

        // EVENT
        if (nextEventPair.first != "" && nextEventPair.second != "") {

            val nextEventName = nextEventPair.first
            val nextEventTime = nextEventPair.second
            if (nextEventTime != "") {
                canvas.drawText(
                    nextEventTime,
                    mCenterX,
                    mCenterY + topMargin + 100,
                    Paint().apply {
                        textSize = 32F
                        color = if (mAmbient) ColorUtils.setAlphaComponent(
                            Color.WHITE,
                            180
                        ) else getColorOfTheDay()
                        isAntiAlias = true
                        textAlign = Paint.Align.CENTER
                    })
                canvas.drawText(
                    nextEventName,
                    mCenterX,
                    mCenterY + topMargin + 120,
                    Paint().apply {
                        textSize = 18F
                        color = Color.GRAY
                        isAntiAlias = true
                        textAlign = Paint.Align.CENTER
                    })
            }
        }

        val battRotation = 90f + 45 / 2f
        val battSweep = 45f

        canvas.save()
        canvas.rotate(battRotation, mCenterX, mCenterY)
        //canvas.translate(-30f, -110f)

        val battSize = 135f
        canvas.drawArc(
            mCenterX - battSize,
            mCenterY - battSize,
            mCenterX + battSize,
            mCenterY + battSize,
            0f,
            -battSweep,
            false,
            Paint().apply {
                color = Color.argb(40, 255, 255, 255)
                strokeWidth = 3f
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
        )

        canvas.drawArc(
            mCenterX - battSize,
            mCenterY - battSize,
            mCenterX + battSize,
            mCenterY + battSize,
            0f,
            -battSweep * mBatteryLevelFactor,
            false,
            Paint().apply {
                color = Color.GRAY
                strokeWidth = 3f
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
        )

        //canvas.rotate(90F, mCenterX, mCenterY)
        canvas.restore()


    }

    fun drawBackground(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
    }


}