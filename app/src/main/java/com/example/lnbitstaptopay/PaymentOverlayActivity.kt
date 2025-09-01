package com.example.lnbitstaptopay

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.external.callable.Callback
import com.stripe.stripeterminal.external.callable.DiscoveryListener
import com.stripe.stripeterminal.external.callable.ReaderCallback
import com.stripe.stripeterminal.external.callable.PaymentIntentCallback
import com.stripe.stripeterminal.external.models.ConnectionConfiguration
import com.stripe.stripeterminal.external.models.DiscoveryConfiguration
import com.stripe.stripeterminal.external.models.PaymentIntent
import com.stripe.stripeterminal.external.models.Reader
import com.stripe.stripeterminal.external.models.TerminalException

class PaymentOverlayActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences("tpos_prefs", MODE_PRIVATE) }
    private fun cfgLocId()  = prefs.getString("locId", "")!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Optional: prevent screenshots of payment UI
        // window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val clientSecret = intent.getStringExtra("client_secret")
        if (clientSecret.isNullOrBlank()) {
            Log.e("TPOS_OVERLAY", "No client_secret in intent; finishing")
            finish()
            return
        }

        // Permissions should already be granted by MainActivity, but double-check
        if (!hasAllRuntimePerms()) {
            Log.e("TPOS_OVERLAY", "Missing runtime permissions; finishing")
            finish()
            return
        }

        // Terminal should already be initialized in MainActivity’s registration flow.
        // If not, this will fail fast (that’s OK—overlay just finishes).
        ensureConnectedThen {
            collectAndProcess(
                clientSecret,
                onOk = {
                    Log.i("TPOS_OVERLAY", "Paid: $it")
                    finish() // reveal TWA underneath
                },
                onFail = {
                    Log.e("TPOS_OVERLAY", it)
                    finish() // reveal TWA so web POS can show error state
                }
            )
        }
    }

    private fun hasAllRuntimePerms(): Boolean {
        val want = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 31) {
            want += Manifest.permission.BLUETOOTH_SCAN
            want += Manifest.permission.BLUETOOTH_CONNECT
        }
        return want.all { ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    }

    private fun ensureConnectedThen(onReady: () -> Unit) {
        val connected = try { Terminal.getInstance().connectedReader != null } catch (_: Throwable) { false }
        if (connected) { onReady(); return }

        val discoveryConfig = DiscoveryConfiguration.TapToPayDiscoveryConfiguration(
            isSimulated = BuildConfig.DEBUG
        )

        try {
            Terminal.getInstance().discoverReaders(
                discoveryConfig,
                object : DiscoveryListener {
                    override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
                        if (readers.isEmpty()) return
                        val cfg = ConnectionConfiguration.TapToPayConnectionConfiguration(
                            locationId = cfgLocId(),
                            autoReconnectOnUnexpectedDisconnect = true,
                            tapToPayReaderListener = null
                        )
                        Terminal.getInstance().connectReader(
                            readers.first(),
                            cfg,
                            object : ReaderCallback {
                                override fun onSuccess(reader: Reader) {
                                    onReady()
                                }
                                override fun onFailure(e: TerminalException) {
                                    Log.e("TPOS_OVERLAY", "Connect failed [${e.errorCode}]: ${e.errorMessage}")
                                    finish()
                                }
                            }
                        )
                    }
                },
                object : Callback {
                    override fun onSuccess() { /* discovery started */ }
                    override fun onFailure(e: TerminalException) {
                        Log.e("TPOS_OVERLAY", "Discovery failed [${e.errorCode}]: ${e.errorMessage}")
                        finish()
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e("TPOS_OVERLAY", "Error starting discovery: ${t.message}", t)
            finish()
        }
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

