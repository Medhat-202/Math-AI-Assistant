
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
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Color
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
    private var isReading = false

    private lateinit var windowManager: WindowManager

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        startForeground(1, createNotification())
        createOverlay()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

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

        handlerThread = HandlerThread("ScreenCapture").apply {
            start()
        }

        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            3
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
            setBackgroundColor(Color.rgb(16, 36, 55))
        }

        resultText = TextView(this).apply {
            text = "Math AI Assistant\nجاهز لقراءة الشاشة"
            textSize = 16f
            setTextColor(Color.WHITE)
        }

        val scrollView = ScrollView(this).apply {
            addView(resultText)
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

        layout.addView(scrollView)
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
            width = (resources.displayMetrics.widthPixels * 0.85).toInt()
        }

        if (Settings.canDrawOverlays(this)) {
            windowManager.addView(layout, params)
        }
    }

    private fun captureAndRead() {

        if (isReading) return

        val reader = imageReader ?: run {
            resultText?.text = "قارئ الشاشة غير جاهز"
            return
        }

        try {
            val image = reader.acquireLatestImage()

            if (image == null) {
                resultText?.text = "انتظر لحظة ثم اضغط اقرأ الآن مرة أخرى"
                return
            }

            isReading = true
            resultText?.text = "جاري قراءة الشاشة..."

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

            buffer.rewind()
            paddedBitmap.copyPixelsFromBuffer(buffer)

            val croppedBitmap = android.graphics.Bitmap.createBitmap(
                paddedBitmap,
                0,
                0,
                image.width,
                image.height
            )

            image.close()
            paddedBitmap.recycle()

            val inputImage = InputImage.fromBitmap(croppedBitmap, 0)

            val recognizer = TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS
            )

            recognizer.process(inputImage)
                .addOnSuccessListener { result ->

                    val recognizedText = result.text

                    resultText?.text = if (recognizedText.isBlank()) {
                        "لم يتم استخراج أي نص من الشاشة"
                    } else {
                        "النص المقروء:\n\n$recognizedText"
                    }

                    isReading = false
                    recognizer.close()
                    croppedBitmap.recycle()
                }
                .addOnFailureListener { error ->

                    resultText?.text =
                        "خطأ OCR:\n${error.message ?: "خطأ غير معروف"}"

                    isReading = false
                    recognizer.close()
                    croppedBitmap.recycle()
                }

        } catch (e: Exception) {

            resultText?.text =
                "حدث خطأ:\n${e.message ?: "خطأ غير معروف"}"

            isReading = false
        }
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                "math_assistant",
                "Math Assistant",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager =
                getSystemService(NotificationManager::class.java)

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
            if (it.isAttachedToWindow) {
                windowManager.removeView(it)
            }
        }

        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        handlerThread?.quitSafely()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
