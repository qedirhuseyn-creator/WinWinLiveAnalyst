package az.liveanalyst

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val requestCapture = 4102
    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
            setBackgroundColor(0xFF101820.toInt())
        }
        val title = TextView(this).apply {
            text = "WinWinBet Live Analyst"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
        }
        val info = TextView(this).apply {
            text = "Üzən pəncərə və ekran OCR monitorinqi. Başlatmaq üçün ekran paylaşımına ayrıca icazə ver."
            textSize = 16f
            setTextColor(0xFFD5DEE5.toInt())
            setPadding(0, 24, 0, 24)
        }
        val permission = Button(this).apply {
            text = "Üzən pəncərə icazəsini ver"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        }
        val start = Button(this).apply {
            text = "Monitorinqə başla"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    info.text = "Əvvəl üzən pəncərə icazəsini aktivləşdir."
                } else {
                    startActivityForResult(projectionManager.createScreenCaptureIntent(), requestCapture)
                }
            }
        }
        val note = TextView(this).apply {
            text = "Qeyd: Ekran paylaşımı Android-in sistem pəncərəsində sənin təsdiqinlə başlayır. Monitorinqi istənilən vaxt dayandıra bilərsən. Bu prototip OCR mətni tanıyır; mərc qərarı və qazanc zəmanəti vermir."
            textSize = 13f
            setTextColor(0xFFB0BEC5.toInt())
            setPadding(0, 24, 0, 0)
        }
        root.addView(title); root.addView(info); root.addView(permission); root.addView(start); root.addView(note)
        setContentView(root)
    }

    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestCapture && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, CaptureService::class.java).apply {
                putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            }
            startForegroundService(service)
            finish()
        }
    }
}
