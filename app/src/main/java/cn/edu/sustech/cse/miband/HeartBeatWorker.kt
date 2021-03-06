package cn.edu.sustech.cse.miband

import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import cn.edu.sustech.cse.miband.db.HeartBeat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.debug
import org.jetbrains.anko.warn
import org.threeten.bp.LocalDateTime

private const val CHANNEL_ID = "channel-heart-beat"
private const val NOTIFICATION_ID = 1

class HeartBeatWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters), AnkoLogger {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    override suspend fun doWork(): Result = coroutineScope {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID,
                    "Real-time Heart Beat Monitor",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        setForeground(createForegroundInfo(null))

        val miBand = findDevice() ?: return@coroutineScope Result.failure()
        miBand.connect()
        val bpmChannel = Channel<Int>()
        val job = launch { miBand.startRealtimeHeartRate(bpmChannel) }
        try {
            saveBpmData(bpmChannel)
        } finally {
            job.cancel()
            bpmChannel.close()
            miBand.disconnect()
        }
        Result.success()
    }

    private suspend fun saveBpmData(channel: Channel<Int>) {
        val dao = applicationContext.database.heartBeatDao()
        while (!isStopped) {
            val bpm = channel.receive()
            debug { "$bpm bpm" }
            setForeground(createForegroundInfo(bpm))
            dao.insert(HeartBeat(LocalDateTime.now(), bpm))
        }
    }

    private fun findDevice(): MiBand? {
        val deviceAddress = inputData.getString("device-address")
            ?: throw IllegalArgumentException("missing device-address")
        val deviceKey = inputData.getString("device-key")
            ?: throw IllegalArgumentException("missing device-key")
        val device = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT).find { device ->
            deviceAddress == device.address
        }
        if (device == null) {
            warn { "device $deviceAddress is not connected" }
            return null
        }
        return MiBand(applicationContext, device, deviceKey, null)
    }


    private fun createForegroundInfo(beatRate: Int?): ForegroundInfo {
        val intent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Heart Beat Monitoring")
            .setTicker("Heart Beat Monitoring")
            .setContentText(
                if (beatRate == null) "Connecting…"
                else "$beatRate bpm"
            )
            .setSmallIcon(R.drawable.ic_baseline_favorite_24)
            .addAction(R.drawable.ic_baseline_stop_24, "Stop", intent)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            //FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or FOREGROUND_SERVICE_TYPE_DATA_SYNC
            0
        } else {
            0
        }
        return ForegroundInfo(NOTIFICATION_ID, notification, type)
    }


}