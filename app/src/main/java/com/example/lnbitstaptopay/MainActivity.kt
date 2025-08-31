package com.example.lnbitstaptopay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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

// NEW: eligibility helpers
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
    private fun stripeBase() = "https://${cfgOrigin()}/api/v1/fiat/stripe/terminal"

    private val http = OkHttpClient()
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    @Volatile private var busy = false
    private var ws: WebSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wsReconnectPending = false
    private var wsBackoffMs = 500L

    private var twaLauncher: TwaLauncher? = null
    private var terminalInitialized = false

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

        // Register once here (fixes the late-registration crash)
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

        // 🔹 Prompt for all core permissions on app start
        requestCorePermissionsOnStart()
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
                .setCaptureActivity(PortraitCaptureActivity::class.java) // <- force portrait
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

    /** @return reason string if NOT eligible, else null */
    private fun tapToPayEligibleReason(): String? {
        val (gmsOk, gmsMsg) = hasPlayServices()
        if (!gmsOk) return "Google Play services not available: ${gmsMsg ?: "unknown error"}"
        if (!hasPlayStore()) return "Google Play Store app not installed"
        if (!hasNfcEnabled()) return "NFC is missing or turned off"
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

        if (reason.contains("NFC", ignoreCase = true)) {
            builder.setNegativeButton("Open NFC settings") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } catch (_: Exception) {}
            }
        }

        builder.show()
    }
    // ----------------------------------------------------

    private fun startPosFlow() {
        // ✅ Gate Tap-to-Pay on device eligibility (NB55 etc.)
        tapToPayEligibleReason()?.let { reason ->
            Log.e("TPOS_TTP", "Tap to Pay not available on this device: $reason")
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

        ensurePermissions(
            onGranted = {
                discoverAndConnect(
                    onReady = { Log.i("TPOS_WS", "TapToPay reader READY") },
                    onError = { e -> Log.e("TPOS_WS", "Startup connect error: $e") }
                )
            },
            onDenied = { e -> Log.e("TPOS_WS", "Startup permissions denied: $e") }
        )

        // EXACTLY like the old flow: start WS then launch TWA
        startTposWebSocket()

        if (twaLauncher == null) twaLauncher = TwaLauncher(this)
        twaLauncher?.launch(Uri.parse(tposUrl()))
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
                    val readerConnected = Terminal.getInstance().connectedReader != null
                    if (readerConnected) {
                        beginCharge(msg!!)
                    } else {
                        ensurePermissions(
                            onGranted = {
                                discoverAndConnect(
                                    onReady = { beginCharge(msg!!) },
                                    onError = { e -> Log.e("TPOS_WS", "Reader not ready: $e") }
                                )
                            },
                            onDenied = { e -> Log.e("TPOS_WS", "Permissions denied: $e") }
                        )
                    }
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

    private fun beginCharge(msg: TapToPayMsg) {
        val clientSecret = msg.client_secret!!
        busy = true
        Log.i("TPOS_WS", "Starting Tap-to-Pay for PI ${msg.payment_intent_id}")

        collectAndProcess(
            clientSecret,
            onOk  = { id ->
                Log.i("TPOS_WS", "✅ Paid: $id")
                busy = false
                restartTposWebSocket(afterMs = 500L)
            },
            onFail = { e  ->
                Log.e("TPOS_WS", "❌ $e")
                busy = false
                restartTposWebSocket(afterMs = 800L)
            }
        )
    }

    // ---- request perms on start
    private fun requestCorePermissionsOnStart() {
        ensurePermissions(
            onGranted = { Log.i("TPOS_PERM", "Startup permissions granted") },
            onDenied  = { Log.w("TPOS_PERM", it) }
        )
    }

    // ---- reusable permission checker
    private fun ensurePermissions(onGranted: () -> Unit, onDenied: (String) -> Unit) {
        val need = mutableListOf<String>()
        val api = android.os.Build.VERSION.SDK_INT

        // Camera for QR scan
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.CAMERA

        if (api >= 31) { // Android 12+
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                need += Manifest.permission.BLUETOOTH_SCAN
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                need += Manifest.permission.BLUETOOTH_CONNECT
        }
        // Many devices still gate discovery behind Location
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

    private fun discoverAndConnect(onReady: () -> Unit, onError: (String) -> Unit) {
        val discoveryConfig = DiscoveryConfiguration.TapToPayDiscoveryConfiguration(
            isSimulated = BuildConfig.DEBUG
        )

        Terminal.getInstance().discoverReaders(
            discoveryConfig,
            object : DiscoveryListener {
                override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
                    Log.i("TPOS_WS", "Discovered readers: ${readers.size}")
                    if (readers.isEmpty()) {
                        Log.w("TPOS_TTP", "No Tap to Pay reader discovered (device likely not eligible).")
                        return
                    }
                    val cfg = ConnectionConfiguration.TapToPayConnectionConfiguration(
                        locationId = cfgLocId(),
                        autoReconnectOnUnexpectedDisconnect = true,
                        tapToPayReaderListener = null
                    )
                    Terminal.getInstance().connectReader(readers.first(), cfg, object : ReaderCallback {
                        override fun onSuccess(reader: Reader) {
                            Log.i("TPOS_WS", "Connected to reader: ${reader.serialNumber}")
                            onReady()
                        }
                        override fun onFailure(e: TerminalException) {
                            Log.e("TPOS_WS", "Connect failed [${e.errorCode}]: ${e.errorMessage}", e)
                            onError("Connect failed [${e.errorCode}]: ${e.errorMessage}")
                        }
                    })
                }
            },
            object : Callback {
                override fun onSuccess() { Log.i("TPOS_WS", "Discovery started") }
                override fun onFailure(e: TerminalException) {
                    Log.e("TPOS_WS", "Discovery failed [${e.errorCode}]: ${e.errorMessage}", e)
                    onError("Discovery failed [${e.errorCode}]: ${e.errorMessage}")
                }
            }
        )
    }

    private fun collectAndProcess(
        clientSecret: String,
        onOk: (String) -> Unit,
        onFail: (String) -> Unit
    ) {
        Terminal.getInstance().retrievePaymentIntent(clientSecret, object : PaymentIntentCallback {
            override fun onSuccess(pi: PaymentIntent) {
                Terminal.getInstance().collectPaymentMethod(pi, object : PaymentIntentCallback {
                    override fun onSuccess(collected: PaymentIntent) {
                        Terminal.getInstance().confirmPaymentIntent(collected, object : PaymentIntentCallback {
                            override fun onSuccess(processed: PaymentIntent) =
                                onOk(processed.id ?: "unknown_intent_id")
                            override fun onFailure(e: TerminalException) =
                                onFail("Confirm failed [${e.errorCode}]: ${e.errorMessage}")
                        })
                    }
                    override fun onFailure(e: TerminalException) =
                        onFail("Collect failed [${e.errorCode}]: ${e.errorMessage}")
                })
            }
            override fun onFailure(e: TerminalException) =
                onFail("Retrieve failed [${e.errorCode}]: ${e.errorMessage}")
        })
    }
}
