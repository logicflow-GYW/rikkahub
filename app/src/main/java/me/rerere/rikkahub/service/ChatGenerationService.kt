package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.CHAT_GENERATION_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import org.koin.android.ext.android.inject

private const val TAG = "ChatGenerationService"

/**
 * 流式生成前台服务。
 *
 * 作用：流式问答在后台生成期间，托住进程不被系统杀死，保证 .onSuccess 的最终落库
 * 一定执行，避免"退出应用后回答尾部丢失"。与流式增量落库（保险）互补：
 * 本服务是主防御——从源头避免进程被杀窗口。
 *
 * 生命周期（watchdog 模型，天然规避多起点/多会话计数竞态）：
 *  - 由 ChatService 在开启"带回答的生成"时通过 startForegroundService 启动。
 *  - 每次 onStartCommand、每次 ChatGenerationUpdate、每次 ChatGenerationEnded
 *    都重新武装 [WATCHDOG_MILLIS] 延时；超时未收到任何生成事件即 stopSelf。
 *  - 因此重复 start、多会话并发都不会让服务提前停止或卡死：
 *    只要还有生成在推进，事件就会持续 refreshing watchdog；生成结束后
 *    watchdog 到点自动收尾。
 */
class ChatGenerationService : Service() {

    private val eventBus: AppEventBus by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** watchdog：收到任一生成事件就重新计时；到点说明生成已完全结束，stopSelf。 */
    private var watchdogJob: Job? = null

    /** 已成功进入前台，防止 STICKY 重建时重复 startForeground 报警告。 */
    private var startedForeground = false

    /** 本实例只挂一次事件收集 */
    private var watchingEvents = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startForegroundCompat()) {
            stopSelf()
            return START_NOT_STICKY
        }
        armWatchdog()
        startObservingGenerations()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        watchdogJob?.cancel()
        serviceScope.cancel()
    }

    private fun startObservingGenerations() {
        if (watchingEvents) return
        watchingEvents = true
        serviceScope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is AppEvent.ChatGenerationUpdate,
                    is AppEvent.ChatGenerationEnded,
                    -> {
                        // 只要还有流式事件推来，说明生成仍活着，续命 watchdog
                        armWatchdog()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun armWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            delay(WATCHDOG_MILLIS)
            if (!isActive) return@launch
            // 安静期结束：没有新的生成事件，安全收尾前台
            shutdown()
        }
    }

    private fun shutdown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(): Boolean {
        if (startedForeground) return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            startedForeground = true
            true
        } catch (e: Exception) {
            // 部分 OEM/权限场景会拒绝特殊类型的 FGS；此时不托底，但业务不受影响
            Log.e(TAG, "Failed to start chat generation foreground service", e)
            false
        }
    }

    private fun buildNotification(): android.app.Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        return NotificationCompat.Builder(this, CHAT_GENERATION_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.chat_generation_ongoing_title))
            .setContentText(getString(R.string.chat_generation_ongoing_content))
            .setContentIntent(
                launchIntent?.let {
                    PendingIntent.getActivity(
                        this, 0, it,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                }
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 2002

        /** 静默时长超过该值视为无生成，前台服务自动收尾 */
        private const val WATCHDOG_MILLIS = 8000L

        fun start(context: Context) {
            val intent = Intent(context, ChatGenerationService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start chat generation foreground service", e)
            }
        }
    }
}
