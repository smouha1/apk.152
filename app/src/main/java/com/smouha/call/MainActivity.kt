package com.smouha.call

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.smouha.call.databinding.ActivityMainBinding
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var pairing: PairingManager

    private var forceShowPair = false
    private var lastQrPayload: String = ""
    private val uiHandler = Handler(Looper.getMainLooper())
    private val pairTickRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing && BridgeState.uiVisible) {
                updateFastPairBadge()
                if (pairing.hostPairWindowOpen()) {
                    uiHandler.postDelayed(this, 1000L)
                }
            }
        }
    }

    private val requestCall = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(
            this,
            if (granted) "Phone permission granted" else "Permission denied — dialer fallback will be used",
            Toast.LENGTH_SHORT
        ).show()
        refreshUi()
    }

    private val requestNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* optional */ }

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startQrScan()
        else Toast.makeText(this, getString(R.string.scan_need_camera), Toast.LENGTH_LONG).show()
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        handleScannedPayload(contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = BridgeState.getPrefs(this)
        pairing = BridgeState.getPairing(this)
        AppCompatDelegate.setDefaultNightMode(
            if (prefs.darkTheme) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateThemeButtonIcon()

        binding.btnThemeToggle.setOnClickListener {
            val nextDark = !prefs.darkTheme
            prefs.darkTheme = nextDark
            AppCompatDelegate.setDefaultNightMode(
                if (nextDark) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        binding.btnToggle.setOnClickListener { toggleBridge() }
        binding.btnPermission.setOnClickListener {
            requestCall.launch(Manifest.permission.CALL_PHONE)
        }
        binding.btnRefreshQr.setOnClickListener {
            pairing.refreshOffer()
            lastQrPayload = ""
            renderPairing()
            Toast.makeText(this, "New pair code ready", Toast.LENGTH_SHORT).show()
        }
        binding.btnCopy.setOnClickListener {
            val text = binding.payloadText.text?.toString().orEmpty()
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("smouha-pair", text))
            Toast.makeText(
                this,
                "Copied — paste in Smouha Papper → Settings → Phones",
                Toast.LENGTH_LONG
            ).show()
        }
        binding.btnTogglePair.setOnClickListener {
            forceShowPair = !forceShowPair
            renderPairing()
        }
        binding.btnScanPcQr.setOnClickListener {
            ensureCameraAndScan()
        }
        binding.btnAcceptPcPair.setOnClickListener {
            if (!BridgeState.running) {
                startBridgeService()
            }
            pairing.enableHostPairWindow(120_000L)
            renderPairing()
            startPairCountdownTicker()
            Toast.makeText(this, getString(R.string.accept_pc_pair_toast), Toast.LENGTH_LONG).show()
        }
        binding.btnClearActivity.setOnClickListener {
            ActivityLog.clear(this)
            renderActivity()
            refreshUi()
            Toast.makeText(this, "Activity log cleared", Toast.LENGTH_SHORT).show()
        }
        binding.btnSaveName.setOnClickListener {
            val name = binding.editDeviceName.text?.toString()?.trim().orEmpty()
            prefs.deviceName = name.ifBlank { Prefs.DEFAULT_DEVICE_NAME }
            Toast.makeText(this, "Saved: ${prefs.deviceName}", Toast.LENGTH_SHORT).show()
            refreshUi()
            renderPairing()
        }
        binding.switchAutoStart.setOnCheckedChangeListener { _, checked ->
            prefs.autoStart = checked
        }
        binding.btnOverlay.visibility = View.GONE
        binding.btnBatteryOpt.setOnClickListener {
            BatteryHelper.openOptimizationSettings(this)
            binding.root.postDelayed({ renderSettings() }, 800)
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> showPanel(0)
                R.id.nav_activity -> {
                    showPanel(1)
                    renderActivity()
                }
                R.id.nav_settings -> {
                    showPanel(2)
                    renderSettings()
                }
            }
            true
        }

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (prefs.autoStart && !BridgeState.running) {
            startBridgeService()
        }
        showPanel(0)
        refreshUi()
        renderPairing()
    }

    private fun updateThemeButtonIcon() {
        binding.btnThemeToggle.text = if (prefs.darkTheme) "☀️" else "🌙"
    }

    private fun startPairCountdownTicker() {
        uiHandler.removeCallbacks(pairTickRunnable)
        uiHandler.post(pairTickRunnable)
    }

    private fun updateFastPairBadge() {
        val open = pairing.hostPairWindowOpen()
        val qc = NetworkUtils.quickCode() ?: "—"
        if (open) {
            val remSec = (pairing.hostPairRemainingMs() / 1000L).toInt()
            binding.pairWindowBadge.visibility = View.VISIBLE
            binding.pairWindowBadge.text = "⚡ AUTO-PAIR · ${remSec}s"
            binding.pairHint.text = getString(R.string.accept_pc_pair_hint, qc)
        } else {
            binding.pairWindowBadge.visibility = View.GONE
            val paired = prefs.hasPairedSession()
            binding.pairHint.text = getString(
                if (paired) R.string.pair_connected_hint else R.string.pair_hint,
                qc
            )
        }
    }

    override fun onStart() {
        super.onStart()
        BridgeState.uiVisible = true
        BridgeState.uiListener = {
            refreshUi()
            renderPairing()
            if (binding.panelActivity.visibility == View.VISIBLE) {
                renderActivity()
            }
        }
        if (pairing.hostPairWindowOpen()) {
            startPairCountdownTicker()
        }
    }

    override fun onStop() {
        BridgeState.uiVisible = false
        BridgeState.uiListener = null
        uiHandler.removeCallbacks(pairTickRunnable)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        renderPairing()
        if (binding.panelActivity.visibility == View.VISIBLE) renderActivity()
    }

    private fun showPanel(index: Int) {
        binding.panelHome.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.panelActivity.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.panelSettings.visibility = if (index == 2) View.VISIBLE else View.GONE
    }

    private fun toggleBridge() {
        if (BridgeState.running) {
            val i = Intent(this, CallBridgeService::class.java).apply {
                action = CallBridgeService.ACTION_STOP
            }
            startService(i)
            binding.root.postDelayed({ refreshUi() }, 300)
        } else {
            startBridgeService()
            binding.root.postDelayed({ refreshUi() }, 300)
        }
    }

    private fun startBridgeService() {
        val i = Intent(this, CallBridgeService::class.java).apply {
            action = CallBridgeService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }

    private fun hasCallPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun refreshUi() {
        val ip = NetworkUtils.localIpv4()
        val qc = NetworkUtils.quickCode(ip)
        val running = BridgeState.running
        val sessionsCount = prefs.sessionTokens().size
        val counts = ActivityLog.todayCounts(this)

        binding.homeDeviceName.text = prefs.deviceName
        binding.homeSubtitle.text = if (running) {
            getString(R.string.status_running, ip ?: "—", Prefs.PORT) + (if (qc != null) " · Code $qc" else "")
        } else {
            getString(R.string.status_stopped)
        }

        binding.kpiBridge.text = if (running) "Online" else "Offline"
        binding.kpiBridge.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.success else R.color.danger)
        )
        binding.kpiBridgeSub.text = "$sessionsCount PC${if (sessionsCount == 1) "" else "s"} paired"

        binding.kpiNetwork.text = ip ?: "No Wi‑Fi"
        binding.kpiPortSub.text = if (qc != null) "Quick Code: $qc" else "Port ${Prefs.PORT}"

        binding.kpiTodayTotal.text = counts.total.toString()
        binding.kpiTodaySub.text = "${counts.calls} Call · ${counts.whatsapp} WA"

        binding.statusPill.text = if (running) "● Online · ${Prefs.PORT}" else "○ Offline"
        binding.statusPill.setBackgroundResource(
            if (running) R.drawable.bg_status_pill else R.drawable.bg_status_pill_offline
        )
        binding.statusPill.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.success else R.color.danger)
        )

        binding.btnToggle.text =
            if (running) getString(R.string.stop_bridge) else getString(R.string.start_bridge)

        if (hasCallPermission()) {
            binding.btnPermission.visibility = View.GONE
            binding.permReadyLabel.visibility = View.VISIBLE
        } else {
            binding.btnPermission.visibility = View.VISIBLE
            binding.permReadyLabel.visibility = View.GONE
        }

        binding.btnOverlay.visibility = View.GONE

        val last = ActivityLog.list(this).firstOrNull()
        if (last != null) {
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val kind = when (last.kind) {
                "whatsapp" -> "💬 WhatsApp"
                "pair" -> "⚡ Paired"
                else -> "📞 Call"
            }
            val st = if (last.ok) "OK" else "FAIL"
            binding.lastActionText.text =
                "$kind · ${last.phone} · $st (${fmt.format(Date(last.ts))})\n${last.detail}"
            binding.lastActionText.setTextColor(
                ContextCompat.getColor(this, if (last.ok) R.color.success else R.color.danger)
            )
        } else {
            binding.lastActionText.text = getString(R.string.no_activity)
            binding.lastActionText.setTextColor(ContextCompat.getColor(this, R.color.muted))
        }
    }

    private fun ensureCameraAndScan() {
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (ok) startQrScan()
        else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startQrScan() {
        if (!BridgeState.running) startBridgeService()
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.scan_pc_qr_hint))
            setBeepEnabled(false)
            setOrientationLocked(false)
            setBarcodeImageEnabled(false)
        }
        scanLauncher.launch(options)
    }

    private fun handleScannedPayload(raw: String) {
        val text = raw.trim()
        try {
            val json = JSONObject(text)
            val secret = json.optString("secret", "").trim()
            if (secret.length < 6) {
                Toast.makeText(this, getString(R.string.scan_bad), Toast.LENGTH_LONG).show()
                return
            }
            val exp = json.optLong("exp", 0L)
            pairing.acceptPcOffer(secret, exp)
            renderPairing()
            startPairCountdownTicker()
            Toast.makeText(this, getString(R.string.scan_ok), Toast.LENGTH_LONG).show()
            ActivityLog.add(this, "pair", "-", true, "QR scanned — waiting for PC auto-connect")
            refreshUi()
        } catch (_: Exception) {
            if (text.length in 6..64 && text.all { it.isLetterOrDigit() }) {
                pairing.acceptPcOffer(text, 0L)
                renderPairing()
                startPairCountdownTicker()
                Toast.makeText(this, getString(R.string.scan_ok), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.scan_bad), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun generateQrBitmap(content: String, sizePx: Int = 420): Bitmap? {
        return try {
            val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.parseColor("#181512") else Color.WHITE)
                }
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }

    private fun renderPairing() {
        val ip = NetworkUtils.localIpv4()
        val payload = pairing.buildPayloadJson(ip)
        binding.payloadText.text = payload
        binding.brandAlways.text = Prefs.BRAND_TAG
        binding.brandTag.text = "${Prefs.BRAND_TAG} · v1.5.2"

        updateFastPairBadge()

        if (forceShowPair) {
            binding.pairCodeBlock.visibility = View.VISIBLE
            binding.btnTogglePair.text = getString(R.string.hide_pair)
            if (payload != lastQrPayload) {
                lastQrPayload = payload
                generateQrBitmap(payload)?.let { binding.qrImageView.setImageBitmap(it) }
            }
        } else {
            binding.pairCodeBlock.visibility = View.GONE
            binding.btnTogglePair.text = getString(R.string.show_pair)
        }
    }

    private fun renderActivity() {
        val list = binding.activityList
        list.removeAllViews()
        val entries = ActivityLog.list(this)
        binding.activityEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val fmt = SimpleDateFormat("dd MMM · HH:mm:ss", Locale.getDefault())
        for (e in entries) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 24, 28, 24)
                setBackgroundResource(R.drawable.bg_card)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 16
                layoutParams = lp
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val badgeText = when (e.kind) {
                "whatsapp" -> "💬 WHATSAPP"
                "pair" -> "⚡ PAIRED"
                else -> "📞 CALL"
            }
            val titleView = TextView(this).apply {
                text = "$badgeText  ·  ${e.phone}"
                textSize = 14f
                setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val statusView = TextView(this).apply {
                text = if (e.ok) "OK" else "FAIL"
                textSize = 11f
                setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
                setPadding(18, 6, 18, 6)
                setBackgroundResource(
                    if (e.ok) R.drawable.bg_status_pill else R.drawable.bg_status_pill_offline
                )
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (e.ok) R.color.success else R.color.danger
                    )
                )
            }

            topRow.addView(titleView)
            topRow.addView(statusView)

            val subView = TextView(this).apply {
                text = "${fmt.format(Date(e.ts))}  ·  ${e.detail}"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
                setPadding(0, 8, 0, 0)
            }

            card.addView(topRow)
            card.addView(subView)
            list.addView(card)
        }
    }

    private fun renderSettings() {
        val unrestricted = BatteryHelper.isIgnoringOptimizations(this)
        binding.batteryStatusText.text = getString(
            if (unrestricted) R.string.battery_status_ok else R.string.battery_status_bad
        )
        binding.btnBatteryOpt.visibility = if (unrestricted) View.GONE else View.VISIBLE

        binding.editDeviceName.setText(prefs.deviceName)
        binding.switchAutoStart.isChecked = prefs.autoStart
        val ip = NetworkUtils.localIpv4() ?: "—"
        val qc = NetworkUtils.quickCode(ip) ?: "—"
        binding.settingsMeta.text =
            "Version: 1.5.2 (Executive Edition)\nDevice ID: ${prefs.deviceId}\nIP: $ip (Quick Code: $qc)\nPort: ${Prefs.PORT}\nPaired PCs: ${prefs.sessionTokens().size}"
    }
}
