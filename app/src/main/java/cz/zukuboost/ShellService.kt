package cz.zukuboost

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
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output
        } catch (t: Throwable) {
            "ERROR: ${t.message}"
        }
    }
}
