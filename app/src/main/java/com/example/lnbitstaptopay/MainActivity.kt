package com.example.lnbitstaptopay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
import okio.Buffer

import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.app.AlertDialog
import android.webkit.JsResult
import android.os.Message
// OkHttp
import okhttp3.Call
import okhttp3.FormBody
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
    private val BACKEND_ORIGIN = "condct.com"
    private val TPOS_ID = "PLHbmmo8LPT5UEQ57xyJA2"

    private val TPOS_URL = "https://$BACKEND_ORIGIN/tpos/$TPOS_ID"

    private val TPOS_WEBSOCKET = "wss://$BACKEND_ORIGIN/api/v1/ws/$TPOS_ID"

    private val ADMIN_BEARER_TOKEN =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJiZW4iLCJhdXRoX3RpbWUiOjE3NTU3OTUyOTksImFwaV90b2tlbl9pZCI6IjgzY2RkM2IxZTgxYjRlYTBhODA4YWExMjE2NWE0ZGIwIiwiZXhwIjoxODgxNjE1NTk5fQ.q6BJKJ1AmyNJYyD_v9ZEsu_4YXJiAePueEO3H3gwXzE"
    private val TERMINAL_LOCATION_ID =
        if (BuildConfig.DEBUG) "" else "tml_GKQlZgyIgq3AAy"
    // ==============

    private val base = "https://$BACKEND_ORIGIN/api/v1/fiat/stripe/terminal"

    private val http = OkHttpClient()
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

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

                            // Important for POS dialogs/sheets/popups
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.setSupportMultipleWindows(true)
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            settings.mediaPlaybackRequiresUserGesture = false

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: android.webkit.WebResourceRequest?
                                ): Boolean {
                                    // Keep all nav in the same WebView
                                    val url = request?.url?.toString() ?: return false
                                    view?.loadUrl(url)
                                    return true
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                // Handle window.open / target=_blank by loading into the same WebView
                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: Message
                                ): Boolean {
                                    val transport = resultMsg.obj as WebView.WebViewTransport
                                    // Create a transient WebView and pipe its URL back into our main one
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

                                // Basic JS dialog handlers (alerts/confirms)
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

                            // Optional: enables chrome://inspect debugging in dev builds
                            if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

                            loadUrl(TPOS_URL)
                        }

                    }
                )
            }
        }
        // Connect once on launch so we're ready when WS arrives
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

    // Payload model (only fields we need)
    data class TapToPayMsg(
        val amount: Int,
        val currency: String,
        val tpos_id: String? = null,
        val charge_id: String? = null
    )

    // Parse the server's string into TapToPayMsg.
// It tries JSON first, then falls back to key=value or {key:value} styles.
    private fun parseTapToPay(raw: String): TapToPayMsg? {
        // 1) Try JSON
        runCatching {
            val adapter = moshi.adapter(TapToPayMsg::class.java)
            adapter.fromJson(raw)?.let { return it }
        }

        // 2) Try to normalize common non-JSON formats
        val s = raw.trim()
            .removePrefix("TapToPay")
            .removePrefix("TapToPay(")
            .removeSuffix(")")
            .replace("""['"]""".toRegex(), "") // strip quotes if present

        // Accept "amount=50, currency=gbp, ..." or "{amount:50, currency:gbp}"
        val map = mutableMapOf<String, String>()
        val pairs = s.split(",", ";").map { it.trim() }
        for (p in pairs) {
            val kv = p.split("=", ":", limit = 2).map { it.trim() }
            if (kv.size == 2 && kv[0].isNotBlank()) map[kv[0].lowercase()] = kv[1]
        }

        val amount = map["amount"]?.toIntOrNull()
        val currency = map["currency"]
        return if (amount != null && !currency.isNullOrBlank()) {
            TapToPayMsg(amount = amount, currency = currency)
        } else null
    }

    // Open WS, listen, and charge on each valid message.
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

                // Ensure permissions + connect to a reader, then charge.
                runOnUiThread {
                    ensurePermissions(
                        onGranted = {
                            discoverAndConnect(
                                onReady = {
                                    // Stripe prefers lowercase currency codes
                                    val cur = msg.currency.lowercase()

                                    createPaymentIntent(
                                        amount = msg.amount,   // already smallest unit (e.g., pence)
                                        currency = cur
                                    ) { clientSecret, err ->
                                        if (err != null || clientSecret == null) {
                                            Log.e("TPOS_WS", "Create PI error: $err")
                                        } else {
                                            collectAndProcess(
                                                clientSecret,
                                                onOk  = { id -> Log.i("TPOS_WS", "✅ Paid: $id") },
                                                onFail = { e  -> Log.e("TPOS_WS", "❌ $e") }
                                            )
                                        }
                                    }
                                },
                                onError = { e -> Log.e("TPOS_WS", "Reader not ready: $e") }
                            )
                        },
                        onDenied = { e -> Log.e("TPOS_WS", "Permissions denied: $e") }
                    )
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

    private fun ensurePermissions(onGranted: () -> Unit, onDenied: (String) -> Unit) {
        val needed = mutableListOf<String>()

        // Location (Terminal requires this to start discovery)
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            needed += android.Manifest.permission.ACCESS_FINE_LOCATION
        }

        // Nearby devices on Android 12+ (optional but recommended)
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

        if (needed.isEmpty()) {
            onGranted(); return
        }

        val launcher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            val allGranted = grants.values.all { it }
            if (allGranted) onGranted()
            else onDenied("Location permission is required to discover readers")
        }
        launcher.launch(needed.toTypedArray())
    }

    private fun Request.Builder.withBearer(): Request.Builder =
        this.header("Authorization", "Bearer $ADMIN_BEARER_TOKEN")
    private fun tokenProvider() = object : ConnectionTokenProvider {
        override fun fetchConnectionToken(callback: ConnectionTokenCallback) {
            val req = Request.Builder()
                .url("$base/connection_token")
                .header("accept", "application/json")
                .withBearer()
                // same as curl -d '' (form-encoded empty body)
                .post(okhttp3.FormBody.Builder().build())
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
        val isSimulated = BuildConfig.DEBUG

        // Safety: never proceed if we have no location id at all
        if (TERMINAL_LOCATION_ID.isBlank()) {
            onError("Stripe Location ID is missing. Set TERMINAL_LOCATION_ID to your tml_… value.")
            return
        }

        val discoveryConfig = DiscoveryConfiguration.TapToPayDiscoveryConfiguration(
            isSimulated = isSimulated
        )

        Terminal.getInstance().discoverReaders(
            discoveryConfig,
            object : DiscoveryListener {
                override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
                    if (readers.isEmpty()) return

                    // Always pass a non-empty locationId (required by the SDK constructor)
                    val cfg = ConnectionConfiguration.TapToPayConnectionConfiguration(
                        locationId = TERMINAL_LOCATION_ID,
                        autoReconnectOnUnexpectedDisconnect = true,
                        tapToPayReaderListener = null
                    )

                    Terminal.getInstance().connectReader(readers.first(), cfg, object : ReaderCallback {
                        override fun onSuccess(reader: Reader) = onReady()
                        override fun onFailure(e: TerminalException) =
                            onError("Connect failed: ${e.errorMessage}")
                    })
                }
            },
            object : TerminalCallback {
                override fun onSuccess() {}
                override fun onFailure(e: TerminalException) =
                    onError("Discovery failed: ${e.errorMessage}")
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
            .post("""{"amount":$amount,"currency":"$currency"}"""
                .toRequestBody("application/json".toMediaType()))
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
                            override fun onFailure(e: TerminalException) =
                                onFail("Confirm failed: ${e.errorMessage}")
                        })
                    }
                    override fun onFailure(e: TerminalException) =
                        onFail("Collect failed: ${e.errorMessage}")
                })
            }
            override fun onFailure(e: TerminalException) =
                onFail("Retrieve failed: ${e.errorMessage}")
        })
    }

    // JSON models
    data class TokenResp(val secret: String?)
    data class PIResp(val client_secret: String?)
}
