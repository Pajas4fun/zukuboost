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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hlídá, která appka je v popředí, a při spuštění hry zapne boost. */
class GameMonitorService : Service(), GamePanelController.Listener {

    companion object {
        private const val CHANNEL = "monitor"
        private const val CHANNEL_DND = "monitor_dnd"
        private const val NOTIF_ID = 1
        private const val LEAVE_DELAY_MS = 2_000L
        private const val POLL_MS = 1_500L
        private const val RETRY_MS = 10_000L
        const val THERMAL_LIMIT_C = 44f
        const val ACTION_TOUCH_LOCK = "cz.zukuboost.TOUCH_LOCK"
        const val ACTION_SCREENSHOT = "cz.zukuboost.SCREENSHOT"
        const val ACTION_RECORD = "cz.zukuboost.RECORD"

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
    @Volatile private var overlay: OverlayController? = null
    private var overlayJob: Job? = null
    private var guardJob: Job? = null
    private var touchLock: TouchLockController? = null
    private var edgeBlock: EdgeBlockController? = null
    private var panel: GamePanelController? = null
    // Živá data pro herní panel
    @Volatile private var lastFps: Int? = null
    @Volatile private var lastCpu: Float? = null
    @Volatile private var lastBat = 0f
    private val fpsHistory = ArrayDeque<Int>()
    @Volatile private var recording = false
    private var recordPath = ""
    private var recordJob: Job? = null
    private var autoHzDone = false
    private var ignoredFg: String? = null
    private var ignoredFgSince = 0L

    // Chytré chlazení
    private var coolJob: Job? = null
    @Volatile private var baseHz = 0f
    @Volatile private var coolLevel = 0
    private var brightnessBeforeCool: String? = null

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
            // Zbytek po pádu vrátíme – ale ne když boost právě nastavilo spuštění hry z appky
            val launching = System.currentTimeMillis() < prefs.launchGraceUntil
            if (prefs.boostActive && !launching) runCatching { BoostEngine.restore(this@GameMonitorService) }
            // Po restartu telefonu HyperOS vypne zmrazování appek – obnovíme ho, jakmile je Shizuku připravené
            runCatching { BoostEngine.ensureFreezer(this@GameMonitorService) }
            loop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOUCH_LOCK -> toggleTouchLock()
            ACTION_SCREENSHOT -> takeScreenshot()
            ACTION_RECORD -> toggleRecording()
        }
        return START_STICKY
    }

    /** Volá se na hlavním vlákně z tlačítka v notifikaci. */
    private fun toggleTouchLock() {
        if (!Settings.canDrawOverlays(this)) return
        val lock = touchLock ?: TouchLockController(this) { refreshNotification() }.also { touchLock = it }
        if (lock.isLocked) lock.unlock() else lock.lock()
        refreshNotification()
    }

    private fun takeScreenshot() {
        scope.launch {
            withContext(Dispatchers.Main) { panel?.setHandleVisible(false) }
            val path = runCatching { BoostEngine.screenshot() }.getOrDefault("")
            withContext(Dispatchers.Main) { panel?.setHandleVisible(true) }
            if (path.isNotEmpty()) {
                android.media.MediaScannerConnection.scanFile(this@GameMonitorService, arrayOf(path), null, null)
            }
            showToast(if (path.isNotEmpty()) "Snímek uložen do Obrázky/ZukuBoost" else "Snímek se nepodařil")
        }
    }

    // ---------- Herní panel (volá se na hlavním vlákně) ----------

    override fun onScreenshot() = takeScreenshot()

    override fun onRecord() = toggleRecording()

    override fun onTouchLock() = toggleTouchLock()

    override fun onToggleOverlay() {
        val current = overlay
        if (current != null) {
            current.hide()
            overlay = null
        } else {
            overlay = OverlayController(this).also { it.show() }
            val game = activeGame
            // Když se neměřilo (měření i overlay vypnuté), spustíme měření teď
            if (overlayJob?.isActive != true && game != null) scope.launch { startMeasure(game, forceOverlay = true) }
        }
    }

    override fun onToggleEdges() {
        val e = edgeBlock ?: EdgeBlockController(this).also { edgeBlock = it }
        if (e.isShown) e.hide() else e.show(prefs.edgeWidthDp)
    }

    override fun onCleanRam() {
        val game = activeGame ?: return
        scope.launch {
            val am = getSystemService(android.app.ActivityManager::class.java)
            fun freeMb(): Long {
                val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                return mi.availMem / 1_048_576
            }
            val before = freeMb()
            val killed = runCatching { BoostEngine.preLaunchClean(this@GameMonitorService, game) }.getOrDefault(0)
            delay(800)
            val freed = (freeMb() - before).coerceAtLeast(0)
            showToast("Uvolněno $freed MB · ukončeno $killed appek")
        }
    }

    override fun isRecording() = recording

    override fun isOverlayOn() = overlay != null

    override fun isEdgesOn() = edgeBlock?.isShown == true

    override fun data(): PanelData {
        val hist = synchronized(fpsHistory) { fpsHistory.toList() }
        val bat = if (lastBat > 0f) lastBat else batteryTemp(this)
        return PanelData(
            fps = lastFps,
            avg = session.avg,
            min = session.min,
            drops = session.drops,
            cpu = lastCpu,
            bat = bat,
            history = hist,
            measuring = overlayJob?.isActive == true,
        )
    }

    override fun gameName() = activeGame?.let { label(it) } ?: ""

    private fun toggleRecording() {
        scope.launch {
            if (recording) {
                runCatching { BoostEngine.stopRecording() }
                finishRecording()
            } else {
                recordPath = runCatching { BoostEngine.startRecording() }.getOrDefault("")
                if (recordPath.isEmpty()) return@launch
                recording = true
                withContext(Dispatchers.Main) { refreshNotification() }
                showToast("Nahrávám (bez zvuku, max 3 min)")
                recordJob?.cancel()
                recordJob = scope.launch {
                    delay(182_000) // screenrecord se sám zastaví po 3 minutách
                    finishRecording()
                }
            }
        }
    }

    private suspend fun finishRecording() {
        if (!recording) return
        recording = false
        // Při automatickém konci po 3 min běžíme UVNITŘ recordJob – sami sebe rušit nesmíme,
        // jinak by se video nezaregistrovalo do galerie
        val self = currentCoroutineContext()[Job]
        if (recordJob != self) recordJob?.cancel()
        delay(1_200) // soubor se dopisuje
        val path = recordPath
        if (path.isNotEmpty()) {
            android.media.MediaScannerConnection.scanFile(this, arrayOf(path), null, null)
        }
        withContext(Dispatchers.Main) { refreshNotification() }
        showToast("Video uloženo do Filmy/ZukuBoost")
    }

    private suspend fun showToast(text: String) {
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(this@GameMonitorService, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshNotification() {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(shownStatus)) }
    }

    override fun onDestroy() {
        running = false
        val app = applicationContext
        overlayJob?.cancel()
        guardJob?.cancel()
        coolJob?.cancel()
        overlay?.hide()
        overlay = null
        touchLock?.unlock()
        edgeBlock?.hide()
        panel?.hide()
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
            // Bez přístupu k využití (systém ho odebral) nevíme, co je v popředí – boost raději vrátíme
            val canSee = hasUsageAccess(this)
            if (!canSee) lastForeground = null
            val target = if (canSee && !paused && power.isInteractive && fg != null && fg in prefs.games) fg else null
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
                    val grace = System.currentTimeMillis() < prefs.launchGraceUntil // hra se právě spouští z appky
                    if (prefs.boostActive && !grace && now - lastRestoreTry >= RETRY_MS) {
                        lastRestoreTry = now
                        runCatching { BoostEngine.restore(this) }
                    }
                    setStatus(idleText())
                }
            }
            delay(POLL_MS)
        }
    }

    /**
     * Která appka je v popředí. Krátké přepnutí do „ignorovaných“ appek (systémová lišta, přihlášení Google,
     * Game Turbo) boost neukončí – ale když v nich zůstaneš déle než 12 s (např. po zavření hry se vrátíš
     * do seznamu her v Game Turbu), bere se to jako konec hry.
     */
    @Suppress("DEPRECATION")
    private fun foreground(): String? {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return lastForeground
        val now = System.currentTimeMillis()
        // Napoprvé se díváme hodinu zpět (hra už mohla běžet), pak jen na nové události
        val from = if (lastQueryEnd == 0L) now - 3_600_000L else lastQueryEnd - 5_000L
        lastQueryEnd = now
        val events = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        var latest: String? = null
        var latestTime = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                latest = e.packageName
                latestTime = e.timeStamp
            }
        }
        if (latest != null) {
            if (latest in IGNORED) {
                if (ignoredFg != latest) {
                    ignoredFg = latest
                    ignoredFgSince = latestTime
                }
            } else {
                lastForeground = latest
                ignoredFg = null
            }
        }
        val ign = ignoredFg
        if (ign != null && now - ignoredFgSince > 12_000) return ign
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
        autoHzDone = false
        baseHz = BoostEngine.sessionHz(this, pkg)
        if (prefs.opt(Opt.SMART_COOL)) startCooling(pkg)
        if (prefs.opt(Opt.GAME_PANEL) && Settings.canDrawOverlays(this)) {
            withContext(Dispatchers.Main) {
                val p = panel ?: GamePanelController(this@GameMonitorService, this@GameMonitorService).also { panel = it }
                p.show()
            }
        }
        if (prefs.opt(Opt.EDGE_BLOCK) && Settings.canDrawOverlays(this)) {
            withContext(Dispatchers.Main) {
                val e = edgeBlock ?: EdgeBlockController(this@GameMonitorService).also { edgeBlock = it }
                e.show(prefs.edgeWidthDp)
            }
        }
        if (prefs.opt(Opt.MEASURE) || prefs.opt(Opt.OVERLAY)) startMeasure(pkg)
    }

    private suspend fun deactivate() {
        val game = activeGame ?: return
        activeGame = null
        applied = false
        leftSince = 0L
        guardJob?.cancelAndJoin()
        guardJob = null
        coolJob?.cancelAndJoin()
        coolJob = null
        coolLevel = 0
        // Jas ztlumený chlazením vrátíme na hodnotu před ztlumením
        brightnessBeforeCool?.let { runCatching { BoostEngine.setBrightness(it) } }
        brightnessBeforeCool = null
        prefs.coolBrightness = null
        stopOverlay()
        withContext(Dispatchers.Main) {
            touchLock?.unlock()
            edgeBlock?.hide()
            panel?.hide()
        }
        lastFps = null
        lastCpu = null
        lastBat = 0f
        synchronized(fpsHistory) { fpsHistory.clear() }
        if (recording) {
            runCatching { BoostEngine.stopRecording() }
            finishRecording()
        }
        val hadBoost = prefs.boostActive
        val restored = runCatching { BoostEngine.restore(this) }.isSuccess
        if (restored && hadBoost) showToast("✓ Hra ukončena – nastavení vrácena")

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

    /**
     * Chytré chlazení: místo toho, aby čip narazil na throttling a FPS spadly skokově,
     * plynule snížíme Hz displeje (hra pak kreslí méně snímků) a při velkém teple i jas.
     * Řídí se teplotou baterie – ta nejlépe odpovídá tomu, jak je telefon teplý v ruce.
     */
    private fun startCooling(pkg: String) {
        coolJob?.cancel()
        coolLevel = 0
        brightnessBeforeCool = null
        // Hranice podle nastavení: 1. stupeň, o 3 °C výš 2. stupeň, o 3 °C níž návrat
        val hot1 = prefs.coolStartC.toFloat()
        val hot2 = hot1 + 3f
        val coolBack = hot1 - 3f
        coolJob = scope.launch {
            var lastChange = 0L
            var chargeWarned = false
            var hotStreak = 0 // kolikrát po sobě bylo nad hranicí – krátká špička chlazení nespustí
            val bm = getSystemService(android.os.BatteryManager::class.java)
            while (isActive) {
                delay(10_000)
                val t = batteryTemp(this@GameMonitorService)
                val now = SystemClock.elapsedRealtime()

                if (!chargeWarned && bm.isCharging && t >= 40f) {
                    chargeWarned = true
                    showToast("Hraješ na nabíječce – telefon se hřeje víc. Odpoj ho nebo sniž FPS.")
                }
                hotStreak = if (t >= hot1) hotStreak + 1 else 0
                if (now - lastChange < 30_000) continue // hystereze – žádné cukání sem a tam

                val rates = BoostEngine.refreshRates(this@GameMonitorService).sorted()
                val base = baseHz.takeIf { it > 0f } ?: rates.maxOrNull() ?: continue
                val low = rates.firstOrNull { it >= 59f } ?: rates.first()
                when {
                    t >= hot2 && hotStreak >= 2 && coolLevel < 2 -> {
                        coolLevel = 2
                        runCatching { BoostEngine.setSessionHz(low) }
                        if (brightnessBeforeCool == null) {
                            brightnessBeforeCool = runCatching { BoostEngine.dimBrightness(0.85f) }.getOrNull()
                            prefs.coolBrightness = brightnessBeforeCool
                        }
                        lastChange = now
                        setStatus("🧊 Chlazení: ${low.toInt()} Hz, jas −15 % · baterie ${t.toInt()} °C")
                    }
                    t >= hot1 && hotStreak >= 3 && coolLevel < 1 -> {
                        coolLevel = 1
                        val step = rates.filter { it < base - 1f && it >= low }.maxOrNull() ?: low
                        runCatching { BoostEngine.setSessionHz(step) }
                        lastChange = now
                        setStatus("🧊 Chlazení: ${step.toInt()} Hz · baterie ${t.toInt()} °C")
                    }
                    t <= coolBack && coolLevel > 0 -> {
                        coolLevel = 0
                        if (prefs.opt(Opt.REFRESH)) {
                            runCatching { BoostEngine.setSessionHz(base) }
                        } else {
                            runCatching { BoostEngine.restoreHz(this@GameMonitorService) } // zpět na systémové Hz
                        }
                        brightnessBeforeCool?.let { runCatching { BoostEngine.setBrightness(it) } }
                        brightnessBeforeCool = null
                        prefs.coolBrightness = null
                        lastChange = now
                        setStatus("Boost aktivní: ${label(pkg)} · zchladnuto, zpět ${base.toInt()} Hz")
                    }
                }
            }
        }
    }

    /**
     * Automatické Hz: po ~30 vzorcích zjistí, kolik FPS hra reálně dává (95. percentil),
     * a nastaví nejnižší Hz, které ho pokryje. Jen jednou za hru, jen bez FPS limitu.
     */
    private suspend fun maybeAutoHz(pkg: String, samples: List<Int>) {
        if (autoHzDone || samples.size < 30) return
        autoHzDone = true
        if (!prefs.opt(Opt.AUTO_HZ) || !prefs.opt(Opt.REFRESH) || prefs.fpsCap(pkg) > 0) return
        val sorted = samples.sorted()
        val p95 = sorted[((sorted.size - 1) * 0.95).toInt()]
        val rates = BoostEngine.refreshRates(this)
        val max = rates.maxOrNull() ?: return
        val target = rates.filter { it >= p95 + 3 }.minOrNull() ?: max
        if (target < max - 1f) {
            baseHz = target
            // Když zrovna chladíme, Hz nezvyšujeme – nová hodnota se použije až po zchladnutí
            if (coolLevel == 0) {
                runCatching { BoostEngine.setSessionHz(target) }
                setStatus("Boost aktivní: ${label(pkg)} · hra ~$p95 FPS → ${target.toInt()} Hz")
            }
        }
    }

    /** Měření FPS a teplot; overlay se zobrazí jen když je zapnutý. */
    private suspend fun startMeasure(pkg: String, forceOverlay: Boolean = false) {
        val showOverlay = (forceOverlay || prefs.opt(Opt.OVERLAY)) && Settings.canDrawOverlays(this)
        if (showOverlay) {
            withContext(Dispatchers.Main) {
                if (overlay == null) overlay = OverlayController(this@GameMonitorService).also { it.show() }
            }
        }
        overlayJob?.cancel()
        overlayJob = scope.launch {
            val meter = FpsMeter(pkg)
            val stats = Stats(this@GameMonitorService)
            val recentFps = mutableListOf<Int>()
            try {
                while (isActive) {
                    val fps = runCatching { meter.sample() }.getOrNull()
                    if (fps != null) session.add(fps)
                    if (fps != null && fps > 0) recentFps += fps
                    maybeAutoHz(pkg, recentFps)
                    val text = stats.format(fps, session, SystemClock.elapsedRealtime() - sessionStart)
                    lastFps = fps
                    lastCpu = stats.cpuTemp
                    lastBat = stats.lastBatTemp
                    if (fps != null) {
                        synchronized(fpsHistory) {
                            fpsHistory.addLast(fps)
                            while (fpsHistory.size > 45) fpsHistory.removeFirst()
                        }
                    }
                    val visible = overlay != null
                    if (visible) withContext(Dispatchers.Main) { overlay?.update(text) }
                    delay(if (visible) 1_000 else 2_000) // bez overlaye stačí měřit méně často
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

    /**
     * Při skrytých oznámeních by zmizela i notifikace ZukuBoostu (a s ní zámek, snímek…).
     * S přístupem k Nerušit vytvoříme kanál, který Nerušit smí obejít – výjimka platí jen při vytvoření.
     */
    private fun channelId(): String {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return CHANNEL
        if (nm.getNotificationChannel(CHANNEL_DND) == null) {
            val ch = NotificationChannel(CHANNEL_DND, "Herní boost (přes Nerušit)", NotificationManager.IMPORTANCE_LOW)
            ch.setBypassDnd(true)
            nm.createNotificationChannel(ch)
        }
        return CHANNEL_DND
    }

    private fun notification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, channelId())
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle("ZukuBoost")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (activeGame != null && Settings.canDrawOverlays(this)) {
            val lockPi = PendingIntent.getService(
                this,
                1,
                Intent(this, GameMonitorService::class.java).setAction(ACTION_TOUCH_LOCK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val locked = touchLock?.isLocked == true
            builder.addAction(0, if (locked) "🔓 Odemknout" else "🔒 Zámek", lockPi)
        }
        if (activeGame != null) {
            fun action(act: String, code: Int) = PendingIntent.getService(
                this,
                code,
                Intent(this, GameMonitorService::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "📸 Snímek", action(ACTION_SCREENSHOT, 2))
            builder.addAction(0, if (recording) "⏹ Stop" else "⏺ Nahrát", action(ACTION_RECORD, 3))
        }
        return builder.build()
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
