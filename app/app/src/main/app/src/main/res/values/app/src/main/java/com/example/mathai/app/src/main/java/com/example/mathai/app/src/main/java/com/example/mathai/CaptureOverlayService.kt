
package com.example.mathai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class CaptureOverlayService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlay: LinearLayout? = null
    private var resultText: TextView? = null
    private var handlerThread: HandlerThread? = null

    private lateinit var windowManager: WindowManager

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(1, createNotification())
        createOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0

        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")

        if (data == null || resultCode == 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager

        projection = manager.getMediaProjection(resultCode, data)

        startCapture()

        return START_NOT_STICKY
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        handlerThread = HandlerThread("ScreenCapture").apply { start() }

        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = projection?.createVirtualDisplay(
            "MathAssistant",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            Handler(handlerThread!!.looper)
        )
    }

    private fun createOverlay() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 12, 20, 12)
            setBackgroundColor(0xEE102437.toInt())
        }

        resultText = TextView(this).apply {
            text = "Math AI Assistant"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
        }

        val scanButton = Button(this).apply {
            text = "اقرأ الآن"
            setOnClickListener {
                captureAndRead()
            }
        }

        val closeButton = Button(this).apply {
            text = "إغلاق"
            setOnClickListener {
                stopSelf()
            }
        }

        layout.addView(resultText)
        layout.addView(scanButton)
        layout.addView(closeButton)

        overlay = layout

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        if (Settings.canDrawOverlays(this)) {
            windowManager.addView(layout, params)
        }
    }

    private fun captureAndRead() {
        val reader = imageReader ?: return

        try {
            val image = reader.acquireLatestImage() ?: return

            val bitmap = android.graphics.Bitmap.createBitmap(
                image.width,
                image.height,
                android.graphics.Bitmap.Config.ARGB_8888
            )

            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val paddedBitmap = android.graphics.Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                android.graphics.Bitmap.Config.ARGB_8888
            )

            paddedBitmap.copyPixelsFromBuffer(buffer)

            val croppedBitmap = android.graphics.Bitmap.createBitmap(
                paddedBitmap,
                0,
                0,
                image.width,
                image.height
            )

            image.close()

            val inputImage = InputImage.fromBitmap(croppedBitmap, 0)
            val recognizer = TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS
            )

            recognizer.process(inputImage)
                .addOnSuccessListener { result ->
                    val answer = MathSolver.solve(result.text)

                    resultText?.text = when (answer) {
                        true -> "✓ صحيحة"
                        false -> "✕ خاطئة"
                        null -> "لم أتعرف على المسألة"
                    }

                    recognizer.close()
                }
                .addOnFailureListener {
                    resultText?.text = "تعذر قراءة الشاشة"
                    recognizer.close()
                }

        } catch (e: Exception) {
            resultText?.text = "حدث خطأ أثناء القراءة"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "math_assistant",
                "Math Assistant",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "math_assistant")
                .setContentTitle("Math AI Assistant")
                .setContentText("المساعد يعمل")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Math AI Assistant")
                .setContentText("المساعد يعمل")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        overlay?.let {
            windowManager.removeView(it)
        }

        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        handlerThread?.quitSafely()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
