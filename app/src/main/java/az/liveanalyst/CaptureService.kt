
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

    private var overlay: LinearLayout? = null
    private var overlayText: TextView? = null

    private var recognizer: TextRecognizer? = null

    private val handler = Handler(Looper.getMainLooper())

    private var processing = false
    private var lastProcessedAt = 0L
    private var stopped = false

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
            .setContentText("Ekran OCR monitorinqi davam edir")
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
            showStatus("Monitorinq başlatmaq mümkün olmadı: ${e.message}")
            stopSelf()
        }

        return START_NOT_STICKY
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

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)
            setBackgroundColor(0xEE14212B.toInt())
        }

        val header = TextView(this).apply {
            text = "LIVE ANALYST • AĞILLI MONİTORİNQ"
            textSize = 14f
            setTextColor(0xFF9BD51B.toInt())
        }

        val body = TextView(this).apply {
            text = "Ekran məlumatları gözlənilir..."
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 12, 0, 12)
        }

        val scroll = ScrollView(this).apply {
            addView(body)
        }

        val stopButton = Button(this).apply {
            text = "Monitorinqi dayandır"
            setOnClickListener {
                stopSelf()
            }
        }

        panel.addView(header)
        panel.addView(scroll)
        panel.addView(stopButton)

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.88f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        wm.addView(panel, params)

        overlay = panel
        overlayText = body
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

        var bitmap: Bitmap? = null

        try {
            bitmap = imageToBitmap(image)

            val input = InputImage.fromBitmap(bitmap, 0)

            recognizer?.process(input)
                ?.addOnSuccessListener { result ->

                    if (stopped) return@addOnSuccessListener

                    // Koordinatları qoruyan yeni OCR parser
                    val snapshot = MarketParser.parse(result)

                    overlayText?.text =
                        MarketAdvisor.render(snapshot)
                }
                ?.addOnFailureListener {
                    showStatus(
                        "Mətn tanınmadı. Ekran yenidən yoxlanılır."
                    )
                }
                ?.addOnCompleteListener {
                    bitmap?.recycle()
                    bitmap = null
                    processing = false
                }
                ?: run {
                    bitmap?.recycle()
                    bitmap = null
                    processing = false
                }

        } catch (e: Exception) {
            bitmap?.recycle()
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

        val paddedWidth =
            width + rowPadding / pixelStride

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
                (getSystemService(WINDOW_SERVICE) as WindowManager)
                    .removeView(it)
            }
        } catch (_: Exception) {
        }

        overlay = null
        overlayText = null

        handler.removeCallbacksAndMessages(null)

        super.onDestroy()
    }
}
