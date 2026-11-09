
package az.liveanalyst

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_CAPTURE = 4102
        private const val REQUEST_NOTIFICATION = 4103
    }

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectionManager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        createScreen()
    }

    private fun createScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(30, 45, 30, 30)
            setBackgroundColor(0xFF101820.toInt())
        }

        val title = TextView(this).apply {
            text = "WinWinBet Live Analyst"
            textSize = 25f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            text = "Monitorinqə başlamaq üçün icazələri yoxla."
            textSize = 16f
            setTextColor(0xFFD5DEE5.toInt())
            setPadding(0, 28, 0, 28)
        }

        val overlayButton = Button(this).apply {
            text = "ÜZƏN PƏNCƏRƏ İCAZƏSİNİ VER"
            setOnClickListener {
                requestOverlayPermission()
            }
        }

        val startButton = Button(this).apply {
            text = "MONİTORİNQƏ BAŞLA"
            setOnClickListener {
                startMonitoring()
            }
        }

        val stopButton = Button(this).apply {
            text = "MONİTORİNQİ DAYANDIR"
            setOnClickListener {
                stopService(
                    Intent(this@MainActivity, CaptureService::class.java)
                )
                statusText.text = "Monitorinq dayandırıldı."
            }
        }

        val explanation = TextView(this).apply {
            text = """
                İŞ PRİNSİPİ

                • Ekran yalnız sənin icazənlə oxunur.
                • OCR görünən mətn və əmsalları tanıyır.
                • Bazar və seçim uyğunluğu ayrıca yoxlanmalıdır.
                • Proqram avtomatik mərc etmir.
                • Qazanc zəmanəti vermir.

                Təhlükəsizlik üçün monitorinqi istənilən vaxt dayandır.
            """.trimIndent()

            textSize = 14f
            setTextColor(0xFFB0BEC5.toInt())
            setPadding(0, 28, 0, 0)
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(overlayButton)
        root.addView(startButton)
        root.addView(stopButton)
        root.addView(explanation)

        setContentView(root)
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            statusText.text = "Üzən pəncərə icazəsi aktivdir."
            return
        }

        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )

        startActivity(intent)
    }

    private fun startMonitoring() {
        if (!Settings.canDrawOverlays(this)) {
            statusText.text =
                "Əvvəl üzən pəncərə icazəsini aktivləşdir."
            requestOverlayPermission()
            return
        }

        requestNotificationPermissionIfNeeded()

        val captureIntent =
            projectionManager.createScreenCaptureIntent()

        startActivityForResult(
            captureIntent,
            REQUEST_CAPTURE
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION
                )
            }
        }
    }

    @Deprecated("Kept for compatibility with the current project")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {

                val serviceIntent = Intent(
                    this,
                    CaptureService::class.java
                ).apply {
                    putExtra(
                        CaptureService.EXTRA_RESULT_CODE,
                        resultCode
                    )
                    putExtra(
                        CaptureService.EXTRA_RESULT_DATA,
                        data
                    )
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                statusText.text = "Monitorinq başladıldı."

            } else {
                statusText.text =
                    "Ekran paylaşımına icazə verilmədi."
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (::statusText.isInitialized &&
            Settings.canDrawOverlays(this)
        ) {
            statusText.text =
                "Üzən pəncərə icazəsi aktivdir. Monitorinqə başlaya bilərsən."
        }
    }
}
