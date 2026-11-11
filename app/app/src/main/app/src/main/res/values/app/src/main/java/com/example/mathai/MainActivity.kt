
package com.example.mathai

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val captureRequestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        val title = TextView(this).apply {
            text = "Math AI Assistant"
            textSize = 24f
        }

        val startButton = Button(this).apply {
            text = "تشغيل المساعد"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    return@setOnClickListener
                }

                val manager = getSystemService(MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager

                startActivityForResult(
                    manager.createScreenCaptureIntent(),
                    captureRequestCode
                )
            }
        }

        layout.addView(title)
        layout.addView(startButton)
        setContentView(layout)
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == captureRequestCode &&
            resultCode == RESULT_OK &&
            data != null
        ) {
            val serviceIntent = Intent(this, CaptureOverlayService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }

            startForegroundService(serviceIntent)
            Toast.makeText(this, "تم تشغيل المساعد", Toast.LENGTH_SHORT).show()
        }
    }
}
