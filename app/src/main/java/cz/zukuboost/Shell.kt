package cz.zukuboost

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/** Spouštění shell příkazů s právy Shizuku. */
object Shell {

    enum class State { NOT_RUNNING, NO_PERMISSION, READY }

    @Volatile private var service: IShellService? = null
    @Volatile private var waiter = CompletableDeferred<IShellService>()
    @Volatile private var binding = false
    private val connectLock = Mutex()

    private val args by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            if (binder != null && binder.pingBinder()) {
                val s = IShellService.Stub.asInterface(binder)
                service = s
                waiter.complete(s)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            reset()
        }
    }

    private fun reset() {
        service = null
        binding = false
        if (waiter.isCompleted) waiter = CompletableDeferred()
    }

    fun state(): State = try {
        when {
            !Shizuku.pingBinder() -> State.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NO_PERMISSION
            else -> State.READY
        }
    } catch (t: Throwable) {
        State.NOT_RUNNING
    }

    /** Souběžná volání (měření, boost, panel) se připojují jen jednou – žádné dvojí bindování. */
    private suspend fun connect(): IShellService {
        service?.let { current -> if (current.asBinder().pingBinder()) return current }
        return connectLock.withLock { connectLocked() }
    }

    private suspend fun connectLocked(): IShellService {
        service?.let { current ->
            if (current.asBinder().pingBinder()) return current
            reset()
        }
        // Binder Shizuku přichází asynchronně po startu procesu, chvíli počkáme
        var tries = 0
        while (state() != State.READY) {
            if (++tries > 20) throw IllegalStateException("Shizuku isn't running or the app has no permission")
            delay(250)
        }
        val pending = waiter
        if (!binding) {
            binding = true
            withContext(Dispatchers.Main) { Shizuku.bindUserService(args, connection) }
        }
        return try {
            withTimeout(10_000) { pending.await() }
        } catch (e: TimeoutCancellationException) {
            binding = false
            throw IllegalStateException("Couldn't connect to the Shizuku service")
        }
    }

    /** Spustí příkaz (lze řetězit přes ;) a vrátí stdout + stderr. Při chybě vyhodí výjimku. */
    /**
     * Spustí příkaz. log = false pro časté měřicí dotazy (FPS, senzory), aby nezahltily log příkazů.
     */
    suspend fun exec(cmd: String, log: Boolean = true): String = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        try {
            val s = connect()
            val out = try {
                s.exec(cmd) ?: ""
            } catch (e: RemoteException) {
                reset()
                throw IllegalStateException("The Shizuku connection dropped")
            }
            if (log) CmdLog.add(cmd, out, (System.nanoTime() - t0) / 1_000_000, error = null)
            out
        } catch (e: IllegalStateException) {
            if (log) CmdLog.add(cmd, "", (System.nanoTime() - t0) / 1_000_000, error = e.message ?: "error")
            throw e
        }
    }
}

/** Záznam příkazu pro obrazovku „Command log“ a hlášení pádů. */
data class LogEntry(val time: Long, val cmd: String, val out: String, val ms: Long, val error: String?) {
    /** Systém příkaz odmítl nebo neznal – nejčastější důvod, proč nějaký tweak nefunguje. */
    val warning: Boolean
        get() = error != null || listOf("Exception", "Error:", "denied", "not allowed", "Unknown command", "not found")
            .any { out.contains(it, ignoreCase = true) }
}

object CmdLog {
    private const val MAX = 300
    private val items = ArrayDeque<LogEntry>()

    @Synchronized
    fun add(cmd: String, out: String, ms: Long, error: String?) {
        items.addLast(LogEntry(System.currentTimeMillis(), cmd, out.trim().take(600), ms, error))
        while (items.size > MAX) items.removeFirst()
    }

    /** Nejnovější první. */
    @Synchronized
    fun all(): List<LogEntry> = items.toList().asReversed()

    @Synchronized
    fun clear() = items.clear()

    /** Text pro schránku / hlášení pádu. */
    fun text(limit: Int = MAX): String {
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        return all().take(limit).joinToString("\n\n") { e ->
            buildString {
                append("[").append(fmt.format(java.util.Date(e.time))).append("] ")
                append(if (e.warning) "⚠ " else "✓ ").append(e.ms).append(" ms\n$ ").append(e.cmd)
                if (e.error != null) append("\n! ").append(e.error)
                if (e.out.isNotBlank()) append("\n").append(e.out)
            }
        }
    }
}
