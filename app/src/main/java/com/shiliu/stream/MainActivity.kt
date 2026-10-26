package com.shiliu.stream

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.shiliu.stream.ui.ShiLiuTheme
import com.shiliu.stream.ui.StreamScreen

class MainActivity : ComponentActivity() {

    private lateinit var controller: StreamController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF060E15.toInt()
        window.navigationBarColor = 0xFF060E15.toInt()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 锁屏状态下仍可查看推流页面，但不强制点亮屏幕
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }

        controller = StreamController.get(this)
        controller.onDisplayRotationChanged(currentRotationDegrees())
        setContent {
            ShiLiuTheme {
                StreamScreen(controller)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller.onDisplayRotationChanged(currentRotationDegrees())
    }

    override fun onDestroy() {
        controller.onActivityDestroyed()
        super.onDestroy()
    }

    private fun currentRotationDegrees(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }
}
