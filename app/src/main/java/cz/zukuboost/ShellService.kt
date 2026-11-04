package cz.zukuboost

import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Běží v samostatném procesu spuštěném Shizuku serverem
 * (uid 2000 = shell přes ADB, nebo 0 při rootu).
 */
class ShellService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(cmd: String?): String {
        if (cmd.isNullOrBlank()) return ""
        return try {
            val process = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            // Výstup čteme v samostatném vlákně, ať zaseknutý příkaz nezablokuje celou appku
            val output = StringBuilder()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(8192)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            synchronized(output) { output.append(buf, 0, n) }
                        }
                    }
                }
            }
            reader.start()
            val finished = process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(2_000)
            val text = synchronized(output) { output.toString() }
            if (finished) text else "$text\nERROR: command exceeded ${TIMEOUT_S}s and was terminated"
        } catch (t: Throwable) {
            "ERROR: ${t.message}"
        }
    }

    private companion object {
        // Kompilace velké hry umí trvat i přes minutu
        const val TIMEOUT_S = 150L
    }
}
