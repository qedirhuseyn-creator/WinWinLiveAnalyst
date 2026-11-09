
package az.liveanalyst

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer

class CaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL = "live_analyst_monitor"
        private const val NOTIFICATION_ID = 71
        private const val CAPTURE_INTERVAL = 2500L
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var recognizer: TextRecognizer? = null

    private var windowManager: WindowManager? = null
    private var overlay: LinearLayout? = null
    private var overlayText: TextView? = null
    private var overlayScroll: ScrollView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private val handler = Handler(Looper.getMainLooper())

    private var processing = false
    private var lastProcessedAt = 0L
    private var stopped = false
    private var collapsed = false

    private var panelWidth = 300
    private var panelHeight = 260

    private var projectionCallback: MediaProjection.Callback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        createNotificationChannel()

        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("WinWinLiveAnalyst aktivdir")
            .setContentText("Canlı ekran monitorinqi davam edir")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(
            EXTRA_RESULT_CODE,
            Activity.RESULT_CANCELED
        ) ?: Activity.RESULT_CANCELED

        @Suppress("DEPRECATION")
        val resultData =
            intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startMonitoring(resultCode, resultData)
        } catch (e: Exception) {
            showStatus("Başlatma xətası: ${e.message}")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL,
                "Canlı monitorinq",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun startMonitoring(
        resultCode: Int,
        resultData: Intent
    ) {
        val manager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        projection = manager.getMediaProjection(
            resultCode,
            resultData
        )

        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }

        projection?.registerCallback(
            projectionCallback!!,
            handler
        )

        recognizer = TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

        showOverlay()
        startCapture()
    }

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(5), dp(7), dp(7))
            setBackgroundColor(0xEE14212B.toInt())
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "LIVE ANALYST"
            textSize = 13f
            setTextColor(0xFF9BD51B.toInt())
            setPadding(dp(5), dp(10), dp(5), dp(10))
        }

        val smallButton = makeButton("−") {
            panelWidth = (panelWidth - 30).coerceAtLeast(190)
            panelHeight = (panelHeight - 35).coerceAtLeast(120)
            updateOverlaySize()
        }

        val bigButton = makeButton("+") {
            panelWidth = (panelWidth + 30).coerceAtMost(420)
            panelHeight = (panelHeight + 35).coerceAtMost(500)
            updateOverlaySize()
        }

        val collapseButton = makeButton("▼") {
            collapsed = !collapsed
            overlayScroll?.visibility =
                if (collapsed) LinearLayout.GONE else LinearLayout.VISIBLE
            it.text = if (collapsed) "▲" else "▼"
        }

        val closeButton = makeButton("×") {
            stopSelf()
        }

        header.addView(
            title,
            LinearLayout.LayoutParams(0, dp(42), 1f)
        )
        header.addView(smallButton)
        header.addView(bigButton)
        header.addView(collapseButton)
        header.addView(closeButton)

        // Panel yalnız başlıqdan tutularaq sürüşdürülür.
        title.setOnTouchListener { _, event ->
            val params = overlayParams

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    title.setTag(R.id.content, event.rawX)
                    title.setTag(R.id.contentDescription, event.rawY)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (params != null) {
                        val oldX = title.getTag(R.id.content) as? Float
                            ?: event.rawX
                        val oldY = title.getTag(R.id.contentDescription) as? Float
                            ?: event.rawY

                        params.x += (event.rawX - oldX).toInt()
                        params.y += (event.rawY - oldY).toInt()

                        title.setTag(R.id.content, event.rawX)
                        title.setTag(R.id.contentDescription, event.rawY)

                        try {
                            wm.updateViewLayout(panel, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    title.setTag(R.id.content, null)
                    title.setTag(R.id.contentDescription, null)
                    true
                }

                else -> false
            }
        }

        val body = TextView(this).apply {
            text = "Ekran məlumatları gözlənilir..."
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(7), dp(8), dp(7), dp(8))
        }

        val scroll = ScrollView(this).apply {
            addView(body)
        }

        overlayText = body
        overlayScroll = scroll

        panel.addView(header)
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(panelHeight)
            )
        )

        val params = WindowManager.LayoutParams(
            dp(panelWidth),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(75)
        }

        overlayParams = params
        overlay = panel

        wm.addView(panel, params)
    }

    private fun makeButton(
        label: String,
        action: (TextView) -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(7), dp(7), dp(7), dp(7))
            setOnClickListener {
                action(this)
            }
        }
    }

    private fun updateOverlaySize() {
        val params = overlayParams ?: return
        val panel = overlay ?: return

        params.width = dp(panelWidth)

        overlayScroll?.layoutParams = overlayScroll?.layoutParams?.apply {
            height = dp(panelHeight)
        }

        try {
            windowManager?.updateViewLayout(panel, params)
        } catch (_: Exception) {
        }
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = projection?.createVirtualDisplay(
            "WinWinLiveAnalyst",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            handler
        )

        reader?.setOnImageAvailableListener(
            { imageReader ->
                processNextImage(imageReader)
            },
            handler
        )
    }

    private fun processNextImage(source: ImageReader) {
        if (stopped) return

        if (processing ||
            SystemClock.elapsedRealtime() - lastProcessedAt <
            CAPTURE_INTERVAL
        ) {
            source.acquireLatestImage()?.close()
            return
        }

        val image = source.acquireLatestImage() ?: return

        processing = true
        lastProcessedAt = SystemClock.elapsedRealtime()

        var capturedBitmap: Bitmap? = null

        try {
            val bitmap = imageToBitmap(image)
            capturedBitmap = bitmap

            val input = InputImage.fromBitmap(bitmap, 0)
            val activeRecognizer = recognizer

            if (activeRecognizer == null) {
                bitmap.recycle()
                processing = false
                return
            }

            activeRecognizer.process(input)
                .addOnSuccessListener { result ->
                    if (stopped) return@addOnSuccessListener

                    val snapshot = MarketParser.parse(result)
                    overlayText?.text = MarketAdvisor.render(snapshot)
                }
                .addOnFailureListener {
                    showStatus("Mətn tanınmadı. Yenidən yoxlanılır.")
                }
                .addOnCompleteListener {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                    capturedBitmap = null
                    processing = false
                }

        } catch (e: Exception) {
            capturedBitmap?.let {
                if (!it.isRecycled) it.recycle()
            }
            processing = false
            showStatus("OCR xətası: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        val width = image.width
        val height = image.height

        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride

        val paddedBitmap = Bitmap.createBitmap(
            paddedWidth,
            height,
            Bitmap.Config.ARGB_8888
        )

        buffer.rewind()
        paddedBitmap.copyPixelsFromBuffer(buffer)

        val finalBitmap = Bitmap.createBitmap(
            paddedBitmap,
            0,
            0,
            width,
            height
        )

        if (finalBitmap !== paddedBitmap) {
            paddedBitmap.recycle()
        }

        return finalBitmap
    }

    private fun showStatus(message: String) {
        handler.post {
            overlayText?.text = message
        }
    }

    override fun onDestroy() {
        stopped = true

        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null

        virtualDisplay?.release()
        virtualDisplay = null

        projectionCallback?.let {
            try {
                projection?.unregisterCallback(it)
            } catch (_: Exception) {
            }
        }

        try {
            projection?.stop()
        } catch (_: Exception) {
        }

        projection = null

        recognizer?.close()
        recognizer = null

        try {
            overlay?.let {
                windowManager?.removeView(it)
            }
        } catch (_: Exception) {
        }

        overlay = null
        overlayText = null
        overlayScroll = null
        overlayParams = null
        windowManager = null

        handler.removeCallbacksAndMessages(null)

        super.onDestroy()
    }
}
