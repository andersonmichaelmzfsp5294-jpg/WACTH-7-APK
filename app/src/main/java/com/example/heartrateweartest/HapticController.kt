package com.example.heartrateweartest

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Device-side actuator. Never repeat indefinitely. */
class HapticController(context: Context) {
    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        }

    fun vibrate(pattern: String): Boolean {
        if (!vibrator.hasVibrator()) return false
        // Only bounded, short, explicitly allowed patterns.
        val timings = when (pattern) {
            "single" -> longArrayOf(0, 180)
            "double" -> longArrayOf(0, 180, 150, 180)
            "triple" -> longArrayOf(0, 150, 150, 150, 150, 150)
            else -> return false
        }
        vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
        return true
    }

    fun cancel() = vibrator.cancel()
}
