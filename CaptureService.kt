package az.liveanalyst

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer

class CaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL = "live_analyst_monitor"
        private const val NOTIFICATION_ID = 71
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: LinearLayout? = null
    private var overlayText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var processing = false
    private var lastProcessedAt = 0L
    private var projectionCallback: MediaProjection.Callback? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Live Analyst aktivdir")
            .setContentText("Ekran yalnız sənin başladığın monitorinq zamanı təhlil edilir")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(code, data)
        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }
        projection?.registerCallback(projectionCallback!!, handler)
        showOverlay()
        startCapture()
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "Canlı monitorinq", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 18, 22, 18)
            setBackgroundColor(0xEE14212B.toInt())
        }
        val header = TextView(this).apply {
            text = "LIVE ANALYST  •  MONİTORİNQ"
            textSize = 13f
            setTextColor(0xFF9BD51B.toInt())
        }
        val body = TextView(this).apply {
            text = "Ekrandakı məlumatlar gözlənilir…"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 10, 0, 10)
        }
        val stop = Button(this).apply {
            text = "Monitorinqi dayandır"
            setOnClickListener { stopSelf() }
        }
        panel.addView(header); panel.addView(body); panel.addView(stop)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 12; y = 180 }
        wm.addView(panel, params)
        overlay = panel; overlayText = body
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "LiveAnalystCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )
        reader?.setOnImageAvailableListener({ source ->
            if (processing || SystemClock.elapsedRealtime() - lastProcessedAt < 2500L) {
                source.acquireLatestImage()?.close(); return@setOnImageAvailableListener
            }
            lastProcessedAt = SystemClock.elapsedRealtime()
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer: ByteBuffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                if (cropped !== bitmap) bitmap.recycle()
                processing = true
                recognizer.process(InputImage.fromBitmap(cropped, 0))
                    .addOnSuccessListener { result ->
                        val snapshot = MarketParser.parse(result.text)
                        overlayText?.text = if (result.text.isBlank()) "Oxuna bilən mətn aşkarlanmadı" else MarketAdvisor.render(snapshot)
                    }
                    .addOnFailureListener { overlayText?.text = "Mətn tanınması alınmadı; yenidən yoxlanılır." }
                    .addOnCompleteListener { processing = false; cropped.recycle() }
            } catch (_: Exception) {
                processing = false
            } finally { image.close() }
        }, handler)
    }

    override fun onDestroy() {
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(overlay) } catch (_: Exception) {}
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        virtualDisplay?.release()
        projectionCallback?.let { callback -> try { projection?.unregisterCallback(callback) } catch (_: Exception) {} }
        projection?.stop()
        recognizer.close()
        super.onDestroy()
    }
}
