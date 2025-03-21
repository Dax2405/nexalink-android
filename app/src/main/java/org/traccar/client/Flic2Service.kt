package org.traccar.client

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.flic.flic2libandroid.Flic2Button
import io.flic.flic2libandroid.Flic2ButtonListener
import io.flic.flic2libandroid.Flic2Manager
import androidx.preference.PreferenceManager
import org.traccar.client.PositionProvider.PositionListener
import org.traccar.client.ProtocolFormatter.formatRequest
import org.traccar.client.RequestManager.RequestHandler
import org.traccar.client.RequestManager.sendRequestAsync
import androidx.core.content.edit
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.*
import android.os.Looper

// Mover la declaración de buttonListeners fuera de la clase Flic2Service
private val buttonListeners = HashMap<String, Boolean>()

class Flic2Service : Service() {

    private val NOTIFICATION_CHANNEL_ID = "Flic2ServiceChannel"
    private val NOTIFICATION_CHANNEL_NAME = "Flic2 Service"
    private val SERVICE_NOTIFICATION_ID = 3523
    private var wakeLock: PowerManager.WakeLock? = null
    private val TAG = "Flic2Service"
    private val handler = Handler() // Handler para tareas recurrentes

    private val pingHandler = Handler(Looper.getMainLooper())
    private lateinit var pingRunnable: Runnable

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created for Flic2")

        // Adquirir WakeLock para mantener el servicio activo
        acquireWakeLock()

        createNotificationChannel()

        // Solicitar permiso de notificaciones en Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // Aquí debes solicitar el permiso.  Como un servicio no puede solicitar permisos directamente,
                // debes enviar un intent a una Activity para que lo haga.
                val intent = Intent(this, MainActivity::class.java)
                intent.putExtra("requestNotificationPermission", true)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                val notification = createNotification()
                startForeground(SERVICE_NOTIFICATION_ID, notification)
            }
        } else {
            val notification = createNotification()
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }

        initializeFlic2Manager()

        // Iniciar un chequeo recurrente de la conexión del botón
        startPeriodicButtonCheck()
        initializePingTask()
    }

    @SuppressLint("InvalidWakeLockTag")
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Flic2Service::FlicWakeLock"
        )
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire() // Adquirir indefinidamente
    }

    private fun initializeFlic2Manager() {
        try {
            val manager = Flic2Manager.initAndGetInstance(applicationContext, Handler())
            connectToButtons(manager)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Flic2 manager", e)
        }
    }

    private fun connectToButtons(manager: Flic2Manager) {
        for (button in manager.buttons) {
            connectButton(button)
        }
    }

    private fun connectButton(button: Flic2Button) {
        try {
            if (!button.hasListener) {
                button.connect()
                listenToButton(button)
                button.hasListener = true
                Log.d(TAG, "Connected to button: ${button.bdAddr}")
            } else {
                Log.d(TAG, "Button ${button.bdAddr} already has a listener")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to button ${button.bdAddr}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH // Cambiar a HIGH para mayor prioridad
            )
            serviceChannel.setSound(null, null) // Sin sonido para no molestar
            serviceChannel.enableVibration(false)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Botón de pánico activo")
            .setContentText("Servicio de botón pánico funcionando en segundo plano")
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setPriority(NotificationCompat.PRIORITY_HIGH) // Alta prioridad
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true) // Notificación persistente
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun listenToButton(button: Flic2Button) {
        button.addListener(object : Flic2ButtonListener() {
            override fun onButtonUpOrDown(
                button: Flic2Button,
                wasQueued: Boolean,
                lastQueued: Boolean,
                timestamp: Long,
                isUp: Boolean,
                isDown: Boolean
            ) {
                if (isDown) {
                    Log.d(TAG, "Button pressed, sending SOS")
                    // Generar un pitido
                    val toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
                    toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200) // 200ms de duración
                    sendAlarm()
                }
            }
        })
    }

    private fun sendAlarm() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val isTrackingServiceEnabled = preferences.getBoolean(MainFragment.KEY_STATUS, false)

        if (!isTrackingServiceEnabled) {
            // Iniciar TrackingService si no está activo
            val startIntent = Intent(this, TrackingService::class.java)
            ContextCompat.startForegroundService(this, startIntent)

            // Actualizar la preferencia KEY_STATUS a true
            preferences.edit { putBoolean(MainFragment.KEY_STATUS, true) }

            // Enviar broadcast para actualizar el toggle
            val broadcastIntent = Intent("org.traccar.client.TRACKING_STARTED")
            sendBroadcast(broadcastIntent)
        }

        sendSmsNotification()

        PositionProviderFactory.create(this, object : PositionListener {
            override fun onPositionUpdate(position: Position) {
                val url = preferences.getString(MainFragment.KEY_URL, null)!!
                val request = formatRequest(url, position, ALARM_SOS)
                sendRequestAsync(request, object : RequestHandler {
                    override fun onComplete(success: Boolean) {
                        if (success) {
                            Log.d(TAG, "SOS signal sent successfully")
                        } else {
                            Log.e(TAG, "Failed to send SOS signal")
                        }
                    }
                })
            }

            override fun onPositionError(error: Throwable) {
                Log.e(TAG, "Error getting location", error)
            }
        }).requestSingleLocation()
    }

    private fun sendSmsNotification() {
        val json = """
            {
                "to": "+593987992649",
                "message": "SOS registrado: REVISE LA APP Nexalink"
            }
        """.trimIndent()

        val request = object : RequestHandler {
            override fun onComplete(success: Boolean) {
                if (success) {
                    Log.d(TAG, "SMS notification sent successfully")
                } else {
                    Log.e(TAG, "Failed to send SMS notification")
                }
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            val result = sendPostRequest(SMS_API_URL, json, SMS_API_KEY)
            withContext(Dispatchers.Main) {
                request.onComplete(result)
            }
        }
    }

    private suspend fun sendPostRequest(url: String, jsonBody: String, apiKey: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val mURL = URL(url)
                with(mURL.openConnection() as HttpsURLConnection) {
                    requestMethod = "POST"
                    doOutput = true
                    doInput = true
                    setRequestProperty("Authorization", "$apiKey")
                    setRequestProperty("Content-Type", "application/json")

                    val outputStream = outputStream
                    outputStream.write(jsonBody.toByteArray(Charsets.UTF_8))
                    outputStream.close()

                    Log.i(TAG, "Response Code : $responseCode")
                    success()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending POST request", e)
                false
            }
        }
    }

    private fun HttpsURLConnection.success(): Boolean {
        return try {
            if (responseCode in 200..299) {
                inputStream.bufferedReader().use { it.readText() }
                true
            } else {
                Log.e(TAG, "HTTP error code: $responseCode")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading response", e)
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")

        // Si el wakeLock se libera por alguna razón, volver a adquirirlo
        if (wakeLock?.isHeld == false) {
            acquireWakeLock()
        }

        // Reiniciar el servicio si es terminado por el sistema
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        super.onDestroy()

        // Eliminar el chequeo recurrente
        handler.removeCallbacks(periodicButtonCheck)

        // Liberar wakeLock
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        // Enviar broadcast para que ServiceRestartReceiver reinicie el servicio
        val broadcastIntent = Intent("org.traccar.client.RESTART_SERVICE")
        sendBroadcast(broadcastIntent)
        stopPingTask()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startPeriodicButtonCheck() {
        handler.postDelayed(periodicButtonCheck, 60 * 1000) // Chequear cada 60 segundos
    }

    private val periodicButtonCheck = object : Runnable {
        override fun run() {
            Log.d(TAG, "Checking Flic2 button connections...")
            try {
                val manager = Flic2Manager.getInstance()
                if (manager != null) {
                    for (button in manager.buttons) {
                        if (!button.hasListener) {
                            connectButton(button)
                        }
                    }
                } else {
                    Log.w(TAG, "Flic2Manager is null, re-initializing...")
                    initializeFlic2Manager()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during periodic button check", e)
            } finally {
                handler.postDelayed(this, 60 * 1000) // Repetir cada 60 segundos
            }
        }
    }

    private fun initializePingTask() {
        pingRunnable = Runnable {
            sendPing()
            pingHandler.postDelayed(pingRunnable, PING_INTERVAL.toLong())
        }
        pingHandler.postDelayed(pingRunnable, PING_INTERVAL.toLong())
    }

    private fun stopPingTask() {
        pingHandler.removeCallbacks(pingRunnable)
    }

    private fun getDeviceIdentifier(): String {
        val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        return sharedPreferences.getString(MainFragment.KEY_DEVICE, "") ?: ""
    }

    private fun sendPing() {
        val deviceId = getDeviceIdentifier()
        if (deviceId.isNotEmpty()) {
            val pingUrl = "http://93.127.213.145:8007/devices/$deviceId/ping"
            RequestManager.sendRequestAsync(pingUrl, object : RequestManager.RequestHandler {
                override fun onComplete(success: Boolean) {
                    if (success) {
                        Log.d(ContentValues.TAG, "Ping exitoso")
                        StatusActivity.addMessage("Ping exitoso")
                    } else {
                        Log.d(ContentValues.TAG, "Fallo en el ping")
                        StatusActivity.addMessage("Fallo en el ping")
                    }
                }
            })
        } else {
            Log.d(ContentValues.TAG, "ID de dispositivo no encontrado para el ping")
            StatusActivity.addMessage("ID de dispositivo no encontrado para el ping")
        }
    }

    companion object {
        const val ALARM_SOS = "sos"
        private const val SMS_API_URL = "https://www.traccar.org/sms"
        private const val SMS_API_KEY = "fV1LiyhMRp2a6UN40CVWZM:APA91bFe8MB0KiD2FXp-HH6wZTMbx0GVB-ObO5ynx6LEMVBYRCuS4SH6Z8vgAeV1n9d6gKh00tPA0N_MO3eAhXFe5yEOUqAm3Snt5H5CoEMuRG9MPw_KxVw"
        private const val PING_INTERVAL = 3 * 60 * 1000

    }
}

var Flic2Button.hasListener: Boolean
    get() = buttonListeners[bdAddr] ?: false
    set(value) {
        buttonListeners[bdAddr] = value
    }