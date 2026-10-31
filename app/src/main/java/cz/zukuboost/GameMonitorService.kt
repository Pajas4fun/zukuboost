package cz.zukuboost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hlídá, která appka je v popředí, a při spuštění hry zapne boost. */
class GameMonitorService : Service() {

    companion object {
        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 1
        private const val LEAVE_DELAY_MS = 3_000L
        private const val POLL_MS = 1_500L
        private const val RETRY_MS = 10_000L
        const val THERMAL_LIMIT_C = 44f

        // Krátké přepnutí do těchto appek (přihlášení, systémová lišta…) neukončí boost
        private val IGNORED = setOf(
            "com.android.systemui",
            "com.google.android.gms",
            "com.google.android.play.games",
            "com.miui.securitycenter",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )

        @Volatile var running = false
            private set

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, GameMonitorService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GameMonitorService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefs: Prefs
    private lateinit var power: PowerManager
    private var overlay: OverlayController? = null
    private var overlayJob: Job? = null
    private var guardJob: Job? = null

    private var activeGame: String? = null
    private var applied = false
    private var lastApplyTry = 0L
    private var lastRestoreTry = 0L
    private var lastForeground: String? = null
    private var lastQueryEnd = 0L
    private var leftSince = 0L
    private var sessionStart = 0L
    private val session = SessionStats()
    @Volatile private var shownStatus = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        power = getSystemService(PowerManager::class.java)
        createChannel()
        shownStatus = idleText()
        if (!startFg(notification(shownStatus))) {
            stopSelf()
            return
        }
        running = true
        scope.launch {
            // Po pádu/restartu nejdřív vrátíme původní nastavení
            if (prefs.boostActive) runCatching { BoostEngine.restore(this@GameMonitorService) }
            // Po restartu telefonu HyperOS vypne zmrazování appek – obnovíme ho, jakmile je Shizuku připravené
            runCatching { BoostEngine.ensureFreezer(this@GameMonitorService) }
            loop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        val app = applicationContext
        overlayJob?.cancel()
        guardJob?.cancel()
        overlay?.hide()
        overlay = null
        scope.cancel()
        if (Prefs(app).boostActive) {
            CoroutineScope(Dispatchers.IO).launch { runCatching { BoostEngine.restore(app) } }
        }
        super.onDestroy()
    }

    private suspend fun loop() {
        while (scope.isActive) {
            val now = SystemClock.elapsedRealtime()
            val paused = prefs.paused
            val fg = foreground()
            // Zhasnutý displej = nehraješ, boost se vrátí (šetří baterii, DND nezůstane viset)
            val target = if (!paused && power.isInteractive && fg != null && fg in prefs.games) fg else null
            val active = activeGame

            when {
                target != null && target != active -> {
                    if (active != null) deactivate()
                    activate(target)
                }
                target != null -> {
                    leftSince = 0L
                    // Shizuku nebyl připravený (třeba po restartu telefonu) – zkusíme znovu
                    if (!applied && now - lastApplyTry >= RETRY_MS) tryApply(target)
                }
                active != null -> {
                    if (paused) {
                        deactivate()
                    } else if (leftSince == 0L) {
                        leftSince = now
                    } else if (now - leftSince >= LEAVE_DELAY_MS) {
                        deactivate()
                    }
                }
                else -> {
                    // Vrácení nastavení se nepovedlo (Shizuku spadl) – zkoušíme, dokud to nevyjde
                    if (prefs.boostActive && now - lastRestoreTry >= RETRY_MS) {
                        lastRestoreTry = now
                        runCatching { BoostEngine.restore(this) }
                    }
                    setStatus(idleText())
                }
            }
            delay(POLL_MS)
        }
    }

    @Suppress("DEPRECATION")
    private fun foreground(): String? {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return lastForeground
        val now = System.currentTimeMillis()
        // Napoprvé se díváme hodinu zpět (hra už mohla běžet), pak jen na nové události
        val from = if (lastQueryEnd == 0L) now - 3_600_000L else lastQueryEnd - 5_000L
        lastQueryEnd = now
        val events = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        var pkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && e.packageName !in IGNORED) {
                pkg = e.packageName
            }
        }
        if (pkg != null) lastForeground = pkg
        return lastForeground
    }

    private suspend fun tryApply(pkg: String) {
        lastApplyTry = SystemClock.elapsedRealtime()
        val result = runCatching { BoostEngine.apply(this, pkg) }
        applied = result.isSuccess
        setStatus(
            if (applied) "Boost aktivní: ${label(pkg)}"
            else "Čekám na Shizuku… (${result.exceptionOrNull()?.message})"
        )
    }

    private suspend fun activate(pkg: String) {
        activeGame = pkg
        applied = false
        leftSince = 0L
        sessionStart = SystemClock.elapsedRealtime()
        session.reset()
        tryApply(pkg)
        if (prefs.opt(Opt.THERMAL)) startThermalGuard()
        if (prefs.opt(Opt.MEASURE) || prefs.opt(Opt.OVERLAY)) startMeasure(pkg)
    }

    private suspend fun deactivate() {
        val game = activeGame ?: return
        activeGame = null
        applied = false
        leftSince = 0L
        guardJob?.cancelAndJoin()
        guardJob = null
        stopOverlay()
        runCatching { BoostEngine.restore(this) }

        val elapsed = SystemClock.elapsedRealtime() - sessionStart
        val minutes = elapsed / 60_000
        val name = label(game)
        val avg = session.avg
        if (minutes >= 1) {
            prefs.addHistory(
                Session(
                    pkg = game,
                    label = name,
                    start = System.currentTimeMillis() - elapsed,
                    minutes = minutes,
                    avg = avg,
                    min = session.min,
                    drops = session.drops,
                    maxCpu = session.maxCpu,
                    maxBat = session.maxBat,
                )
            )
        }
        prefs.lastSession = buildString {
            append(name).append(" · ").append(minutes).append(" min")
            if (avg != null) append(" · ø ").append(avg).append(" FPS · propady ").append(session.drops)
        }
        setStatus(idleText())
    }

    private fun idleText(): String {
        if (prefs.paused) return "Pozastaveno"
        val last = prefs.lastSession
        return if (last.isBlank()) "Čekám na spuštění hry…" else "Čekám na hru · naposledy: $last"
    }

    /** Pojistka pro „Nehlásit hrám přehřátí“: při vysoké teplotě vrátí skutečné hlášení. */
    private fun startThermalGuard() {
        guardJob?.cancel()
        guardJob = scope.launch {
            while (isActive) {
                delay(5_000)
                if (batteryTemp(this@GameMonitorService) >= THERMAL_LIMIT_C) {
                    runCatching { BoostEngine.releaseThermal(this@GameMonitorService) }
                    setStatus("Pojistka: baterie ${THERMAL_LIMIT_C.toInt()} °C, hlášení teplot vráceno")
                    break
                }
            }
        }
    }

    /** Měření FPS a teplot; overlay se zobrazí jen když je zapnutý. */
    private suspend fun startMeasure(pkg: String) {
        val showOverlay = prefs.opt(Opt.OVERLAY) && Settings.canDrawOverlays(this)
        if (showOverlay) {
            withContext(Dispatchers.Main) {
                if (overlay == null) overlay = OverlayController(this@GameMonitorService).also { it.show() }
            }
        }
        overlayJob?.cancel()
        overlayJob = scope.launch {
            val meter = FpsMeter(pkg)
            val stats = Stats(this@GameMonitorService)
            try {
                while (isActive) {
                    val fps = runCatching { meter.sample() }.getOrNull()
                    if (fps != null) session.add(fps)
                    val text = stats.format(fps, session, SystemClock.elapsedRealtime() - sessionStart)
                    if (showOverlay) withContext(Dispatchers.Main) { overlay?.update(text) }
                    delay(1_000)
                }
            } finally {
                withContext(NonCancellable) { runCatching { meter.close() } }
            }
        }
    }

    private suspend fun stopOverlay() {
        overlayJob?.cancelAndJoin()
        overlayJob = null
        withContext(Dispatchers.Main) {
            overlay?.hide()
            overlay = null
        }
    }

    private fun label(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Herní boost", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle("ZukuBoost")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun setStatus(text: String) {
        if (text == shownStatus) return
        shownStatus = text
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text)) }
    }

    private fun startFg(n: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        true
    } catch (t: Throwable) {
        false
    }
}
