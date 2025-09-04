package com.example.lnbitstaptopay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.androidbrowserhelper.trusted.TwaLauncher

// Stripe Terminal SDK (4.6.0)
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.external.callable.*
import com.stripe.stripeterminal.external.models.*
import com.stripe.stripeterminal.log.LogLevel

// OkHttp
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Callback as OkHttpCallback

// JSON
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

// QR (ZXing modern API)
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

// Eligibility + dialogs
import android.app.AlertDialog
import android.nfc.NfcAdapter
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

import java.io.IOException
import androidx.core.net.toUri

class MainActivity : ComponentActivity() {

    // ---- persisted config defaults
    private val BACKEND_ORIGIN_DEFAULT = "" // no scheme
    private val TPOS_ID_DEFAULT = ""
    private val ADMIN_BEARER_TOKEN_DEFAULT = ""
    private val TERMINAL_LOCATION_ID_DEFAULT = ""

    private val prefs by lazy { getSharedPreferences("tpos_prefs", MODE_PRIVATE) }
    private fun cfgOrigin() = prefs.getString("origin", BACKEND_ORIGIN_DEFAULT)!!
    private fun cfgTposId() = prefs.getString("tposId", TPOS_ID_DEFAULT)!!
    private fun cfgBearer() = prefs.getString("bearer", ADMIN_BEARER_TOKEN_DEFAULT)!!
    private fun cfgLocId()  = prefs.getString("locId", TERMINAL_LOCATION_ID_DEFAULT)!!
    private fun hasSavedConfig(): Boolean =
        prefs.contains("origin") && prefs.contains("tposId") && prefs.contains("bearer") && prefs.contains("locId")

    private fun tposUrl() = "https://${cfgOrigin()}/tpos/${cfgTposId()}"
    private fun wsUrl()   = "wss://${cfgOrigin()}/api/v1/ws/${cfgTposId()}"
    private fun stripeBase() = "https://${cfgOrigin()}/api/v1/fiat/stripe"

    private val http = OkHttpClient()
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    @Volatile private var busy = false
    private var ws: WebSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wsReconnectPending = false
    private var wsBackoffMs = 500L

    private var twaLauncher: TwaLauncher? = null
    private var terminalInitialized = false

    // Discovery/connect state
    private var discoveryStarted = false
    private var readerConnected = false
    private var discoveryTimeoutPosted = false

    // ---- permission launcher (register once)
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private var onPermsGranted: (() -> Unit)? = null
    private var onPermsDenied: ((String) -> Unit)? = null

    // ---- QR Scanner launcher (modern API)
    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val ok = saveFromPairingUrl(result.contents!!)
            if (ok) {
                Log.i("TPOS_PAIR", "Saved config from pairing URL")
                findViewById<Button>(R.id.btnContinue)?.visibility = View.VISIBLE
                findViewById<TextView>(R.id.tvSummary)?.text =
                    "Saved: https://${cfgOrigin()}/tpos/${cfgTposId()} (pos=${cfgLocId()})"
            } else {
                Log.e("TPOS_PAIR", "Invalid pairing URL: ${result.contents}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        // Register runtime permission launcher
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            val allGranted = grants.values.all { it }
            if (allGranted) onPermsGranted?.invoke()
            else onPermsDenied?.invoke("Required permissions not granted")
            onPermsGranted = null
            onPermsDenied = null
        }

        initOnboardingUi()

        // NOTE: Do NOT auto-show registration; it opens only when user taps "Continue".
        // If you kept a tpos:// intent filter, we don't need to handle it explicitly here.
    }

    override fun onDestroy() {
        super.onDestroy()
        try { ws?.cancel() } catch (_: Throwable) {}
        ws = null
        twaLauncher?.destroy()
        twaLauncher = null
    }

    private fun restartTposWebSocket(afterMs: Long = 600L) {
        if (wsReconnectPending) return
        wsReconnectPending = true
        try { ws?.cancel() } catch (_: Throwable) {}
        ws = null
        mainHandler.postDelayed({
            wsReconnectPending = false
            startTposWebSocket()
        }, afterMs)
    }

    private fun scheduleBackoffReconnect() {
        val delay = wsBackoffMs.coerceAtMost(8000L)
        Log.i("TPOS_WS", "Scheduling WS reconnect in ${delay}ms")
        restartTposWebSocket(delay)
        wsBackoffMs = (wsBackoffMs * 2).coerceAtMost(8000L)
    }

    private fun initOnboardingUi() {
        val btnScan = findViewById<Button>(R.id.btnScan)
        val btnContinue = findViewById<Button>(R.id.btnContinue)
        val tvSummary = findViewById<TextView>(R.id.tvSummary)

        fun refresh() {
            if (hasSavedConfig()) {
                btnContinue.visibility = View.VISIBLE
                tvSummary.text = "Saved: https://${cfgOrigin()}/tpos/${cfgTposId()} (pos=${cfgLocId()})"
            } else {
                btnContinue.visibility = View.GONE
                tvSummary.text = ""
            }
        }
        refresh()

        btnScan.setOnClickListener {
            val opts = ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan Pairing Link")
                .setBeepEnabled(false)
                .setOrientationLocked(true)
                .setCaptureActivity(PortraitCaptureActivity::class.java)
            qrLauncher.launch(opts)
        }

        btnContinue.setOnClickListener {
            startPosFlow()
        }
    }

    private fun saveFromPairingUrl(url: String): Boolean {
        return runCatching {
            val u = url.toUri()
            val host = u.host ?: return false
            val port = if (u.port != -1) ":${u.port}" else ""
            val segs = u.pathSegments
            if (segs.size < 2 || segs[0] != "tpos") return false
            val tposId = segs[1]
            val pos  = u.getQueryParameter("pos") ?: return false
            val auth = u.getQueryParameter("auth") ?: return false

            prefs.edit()
                .putString("origin", host + port)
                .putString("tposId", tposId)
                .putString("bearer", auth)
                .putString("locId", pos)
                .apply()
            true
        }.getOrDefault(false)
    }

    // ---------- Tap to Pay eligibility helpers ----------
    private fun hasPlayServices(): Pair<Boolean, String?> {
        val gaa = GoogleApiAvailability.getInstance()
        val code = gaa.isGooglePlayServicesAvailable(this)
        val ok = (code == ConnectionResult.SUCCESS)
        val msg = if (ok) null else gaa.getErrorString(code)
        return ok to msg
    }

    private fun maybeResolvePlayServices(): Boolean {
        val gaa = GoogleApiAvailability.getInstance()
        val code = gaa.isGooglePlayServicesAvailable(this)
        if (gaa.isUserResolvableError(code)) {
            gaa.getErrorDialog(this, code, 0)?.show()
            return true
        }
        return false
    }

    private fun hasPlayStore(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo("com.android.vending", PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo("com.android.vending", 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun hasNfcEnabled(): Boolean =
        NfcAdapter.getDefaultAdapter(this)?.isEnabled == true

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    private fun supportsTapToPayDiscovery(): Boolean = try {
        true // keep true unless you wire in Terminal.supportsDiscoveryMethod(...)
    } catch (_: Throwable) {
        true
    }

    /** @return reason string if NOT eligible, else null */
    private fun tapToPayEligibleReason(): String? {
        val (gmsOk, gmsMsg) = hasPlayServices()
        if (!gmsOk) return "Google Play services not available: ${gmsMsg ?: "unknown error"}"
        if (!hasPlayStore()) return "Google Play Store app not installed"
        if (!hasNfcEnabled()) return "NFC is missing or turned off"
        if (!isLocationEnabled()) return "Location services are turned off"
        if (!supportsTapToPayDiscovery()) return "SDK reports Tap to Pay discovery not supported"
        return null
    }

    private fun showUnsupportedDialog(reason: String) {
        val builder = AlertDialog.Builder(this)
            .setTitle("Device not supported")
            .setMessage(
                "Stripe Tap to Pay can't run on this device.\n\n" +
                        "Reason: $reason\n\n" +
                        "Use a supported phone (e.g., Samsung/Pixel) or pair an external Stripe reader."
            )
            .setPositiveButton("OK", null)

        when {
            reason.contains("NFC", ignoreCase = true) -> {
                builder.setNegativeButton("Open NFC settings") { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } catch (_: Exception) {}
                }
            }
            reason.contains("Location", ignoreCase = true) -> {
                builder.setNegativeButton("Enable location") { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) {}
                }
            }
        }
        builder.show()
    }
    // ----------------------------------------------------

    private fun startPosFlow() {
        // Gate Tap-to-Pay on device eligibility
        val reason = tapToPayEligibleReason()
        if (reason != null) {
            Log.e("TPOS_TTP", "Tap to Pay not available on this device: $reason")
            if (reason.startsWith("Google Play services") && maybeResolvePlayServices()) {
                return
            }
            showUnsupportedDialog(reason)
            return
        }

        if (!terminalInitialized) {
            Terminal.initTerminal(
                applicationContext,
                LogLevel.VERBOSE,
                tokenProvider(),
                object : TerminalListener {}
            )
            terminalInitialized = true
        }

        // Show the registration modal ONLY when user taps Continue
        showRegistrationScreen()
    }

    private fun postDiscoveryTimeout() {
        if (discoveryTimeoutPosted) return
        discoveryTimeoutPosted = true
        mainHandler.postDelayed({
            discoveryTimeoutPosted = false
            if (!readerConnected) {
                val msg = "No Tap to Pay reader discovered/connected in time"
                Log.e("TPOS_TTP", msg)
                showUnsupportedDialog(
                    "$msg. Possible causes: device fails attestation, Play Services outdated, or Tap to Pay not supported on this model."
                )
            }
        }, 10_000L)
    }

    data class TapToPayMsg(
        val payment_intent_id: String?,
        val client_secret: String?,
        val currency: String?,
        val amount: Int?,
        val tpos_id: String? = null,
        val payment_hash: String? = null
    )
    data class TokenResp(val secret: String?)

    private fun parseTapToPay(raw: String): TapToPayMsg? {
        runCatching {
            val adapter = moshi.adapter(TapToPayMsg::class.java)
            adapter.fromJson(raw)?.let { return it }
        }
        val s = raw.trim()
            .removePrefix("TapToPay")
            .removePrefix("TapToPay(")
            .removeSuffix(")")
            .replace("""['"]""".toRegex(), "")
        val map = mutableMapOf<String, String>()
        s.split(",", ";", " ").map { it.trim() }.forEach { p ->
            val kv = p.split("=", ":", limit = 2).map { it.trim() }
            if (kv.size == 2 && kv[0].isNotBlank()) map[kv[0].lowercase()] = kv[1]
        }
        return TapToPayMsg(
            payment_intent_id = map["payment_intent_id"],
            client_secret     = map["client_secret"],
            currency          = map["currency"],
            amount            = map["amount"]?.toIntOrNull(),
            tpos_id           = map["tpos_id"],
            payment_hash      = map["payment_hash"]
        )
    }

    private fun startTposWebSocket() {
        try { ws?.cancel() } catch (_: Throwable) {}
        ws = null

        val req = Request.Builder().url(wsUrl()).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i("TPOS_WS", "WebSocket connected")
                wsBackoffMs = 500L
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.i("TPOS_WS", "Message: $text")
                val msg = parseTapToPay(text)
                if (msg?.client_secret.isNullOrBlank() || msg?.payment_intent_id.isNullOrBlank()) {
                    Log.w("TPOS_WS", "Missing client_secret or payment_intent_id in payload")
                    return
                }
                runOnUiThread {
                    if (busy) {
                        Log.i("TPOS_WS", "Ignoring WS: already collecting")
                        return@runOnUiThread
                    }
                    // Always launch the transparent overlay for the Tap-to-Pay flow
                    busy = true
                    val i = Intent(this@MainActivity, PaymentOverlayActivity::class.java)
                        .putExtra("client_secret", msg!!.client_secret!!)
                        .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    startActivity(i)

                    // Allow next cycle shortly after launch; overlay will finish() when done.
                    mainHandler.postDelayed({ busy = false }, 500)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e("TPOS_WS", "WebSocket failure: ${t.message}", t)
                scheduleBackoffReconnect()
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i("TPOS_WS", "WebSocket closing: $code $reason")
                try { ws.close(code, reason) } catch (_: Throwable) {}
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i("TPOS_WS", "WebSocket closed: $code $reason")
                scheduleBackoffReconnect()
            }
        })
    }

    // ---- request perms helper (kept for re-use inside registration)
    private fun hasAllRuntimePerms(): Boolean {
        val want = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            want += Manifest.permission.BLUETOOTH_SCAN
            want += Manifest.permission.BLUETOOTH_CONNECT
        }
        return want.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun ensurePermissions(onGranted: () -> Unit, onDenied: (String) -> Unit) {
        val need = mutableListOf<String>()
        val api = android.os.Build.VERSION.SDK_INT

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.CAMERA
        if (api >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                need += Manifest.permission.BLUETOOTH_SCAN
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                need += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.ACCESS_FINE_LOCATION

        if (need.isEmpty()) { onGranted(); return }

        onPermsGranted = onGranted
        onPermsDenied  = onDenied
        permissionLauncher.launch(need.toTypedArray())
    }

    private fun Request.Builder.withBearer(): Request.Builder =
        this.header("Authorization", "Bearer ${cfgBearer()}")

    private fun tokenProvider() = object : ConnectionTokenProvider {
        override fun fetchConnectionToken(callback: ConnectionTokenCallback) {
            val req = Request.Builder()
                .url("${stripeBase()}/connection_token")
                .header("accept", "application/json")
                .withBearer()
                .post("".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()

            http.newCall(req).enqueue(object : OkHttpCallback {
                override fun onFailure(call: Call, e: IOException) {
                    callback.onFailure(ConnectionTokenException(e.message ?: "Failed to fetch connection token", e))
                }
                override fun onResponse(call: Call, resp: Response) {
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        callback.onFailure(ConnectionTokenException("HTTP ${resp.code}: $body"))
                        return
                    }
                    val secret = moshi.adapter(TokenResp::class.java).fromJson(body)?.secret
                    if (secret.isNullOrBlank()) callback.onFailure(ConnectionTokenException("No secret"))
                    else callback.onSuccess(secret)
                }
            })
        }
    }

    // ---- DISCOVER + CONNECT with progress + watchdog (for registration) ----
    @SuppressLint("MissingPermission")
    private fun discoverAndConnect(
        onReady: () -> Unit,
        onError: (String) -> Unit,
        onUpdate: ((Int) -> Unit)? = null
    ) {
        if (!hasAllRuntimePerms()) {
            onError("Missing runtime permissions (Location/Bluetooth/Camera).")
            return
        }

        val discoveryConfig = DiscoveryConfiguration.TapToPayDiscoveryConfiguration(
            isSimulated = BuildConfig.DEBUG
        )

        try {
            Terminal.getInstance().discoverReaders(
                discoveryConfig,
                object : DiscoveryListener {
                    override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
                        discoveryStarted = true
                        onUpdate?.invoke(readers.size)
                        Log.i("TPOS_WS", "Discovered readers: ${readers.size}")

                        Terminal.getInstance().connectedReader?.let {
                            onReady()
                            return
                        }
                        if (readers.isEmpty()) return

                        startConnectedReaderWatchdog(onReady)

                        val cfg = ConnectionConfiguration.TapToPayConnectionConfiguration(
                            locationId = cfgLocId(),
                            autoReconnectOnUnexpectedDisconnect = true,
                            tapToPayReaderListener = null
                        )
                        Terminal.getInstance().connectReader(
                            readers.first(),
                            cfg,
                            object : ReaderCallback {
                                override fun onSuccess(reader: Reader) { onReady() }
                                override fun onFailure(e: TerminalException) {
                                    val msg = "Connect failed [${e.errorCode}]: ${e.errorMessage}"
                                    Log.e("TPOS_WS", msg, e)
                                    Terminal.getInstance().connectedReader?.let { onReady(); return }
                                    onError(msg)
                                }
                            }
                        )
                    }
                },
                object : Callback {
                    override fun onSuccess() { discoveryStarted = true }
                    override fun onFailure(e: TerminalException) {
                        val msg = "Discovery failed [${e.errorCode}]: ${e.errorMessage}"
                        Log.e("TPOS_WS", msg, e)
                        onError(msg)
                    }
                }
            )
        } catch (se: SecurityException) {
            Log.e("TPOS_PERM", "discoverReaders SecurityException", se)
            onError("Permission error starting discovery: ${se.message ?: "SecurityException"}")
        }
    }

    private fun startConnectedReaderWatchdog(onReady: () -> Unit) {
        val start = System.currentTimeMillis()
        fun tick() {
            val cr = try { Terminal.getInstance().connectedReader } catch (_: Throwable) { null }
            if (cr != null) { onReady(); return }
            if (System.currentTimeMillis() - start > 12_000L) return
            mainHandler.postDelayed({ tick() }, 500L)
        }
        mainHandler.postDelayed({ tick() }, 500L)
    }

    // ---- REGISTRATION MODAL (pre-page) ----
    private fun showRegistrationScreen() {
        val view = layoutInflater.inflate(R.layout.dialog_registration_status, null)
        val tvTitle   = view.findViewById<TextView>(R.id.tvRegTitle)
        val tvStep    = view.findViewById<TextView>(R.id.tvRegStep)
        val tvDetail  = view.findViewById<TextView>(R.id.tvRegDetail)
        val btnClose  = view.findViewById<Button>(R.id.btnRegClose)
        val btnGo     = view.findViewById<Button>(R.id.btnRegContinue)

        tvTitle.text = "Registering this device with Stripe"
        tvStep.text  = "Starting…"
        tvDetail.text = "Checking device eligibility and connecting…"
        btnGo.isEnabled = false

        val dlg = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()

        fun onPhase(label: String, detail: String = "") {
            tvStep.text = label
            tvDetail.text = detail
        }
        fun onRegistered(reader: Reader) {
            tvTitle.text = "Registered ✅"
            tvStep.text  = "Connected as software reader"
            val rn = reader.serialNumber ?: "unknown"
            val dt = reader.deviceType?.name ?: "TapToPay"
            val lid = cfgLocId()
            tvDetail.text = "Reader: $dt • SN: $rn\nLocation: $lid"
            btnGo.isEnabled = true
        }
        fun onFail(msg: String) {
            tvTitle.text = "Couldn’t register"
            tvStep.text  = "Error"
            tvDetail.text = msg
            btnGo.isEnabled = false
        }

        btnClose.setOnClickListener { dlg.dismiss() }
        btnGo.setOnClickListener {
            dlg.dismiss()
            // Now that we’re registered, start WS and launch the TWA
            startTposWebSocket()
            if (twaLauncher == null) twaLauncher = TwaLauncher(this)
            twaLauncher?.launch(Uri.parse(tposUrl()))
        }

        dlg.show()

        if (!hasSavedConfig()) {
            tvTitle.text = "Pair device to continue"
            tvStep.text = "Waiting for pairing"
            tvDetail.text = "Scan the pairing link QR code to set Origin, TPoS ID, Token, and Location."
            btnGo.isEnabled = false
            return
        }

        val reason = tapToPayEligibleReason()
        if (reason != null) { onFail("Device not eligible: $reason"); return }

        if (!terminalInitialized) {
            onPhase("Initializing Stripe SDK")
            Terminal.initTerminal(
                applicationContext,
                LogLevel.VERBOSE,
                tokenProvider(),
                object : TerminalListener {}
            )
            terminalInitialized = true
        }

        discoveryStarted = false
        readerConnected = false
        postDiscoveryTimeout()

        ensurePermissions(
            onGranted = {
                onPhase("Discovering Tap to Pay reader", "debug=${BuildConfig.DEBUG}")
                discoverAndConnect(
                    onReady = {
                        val cr = Terminal.getInstance().connectedReader
                        if (cr != null) onRegistered(cr) else onFail("Connected, but reader not available")
                    },
                    onError = { e -> onFail(e) },
                    onUpdate = { count ->
                        onPhase("Discovering…", "Found $count candidate${if (count==1) "" else "s"} — connecting…")
                    }
                )
            },
            onDenied = { e -> onFail("Permissions denied: $e") }
        )
    }
}
