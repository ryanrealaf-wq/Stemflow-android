package com.example.stemflow.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.stemflow.data.AppDatabase
import com.example.stemflow.data.JobRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Native Android foreground service for audio-to-MIDI stem processing.
 * Configured with FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING so jobs survive
 * app backgrounding, task switching, and device screen locks.
 */
class StemFlowProcessingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var processingJob: Job? = null
    @Volatile private var isStopRequested = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_CANCEL) {
            isStopRequested = true
            processingJob?.cancel()
            updateNotification("Cancelling StemFlow processing…", 0f, indeterminate = true)
            stopForegroundAndFinish()
            return START_NOT_STICKY
        }

        val jobId = intent?.getLongExtra(EXTRA_JOB_ID, -1L) ?: -1L
        if (jobId == -1L) {
            stopSelf()
            return START_NOT_STICKY
        }

        isStopRequested = false
        val initialNotification = buildNotification("Preparing 6-stem neural pipeline…", 0.02f)
        
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            0
        }
        
        ServiceCompat.startForeground(this, NOTIFICATION_ID, initialNotification, fgsType)

        processingJob?.cancel()
        processingJob = serviceScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            val repository = JobRepository(applicationContext, db.jobDao())

            try {
                StemFlowPipeline.executeJob(
                    context = applicationContext,
                    jobRepository = repository,
                    jobId = jobId
                ) { phase, progress, peakRam ->
                    val progressPercent = (progress * 100).toInt()
                    val statusText = "[$phase $progressPercent%] RAM: ${"%.1f".format(peakRam)} MB"
                    updateNotification(statusText, progress)
                }

                // Complete
                updateNotification("Complete: 6 stems & MIDI exported", 1.0f, ongoing = false)
            } catch (e: CancellationException) {
                updateNotification("Processing cancelled", 0f, ongoing = false)
            } catch (e: Throwable) {
                val errorMsg = e.message ?: e::class.java.simpleName
                updateNotification("Failed: $errorMsg", 0f, ongoing = false)
            } finally {
                stopForegroundAndFinish()
            }
        }

        return START_NOT_STICKY
    }

    private fun stopForegroundAndFinish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun buildNotification(text: String, progress: Float, indeterminate: Boolean = false, ongoing: Boolean = true): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, StemFlowProcessingService::class.java).apply {
                action = ACTION_CANCEL
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("StemFlow Neural Instrument")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (ongoing) {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            if (indeterminate) {
                builder.setProgress(0, 0, true)
            } else {
                builder.setProgress(100, (progress * 100).toInt(), false)
            }
        }

        return builder.build()
    }

    private fun updateNotification(text: String, progress: Float, indeterminate: Boolean = false, ongoing: Boolean = true) {
        val notification = buildNotification(text, progress, indeterminate, ongoing)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "StemFlow Audio Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live progress for 6-stem neural separation and MIDI transcription"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        isStopRequested = true
        processingJob?.cancel()
        stopSelf(startId)
    }

    override fun onDestroy() {
        isStopRequested = true
        processingJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "stemflow_media_processing"
        const val NOTIFICATION_ID = 4001
        const val EXTRA_JOB_ID = "extra_job_id"
        const val ACTION_CANCEL = "com.example.stemflow.ACTION_CANCEL"

        fun startProcessing(context: Context, jobId: Long) {
            val intent = Intent(context, StemFlowProcessingService::class.java).apply {
                putExtra(EXTRA_JOB_ID, jobId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelProcessing(context: Context) {
            val intent = Intent(context, StemFlowProcessingService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
