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
// OkHttp
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Callback as OkHttpCallback

// JSON
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

import java.io.IOException

class MainActivity : ComponentActivity() {

    // === CONFIG ===
    private val BACKEND_ORIGIN = "https://condct.com"
    private val ADMIN_BEARER_TOKEN =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJiZW4iLCJhdXRoX3RpbWUiOjE3NTU3OTUyOTksImFwaV90b2tlbl9pZCI6IjgzY2RkM2IxZTgxYjRlYTBhODA4YWExMjE2NWE0ZGIwIiwiZXhwIjoxODgxNjE1NTk5fQ.q6BJKJ1AmyNJYyD_v9ZEsu_4YXJiAePueEO3H3gwXzE"
    private val TERMINAL_LOCATION_ID =
        if (BuildConfig.DEBUG) "" else "tml_GKQlZgyIgq3AAy"
    // ==============

    private val base = "$BACKEND_ORIGIN/api/v1/fiat/stripe/terminal"

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
                var status by remember { mutableStateOf("Initializing…") }
                var ready by remember { mutableStateOf(false) }
                var busy by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    ensurePermissions(
                        onGranted = {
                            discoverAndConnect(
                                onReady = { status = "Ready. Tap to Pay available."; ready = true },
                                onError  = { msg -> status = "❌ $msg" }
                            )
                        },
                        onDenied = { msg -> status = "❌ $msg" }
                    )
                }

                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(status)
                        Spacer(Modifier.height(16.dp))
                        Button(
                            enabled = ready && !busy,
                            onClick = {
                                busy = true
                                status = "Creating $1 PaymentIntent…"
                                createPaymentIntent(
                                    amount = 50,
                                    currency = "gbp"
                                ) { clientSecret, err ->
                                    if (err != null) {
                                        status = "❌ $err"
                                        busy = false
                                    } else {
                                        status = "Ready for tap…"
                                        collectAndProcess(
                                            clientSecret!!,
                                            onOk = { id ->
                                                status = "✅ Paid: $id"
                                                busy = false
                                            },
                                            onFail = { e ->
                                                status = "❌ $e"
                                                busy = false
                                            }
                                        )
                                    }
                                }
                            }
                        ) { Text("Charge $1") }
                    }
                }
            }
        }
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
