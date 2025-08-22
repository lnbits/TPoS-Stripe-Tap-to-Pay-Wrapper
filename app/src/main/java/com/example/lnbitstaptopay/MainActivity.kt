package com.example.lnbitstaptopay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

// Stripe Terminal SDK (4.6.0)
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.log.LogLevel
import com.stripe.stripeterminal.external.callable.ConnectionTokenCallback
import com.stripe.stripeterminal.external.callable.ConnectionTokenProvider
import com.stripe.stripeterminal.external.callable.DiscoveryListener
import com.stripe.stripeterminal.external.callable.PaymentIntentCallback
import com.stripe.stripeterminal.external.callable.ReaderCallback
import com.stripe.stripeterminal.external.callable.TerminalListener
import com.stripe.stripeterminal.external.callable.Callback as TerminalCallback
import com.stripe.stripeterminal.external.models.ConnectionTokenException
import com.stripe.stripeterminal.external.models.DiscoveryConfiguration
import com.stripe.stripeterminal.external.models.ConnectionConfiguration
import com.stripe.stripeterminal.external.models.PaymentIntent
import com.stripe.stripeterminal.external.models.Reader
import com.stripe.stripeterminal.external.models.TerminalException

import android.util.Log
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.*
import android.app.AlertDialog
import android.os.Message

// OkHttp
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Callback as OkHttpCallback
import okhttp3.WebSocket
import okhttp3.WebSocketListener

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
    private val TPOS_CALLBACK_URL = "https://$BACKEND_ORIGIN/tpos/api/v1/atm/t2p"

    private val ADMIN_BEARER_TOKEN =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJiZW4iLCJhdXRoX3RpbWUiOjE3NTU3OTUyOTksImFwaV90b2tlbl9pZCI6IjgzY2RkM2IxZTgxYjRlYTBhODA4YWExMjE2NWE0ZGIwIiwiZXhwIjoxODgxNjE1NTk5fQ.q6BJKJ1AmyNJYyD_v9ZEsu_4YXJiAePueEO3H3gwXzE"

    private val TERMINAL_LOCATION_ID = "tml_GKQlZgyIgq3AAy"
    // ==============

    private val base = "https://$BACKEND_ORIGIN/api/v1/fiat/stripe/terminal"

    private val http = OkHttpClient()
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Terminal.initTerminal(
            applicationContext,
            LogLevel.VERBOSE,
            tokenProvider(),
            object : TerminalListener {}
        )

        setContent {
            MaterialTheme {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.setSupportMultipleWindows(true)
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            settings.mediaPlaybackRequiresUserGesture = false

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url?.toString() ?: return false
                                    view?.loadUrl(url)
                                    return true
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: Message
                                ): Boolean {
                                    val transport = resultMsg.obj as WebView.WebViewTransport
                                    val popup = WebView(view?.context!!)
                                    popup.webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(v: WebView?, url: String?) {
                                            if (!url.isNullOrEmpty()) this@apply.loadUrl(url)
                                            popup.destroy()
                                        }
                                    }
                                    transport.webView = popup
                                    resultMsg.sendToTarget()
                                    return true
                                }

                                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                                    AlertDialog.Builder(view?.context)
                                        .setMessage(message)
                                        .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                                        .setOnCancelListener { result?.cancel() }
                                        .show()
                                    return true
                                }

                                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                                    AlertDialog.Builder(view?.context)
                                        .setMessage(message)
                                        .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                                        .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                                        .show()
                                    return true
                                }
                            }

                            if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                            loadUrl(TPOS_URL)
                        }
                    }
                )
            }
        }

        // Connect once on launch so the phone-reader is ready
        ensurePermissions(
            onGranted = {
                discoverAndConnect(
                    onReady = { Log.i("TPOS_WS", "TapToPay reader READY") },
                    onError = { e -> Log.e("TPOS_WS", "Startup connect error: $e") }
                )
            },
            onDenied = { e -> Log.e("TPOS_WS", "Startup permissions denied: $e") }
        )

        startTposWebSocket()
    }

    // === WS payload ===
    data class TapToPayMsg(
        val amount: Int,
        val currency: String,
        val tpos_id: String? = null,
        val charge_id: String? = null,
        val paid: Boolean? = null // not sent by WS; we set it for callback
    )
    data class CallbackPayload(
        val amount: Int,
        val currency: String,
        val tpos_id: String,
        val charge_id: String,
        val paid: Boolean
    )

    private fun parseTapToPay(raw: String): TapToPayMsg? {
        // try JSON first
        runCatching {
            val adapter = moshi.adapter(TapToPayMsg::class.java)
            adapter.fromJson(raw)?.let { return it }
        }
        // tolerant fallback for pydantic str()
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
        val amount = map["amount"]?.toIntOrNull()
        val currency = map["currency"]
        val tposId = map["tpos_id"]
        val chargeId = map["charge_id"]
        return if (amount != null && !currency.isNullOrBlank())
            TapToPayMsg(amount, currency, tposId, chargeId)
        else null
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
                    Log.w("TPOS_WS", "Could not parse TapToPay payload")
                    return
                }
                runOnUiThread {
                    if (busy) {
                        Log.i("TPOS_WS", "Ignoring WS: already collecting")
                        return@runOnUiThread
                    }
                    val readerConnected = Terminal.getInstance().connectedReader != null
                    if (readerConnected) {
                        beginCharge(msg)
                    } else {
                        ensurePermissions(
                            onGranted = {
                                discoverAndConnect(
                                    onReady = { beginCharge(msg) },
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
        val currency = msg.currency.lowercase()
        busy = true
        Log.i("TPOS_WS", "Charging ${msg.amount} $currency (smallest unit)")
        createPaymentIntent(
            amount = msg.amount,
            currency = currency
        ) { clientSecret, err ->
            if (err != null || clientSecret == null) {
                Log.e("TPOS_WS", "Create PI error: $err")
                busy = false
            } else {
                Log.i("TPOS_WS", "Client secret received, collecting…")
                collectAndProcess(
                    clientSecret,
                    onOk  = { id ->
                        Log.i("TPOS_WS", "✅ Paid: $id")
                        postCallback(msg, paid = true)
                        busy = false
                    },
                    onFail = { e  ->
                        Log.e("TPOS_WS", "❌ $e")
                        busy = false
                    }
                )
            }
        }
    }

    private fun postCallback(msg: TapToPayMsg, paid: Boolean) {
        // Build JSON body using Moshi
        val payload = CallbackPayload(
            amount = msg.amount,
            currency = msg.currency,
            tpos_id = msg.tpos_id ?: "",
            charge_id = msg.charge_id ?: "",
            paid = paid
        )
        val json = moshi.adapter(CallbackPayload::class.java).toJson(payload)
        val req = Request.Builder()
            .url(TPOS_CALLBACK_URL)
            .header("accept", "application/json")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).enqueue(object : OkHttpCallback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("TPOS_WS", "Callback POST failed: ${e.message}", e)
            }
            override fun onResponse(call: Call, resp: Response) {
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.e("TPOS_WS", "Callback HTTP ${resp.code}: $body")
                } else {
                    Log.i("TPOS_WS", "Callback OK: $body")
                }
            }
        })
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
            object : TerminalCallback {
                override fun onSuccess() { Log.i("TPOS_WS", "Discovery started") }
                override fun onFailure(e: TerminalException) {
                    Log.e("TPOS_WS", "Discovery failed: ${e.errorMessage}")
                    onError("Discovery failed: ${e.errorMessage}")
                }
            }
        )
    }

    private fun createPaymentIntent(
        amount: Int,
        currency: String,
        onResult: (String?, String?) -> Unit
    ) {
        val req = Request.Builder()
            .url("$base/payment_intents")
            .header("accept", "application/json")
            .withBearer()
            .post("""{"amount":$amount,"currency":"$currency"}""".toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).enqueue(object : OkHttpCallback {
            override fun onFailure(call: Call, e: IOException) = onResult(null, e.message)
            override fun onResponse(call: Call, resp: Response) {
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    onResult(null, "HTTP ${resp.code}: $body"); return
                }
                val parsed = moshi.adapter(PIResp::class.java).fromJson(body)
                if (parsed?.client_secret.isNullOrBlank())
                    onResult(null, "Missing client_secret in response")
                else
                    onResult(parsed.client_secret, null)
            }
        })
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
    data class PIResp(val client_secret: String?)
}
