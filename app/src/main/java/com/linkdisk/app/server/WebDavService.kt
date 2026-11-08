package com.linkdisk.app.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.linkdisk.app.MainActivity
import com.linkdisk.app.R
import com.linkdisk.app.data.LinkDiskPreferences
import com.linkdisk.app.data.StorageAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.BindException
import java.net.HttpURLConnection
import java.net.URL

enum class ServerStatus { STOPPED, STARTING, RUNNING, ERROR }
enum class PublicStatus { OFF, STARTING, RUNNING, RECONNECTING, ERROR }

data class ServerState(
    val status: ServerStatus = ServerStatus.STOPPED,
    val url: String = "",
    val error: String = "",
    val publicStatus: PublicStatus = PublicStatus.OFF,
    val publicUrl: String = "",
    val publicError: String = "",
)

class WebDavService : Service() {
    private var server: WebDavServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tunnelJob: Job? = null
    private var frpcProcess: FrpcProcess? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            return START_NOT_STICKY
        }
        if (_state.value.status == ServerStatus.RUNNING || _state.value.status == ServerStatus.STARTING) {
            return START_NOT_STICKY
        }

        startForegroundWithNotification(getString(R.string.service_starting))
        _state.value = ServerState(ServerStatus.STARTING)
        val preferences = LinkDiskPreferences(this)
        if (!StorageAccess.hasAccess(this)) {
            failServer(getString(R.string.storage_permission_required))
            return START_NOT_STICKY
        }
        val directory = preferences.sharedDirectory?.let(::File)
        if (directory == null) {
            failServer(getString(R.string.choose_scope_required))
            return START_NOT_STICKY
        }
        if (!StorageAccess.isWithinRoot(directory) || !directory.isDirectory) {
            failServer(getString(R.string.shared_directory_invalid))
            return START_NOT_STICKY
        }

        try {
            server = WebDavServer(
                directory,
                preferences.port,
                preferences.username,
                preferences.password,
            ).also { it.start(SOCKET_READ_TIMEOUT, false) }
            acquireNetworkLocks()
            val address = NetworkUtils.localIpv4Address(this)
            val url = address?.let { "http://$it:${preferences.port}/" }.orEmpty()
            _state.value = ServerState(ServerStatus.RUNNING, url = url)
            updateNotification(notificationContent())
            if (preferences.publicAccessEnabled) {
                startPublicAccess(preferences.tunnelConfig, preferences.username, preferences.password)
            }
        } catch (error: Exception) {
            val message = if (error is BindException || error.message?.contains("bind", true) == true) {
                "端口 ${preferences.port} 已被占用，请更换端口"
            } else {
                error.message ?: "WebDAV 服务启动失败"
            }
            failServer(message)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseResources()
        serviceScope.cancel()
        if (_state.value.status != ServerStatus.ERROR) _state.value = ServerState()
        super.onDestroy()
    }

    private fun startPublicAccess(config: TunnelConfig, username: String, password: String) {
        val publicUrl = try {
            config.validate()
            config.normalizedPublicUrl()
        } catch (error: IllegalArgumentException) {
            updatePublicState(
                PublicStatus.ERROR,
                publicUrl = config.publicUrl,
                error = error.message ?: "公网配置无效",
            )
            return
        }
        updatePublicState(PublicStatus.STARTING, publicUrl = publicUrl)
        tunnelJob = serviceScope.launch {
            superviseTunnel(config, username, password)
        }
    }

    private suspend fun superviseTunnel(config: TunnelConfig, username: String, password: String) {
        var retryDelay = INITIAL_RECONNECT_DELAY_MS
        while (serviceScope.isActive) {
            val exited = CompletableDeferred<String>()
            var probeJob: Job? = null
            var reachedPublic = false
            val process = FrpcProcess(
                this,
                serviceScope,
                onExit = { _, output -> exited.complete(output) },
            )
            frpcProcess = process
            val failure = try {
                process.start(config)
                probeJob = serviceScope.launch {
                    probePublicAccess(config, username, password) { reachedPublic = true }
                }
                frpcExitMessage(exited.await())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                error.message ?: "frpc 启动失败"
            } finally {
                probeJob?.cancel()
                process.close()
                if (frpcProcess === process) frpcProcess = null
            }

            if (!serviceScope.isActive) break
            updatePublicState(PublicStatus.RECONNECTING, error = failure)
            delay(retryDelay)
            retryDelay = if (reachedPublic) {
                INITIAL_RECONNECT_DELAY_MS
            } else {
                (retryDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            }
        }
    }

    private suspend fun probePublicAccess(
        config: TunnelConfig,
        username: String,
        password: String,
        onConnected: () -> Unit,
    ) {
        while (serviceScope.isActive) {
            val failure = probePublicAccessOnce(config, username, password)
            if (failure == null) {
                onConnected()
                updatePublicState(PublicStatus.RUNNING)
                return
            }
            val status = if (_state.value.publicStatus == PublicStatus.RECONNECTING) {
                PublicStatus.RECONNECTING
            } else {
                PublicStatus.ERROR
            }
            updatePublicState(status, error = "$failure；将继续探测")
            delay(PUBLIC_PROBE_RETRY_MS)
        }
    }

    private suspend fun probePublicAccessOnce(
        config: TunnelConfig,
        username: String,
        password: String,
    ): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(config.normalizedPublicUrl()).openConnection() as HttpURLConnection).apply {
                requestMethod = "OPTIONS"
                connectTimeout = PUBLIC_PROBE_TIMEOUT_MS
                readTimeout = PUBLIC_PROBE_TIMEOUT_MS
                instanceFollowRedirects = false
                if (username.isNotEmpty()) {
                    val credentials = Base64.encodeToString(
                        "$username:$password".toByteArray(Charsets.UTF_8),
                        Base64.NO_WRAP,
                    )
                    setRequestProperty("Authorization", "Basic $credentials")
                }
            }
            val responseCode = connection.responseCode
            when {
                responseCode == HttpURLConnection.HTTP_OK && hasDavHeader(connection) -> null
                responseCode == HttpURLConnection.HTTP_OK -> "公网地址未返回有效的 WebDAV 响应"
                responseCode == HttpURLConnection.HTTP_UNAUTHORIZED ||
                    responseCode == HttpURLConnection.HTTP_FORBIDDEN ->
                    "公网 WebDAV 身份验证失败（HTTP $responseCode）"
                else -> "公网 WebDAV 探测返回 HTTP $responseCode"
            }
        } catch (error: Exception) {
            "公网地址暂不可访问：${error.message ?: error.javaClass.simpleName}"
        } finally {
            connection?.disconnect()
        }
    }

    private fun hasDavHeader(connection: HttpURLConnection): Boolean =
        connection.getHeaderField("DAV")?.split(',')?.any { it.trim() == "1" || it.trim() == "2" } == true

    private fun frpcExitMessage(output: String): String {
        val detail = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
            .takeLast(3)
            .joinToString("\n")
            .takeLast(MAX_PUBLIC_ERROR_CHARS)
        return if (detail.isEmpty()) "frpc 已退出" else "frpc 已退出：\n$detail"
    }

    private fun updatePublicState(
        status: PublicStatus,
        publicUrl: String = _state.value.publicUrl,
        error: String = "",
    ) {
        _state.value = _state.value.copy(
            publicStatus = status,
            publicUrl = publicUrl,
            publicError = error,
        )
        updateNotification(notificationContent())
    }

    private fun notificationContent(): String {
        val state = _state.value
        val localContent = state.url.ifEmpty { getString(R.string.service_running) }
        return when (state.publicStatus) {
            PublicStatus.STARTING, PublicStatus.RECONNECTING -> "$localContent\n公网连接中"
            PublicStatus.RUNNING -> "$localContent\n公网：${state.publicUrl}"
            else -> localContent
        }
    }

    private fun failServer(message: String) {
        releaseResources()
        _state.value = ServerState(ServerStatus.ERROR, error = message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopServer() {
        releaseResources()
        _state.value = ServerState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseResources() {
        tunnelJob?.cancel()
        tunnelJob = null
        frpcProcess?.close()
        frpcProcess = null
        server?.stop()
        server = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        wakeLock = null
        wifiLock = null
    }

    @Suppress("DEPRECATION")
    private fun acquireNetworkLocks() {
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LinkDisk:WebDAV").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LinkDisk:WebDAV").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.notification_channel_description) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(content: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WebDavService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_link_disk)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, getString(R.string.stop_server), stop)
            .build()
    }

    private fun startForegroundWithNotification(content: String) {
        val notification = notification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(content))
    }

    companion object {
        private const val CHANNEL_ID = "webdav_server"
        private const val NOTIFICATION_ID = 1001
        private const val SOCKET_READ_TIMEOUT = 60_000
        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L
        private const val INITIAL_RECONNECT_DELAY_MS = 2_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val PUBLIC_PROBE_RETRY_MS = 3_000L
        private const val PUBLIC_PROBE_TIMEOUT_MS = 8_000
        private const val MAX_PUBLIC_ERROR_CHARS = 1_200
        const val ACTION_START = "com.linkdisk.app.action.START"
        const val ACTION_STOP = "com.linkdisk.app.action.STOP"

        private val _state = MutableStateFlow(ServerState())
        val state: StateFlow<ServerState> = _state.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WebDavService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WebDavService::class.java).setAction(ACTION_STOP))
        }
    }
}
