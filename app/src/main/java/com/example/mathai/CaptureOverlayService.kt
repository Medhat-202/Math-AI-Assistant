package com.example.mathai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
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
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayVisible = false
    private var isReading = false

    private var handlerThread: HandlerThread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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
            setPadding(18, 12, 18, 12)
            setBackgroundColor(Color.rgb(16, 36, 55))
        }

        val title = TextView(this).apply {
            text = "Math AI Assistant  ⋮ اسحب لتحريك النافذة"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(4, 4, 4, 12)
        }

        resultText = TextView(this).apply {
            text = "جاهز لقراءة السؤال"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(4, 8, 4, 8)
        }

        val scanButton = Button(this).apply {
            text = "اقرأ السؤال"
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

        layout.addView(title)
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
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 120
            width = (resources.displayMetrics.widthPixels * 0.85).toInt()
        }

        overlayParams = params

        // تحريك النافذة من شريط العنوان
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        title.setOnTouchListener { _, event ->

            when (event.action) {

                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()

                    if (overlayVisible) {
                        windowManager.updateViewLayout(layout, params)
                    }
                    true
                }

                else -> false
            }
        }

        showOverlay()
    }

    private fun showOverlay() {
        val view = overlay ?: return

        if (!overlayVisible && Settings.canDrawOverlays(this)) {
            try {
                windowManager.addView(view, overlayParams)
                overlayVisible = true
            } catch (e: Exception) {
                resultText?.text = "تعذر إظهار النافذة"
            }
        }
    }

    private fun hideOverlay() {
        val view = overlay ?: return

        if (overlayVisible) {
            try {
                windowManager.removeView(view)
                overlayVisible = false
            } catch (e: Exception) {
                overlayVisible = false
            }
        }
    }

    private fun captureAndRead() {

        if (isReading) return

        val reader = imageReader

        if (reader == null) {
            resultText?.text = "قارئ الشاشة غير جاهز"
            return
        }

        isReading = true
        resultText?.text = "جاري تجهيز الصورة..."

        // إخفاء النافذة حتى لا تدخل في صورة السؤال
        hideOverlay()

        // انتظار لحظة حتى تختفي النافذة من الصورة
        mainHandler.postDelayed({

            try {

                val image = reader.acquireLatestImage()

                if (image == null) {
                    resultText?.text =
                        "لم تصل صورة الشاشة، حاول مرة أخرى"

                    isReading = false
                    showOverlay()
                    return@postDelayed
                }

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

                val fullBitmap = android.graphics.Bitmap.createBitmap(
                    paddedBitmap,
                    0,
                    0,
                    image.width,
                    image.height
                )

                image.close()
                paddedBitmap.recycle()

                // منطقة السؤال التقريبية في تصميم اللعبة
                val cropX = (fullBitmap.width * 0.12).toInt()
                val cropY = (fullBitmap.height * 0.28).toInt()
                val cropWidth = (fullBitmap.width * 0.76).toInt()
                val cropHeight = (fullBitmap.height * 0.27).toInt()

                val questionBitmap = android.graphics.Bitmap.createBitmap(
                    fullBitmap,
                    cropX,
                    cropY,
                    cropWidth,
                    cropHeight
                )

                fullBitmap.recycle()

                // إعادة النافذة بعد التقاط الصورة
                showOverlay()

                resultText?.text = "جاري قراءة السؤال..."

                val inputImage = InputImage.fromBitmap(
                    questionBitmap,
                    0
                )

                val recognizer = TextRecognition.getClient(
                    TextRecognizerOptions.DEFAULT_OPTIONS
                )

                recognizer.process(inputImage)
                    .addOnSuccessListener { result ->

                        val recognizedText = result.text
                        val answer = MathSolver.solve(recognizedText)

                        resultText?.text = when (answer) {
                            true -> "✓ العبارة صحيحة"
                            false -> "✕ العبارة خاطئة"
                            null -> "لم أفهم السؤال:\n$recognizedText"
                        }

                        isReading = false
                        recognizer.close()
                        questionBitmap.recycle()
                    }
                    .addOnFailureListener { error ->

                        resultText?.text =
                            "خطأ في قراءة السؤال:\n${error.message}"

                        isReading = false
                        recognizer.close()
                        questionBitmap.recycle()
                    }

            } catch (e: Exception) {

                resultText?.text =
                    "حدث خطأ:\n${e.message ?: "خطأ غير معروف"}"

                isReading = false
                showOverlay()
            }

        }, 350)
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

        hideOverlay()
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        handlerThread?.quitSafely()
        mainHandler.removeCallbacksAndMessages(null)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
