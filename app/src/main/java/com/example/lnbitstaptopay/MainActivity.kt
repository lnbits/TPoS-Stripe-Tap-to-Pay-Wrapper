package com.example.lnbitstaptopay

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
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

import java.io.IOException

class MainActivity : ComponentActivity() {

    // === CONFIG ===
    private val BACKEND_ORIGIN = "condct.com" // no scheme
    private val TPOS_ID = "PLHbmmo8LPT5UEQ57xyJA2"

    private val TPOS_URL = "https://$BACKEND_ORIGIN/tpos/$TPOS_ID"
    private val TPOS_WEBSOCKET = "wss://$BACKEND_ORIGIN/api/v1/ws/$TPOS_ID"

    private val ADMIN_BEARER_TOKEN =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJiZW4iLCJhdXRoX3RpbWUiOjE3NTYwNTU1MjQsImFwaV90b2tlbl9pZCI6IjU1ZmE1NzUyMjI1NjQ0MGE5ZGQ1YzM3M2ExODZkYWQxIiwiZXhwIjoxODUwNDI1MTQ0fQ.NqWEOc5H10Zt203OJ6IbCcxnPFFfd0cfU42ek7-Un0o"

    private val TERMINAL_LOCATION_ID = "tml_GKQlZgyIgq3AAy"
    // ==============

    // only used for connection_token
    private val base = "https://$BACKEND_ORIGIN/api/v1/fiat/stripe/terminal"

    private val http = OkHttpClient()
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    @Volatile private var busy = false

    private var twaLauncher: TwaLauncher? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Stripe Terminal init
        Terminal.initTerminal(
            applicationContext,
            LogLevel.VERBOSE,
            tokenProvider(),
            object : TerminalListener {}
        )

        // Permissions + connect reader once on launch
        ensurePermissions(
            onGranted = {
                discoverAndConnect(
                    onReady = { Log.i("TPOS_WS", "TapToPay reader READY") },
                    onError = { e -> Log.e("TPOS_WS", "Startup connect error: $e") }
                )
            },
            onDenied = { e -> Log.e("TPOS_WS", "Startup permissions denied: $e") }
        )

        // Start WebSocket command channel
        startTposWebSocket()

        // Launch as Trusted Web Activity (falls back to Custom Tab if needed)
        twaLauncher = TwaLauncher(this)
        twaLauncher?.launch(Uri.parse(TPOS_URL))
        // Do NOT finish(); keep activity alive so WS/Terminal remain active.
    }

    override fun onDestroy() {
        super.onDestroy()
        twaLauncher?.destroy()
        twaLauncher = null
    }

    // === WS payload ===
    // Expect JSON like:
    // {
    //   "type": "tap_to_pay",
    //   "payment_intent_id": "pi_XXXX",
    //   "client_secret": "pi_XXXX_secret_YYYY",
    //   "currency": "gbp",
    //   "amount": 1234,           // smallest currency unit
    //   "tpos_id": "PLHbmmo8LPT5UEQ57xyJA2",
    //   "payment_hash": "..."
    // }
    data class TapToPayMsg(
        val payment_intent_id: String?,
        val client_secret: String?,
        val currency: String?,
        val amount: Int?,
        val tpos_id: String? = null,
        val payment_hash: String? = null
    )

    data class CallbackPayload(
        val amount: Int,
        val currency: String,
        val tpos_id: String,
        val charge_id: String,
        val paid: Boolean
    )

    private fun parseTapToPay(raw: String): TapToPayMsg? {
        // Try JSON first
        runCatching {
            val adapter = moshi.adapter(TapToPayMsg::class.java)
            adapter.fromJson(raw)?.let { return it }
        }

        // Tolerant fallback if server ever sent a string payload
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
        val req = Request.Builder().url(TPOS_WEBSOCKET).build()
        http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i("TPOS_WS", "WebSocket connected")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.i("TPOS_WS", "Message: $text")
                val msg = parseTapToPay(text)
                if (msg == null) {
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
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i("TPOS_WS", "WebSocket closing: $code $reason")
                ws.close(code, reason)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i("TPOS_WS", "WebSocket closed: $code $reason")
            }
        })
    }

    private fun beginCharge(msg: TapToPayMsg) {
        // We no longer create a PI here; server did it and sent client_secret
        val clientSecret = msg.client_secret!!
        busy = true
        Log.i("TPOS_WS", "Starting Tap-to-Pay for PI ${msg.payment_intent_id}")

        collectAndProcess(
            clientSecret,
            onOk  = { id ->
                Log.i("TPOS_WS", "✅ Paid: $id")
                busy = false
            },
            onFail = { e  ->
                Log.e("TPOS_WS", "❌ $e")
                busy = false
            }
        )
    }

    // === Permissions ===
    private fun ensurePermissions(onGranted: () -> Unit, onDenied: (String) -> Unit) {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            needed += android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                needed += android.Manifest.permission.BLUETOOTH_SCAN
            }
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                needed += android.Manifest.permission.BLUETOOTH_CONNECT
            }
        }
        if (needed.isEmpty()) { onGranted(); return }
        val launcher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            val allGranted = grants.values.all { it }
            if (allGranted) onGranted() else onDenied("Location permission is required to discover readers")
        }
        launcher.launch(needed.toTypedArray())
    }

    // === Stripe Terminal plumbing ===
    private fun Request.Builder.withBearer(): Request.Builder =
        this.header("Authorization", "Bearer $ADMIN_BEARER_TOKEN")

    private fun tokenProvider() = object : ConnectionTokenProvider {
        override fun fetchConnectionToken(callback: ConnectionTokenCallback) {
            val req = Request.Builder()
                .url("$base/connection_token")
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
                    if (readers.isEmpty()) return
                    val cfg = ConnectionConfiguration.TapToPayConnectionConfiguration(
                        locationId = TERMINAL_LOCATION_ID,
                        autoReconnectOnUnexpectedDisconnect = true,
                        tapToPayReaderListener = null
                    )
                    Terminal.getInstance().connectReader(readers.first(), cfg, object : ReaderCallback {
                        override fun onSuccess(reader: Reader) {
                            Log.i("TPOS_WS", "Connected to reader: ${reader.serialNumber}")
                            onReady()
                        }
                        override fun onFailure(e: TerminalException) {
                            Log.e("TPOS_WS", "Connect failed: ${e.errorMessage}")
                            onError("Connect failed: ${e.errorMessage}")
                        }
                    })
                }
            },
            object : Callback {
                override fun onSuccess() { Log.i("TPOS_WS", "Discovery started") }
                override fun onFailure(e: TerminalException) {
                    Log.e("TPOS_WS", "Discovery failed: ${e.errorMessage}")
                    onError("Discovery failed: ${e.errorMessage}")
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
                            override fun onSuccess(processed: PaymentIntent) = onOk(processed.id ?: "unknown_intent_id")
                            override fun onFailure(e: TerminalException) = onFail("Confirm failed: ${e.errorMessage}")
                        })
                    }
                    override fun onFailure(e: TerminalException) = onFail("Collect failed: ${e.errorMessage}")
                })
            }
            override fun onFailure(e: TerminalException) = onFail("Retrieve failed: ${e.errorMessage}")
        })
    }

    // JSON models
    data class TokenResp(val secret: String?)
}