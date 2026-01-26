package com.example.lnbitstaptopay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class TposWebSocketService : Service() {
    companion object {
        const val ACTION_START = "com.example.lnbitstaptopay.action.START_WS"
        const val ACTION_STOP = "com.example.lnbitstaptopay.action.STOP_WS"
        private const val NOTIF_CHANNEL_ID = "tpos_ws"
        private const val NOTIF_ID = 1001
    }

    private val prefs by lazy { getSharedPreferences("tpos_prefs", MODE_PRIVATE) }
    private fun cfgOrigin() = prefs.getString("origin", "")!!
    private fun cfgTposId() = prefs.getString("tposId", "")!!
    private fun wsUrl() = "wss://${cfgOrigin()}/api/v1/ws/${cfgTposId()}"

    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var wsReconnectPending = false
    private var wsBackoffMs = 500L
    @Volatile private var busy = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIF_ID, buildNotification())
                startTposWebSocket()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { ws?.cancel() } catch (_: Throwable) {}
        ws = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTposWebSocket() {
        if (cfgOrigin().isBlank() || cfgTposId().isBlank()) return
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
                if (busy) {
                    Log.i("TPOS_WS", "Ignoring WS: already collecting")
                    return
                }
                busy = true
                val i = Intent(this@TposWebSocketService, PaymentOverlayActivity::class.java)
                    .putExtra("client_secret", msg.client_secret)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                startActivity(i)
                mainHandler.postDelayed({ busy = false }, 500)
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

    data class TapToPayMsg(
        val payment_intent_id: String?,
        val client_secret: String?,
        val currency: String?,
        val amount: Int?,
        val tpos_id: String? = null,
        val payment_hash: String? = null
    )

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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "TPoS WebSocket",
            NotificationManager.IMPORTANCE_LOW
        )
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("TPoS Tap to Pay")
            .setContentText("Listening for Tap to Pay events")
            .setOngoing(true)
            .build()
    }
}
