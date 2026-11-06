package ru.tarifradar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/** Будильник: уведомление + громкий звук (как у будильника, звучит даже в беззвучном) + вибрация. */
object AlarmPlayer {
    private const val CH_ALARM = "tariff_alarm_v1"
    private const val CH_INFO = "tariff_info_v1"
    private const val ID_ALARM = 101
    private const val ID_INFO = 102
    private const val RING_MS = 60_000L

    private var player: MediaPlayer? = null
    private var vib: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopSound() }

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Будильник тарифов", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Когда тариф дошёл до порога"
                setSound(null, null) // звук играем сами, через канал будильника
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_INFO, "Сообщения радара", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun fire(ctx: Context, text: String, sound: Boolean) {
        val c = ctx.applicationContext
        ensureChannels(c)
        val open = PendingIntent.getActivity(
            c, 0,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getBroadcast(
            c, 1, Intent(c, StopAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(c, CH_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Тариф вырос!")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(open)
            .setDeleteIntent(stop)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null as Icon?, "Стоп", stop).build())
            .build()
        try {
            c.getSystemService(NotificationManager::class.java)?.notify(ID_ALARM, n)
        } catch (_: SecurityException) {}
        if (sound) startSound(c)
    }

    fun info(ctx: Context, title: String, text: String) {
        val c = ctx.applicationContext
        ensureChannels(c)
        val n = Notification.Builder(c, CH_INFO)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        try {
            c.getSystemService(NotificationManager::class.java)?.notify(ID_INFO, n)
        } catch (_: SecurityException) {}
    }

    private fun startSound(c: Context) {
        stopSound()
        val candidates = listOfNotNull<Uri>(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        )
        for (uri in candidates) {
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(c, uri)
                    isLooping = true
                    prepare()
                    start()
                }
                break
            } catch (_: Throwable) {
                player?.release()
                player = null
            }
        }
        vib = vibrator(c)
        try {
            vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
        } catch (_: Throwable) {}
        handler.postDelayed(autoStop, RING_MS)
    }

    fun stopSound() {
        handler.removeCallbacks(autoStop)
        player?.let {
            try { it.stop() } catch (_: Throwable) {}
            it.release()
        }
        player = null
        try { vib?.cancel() } catch (_: Throwable) {}
        vib = null
    }

    fun stop(ctx: Context) {
        stopSound()
        ctx.getSystemService(NotificationManager::class.java)?.cancel(ID_ALARM)
    }

    private fun vibrator(c: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            c.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            c.getSystemService(Vibrator::class.java)
        }
}

class StopAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AlarmPlayer.stop(context)
    }
}
