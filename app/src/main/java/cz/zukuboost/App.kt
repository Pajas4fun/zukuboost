package cz.zukuboost

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File

/** Zachytí pád appky a uloží hlášení (s posledními příkazy), které se ukáže při dalším spuštění. */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(filesDir, CRASH_FILE).writeText(
                    buildString {
                        append("ZukuBoost ").append(BuildConfig.VERSION_NAME)
                        append(" · Android ").append(Build.VERSION.RELEASE)
                        append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                        append("Thread: ").append(thread.name).append("\n\n")
                        append(Log.getStackTraceString(error))
                        append("\n--- last commands ---\n")
                        append(CmdLog.text(25))
                    }
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CRASH_FILE = "crash.txt"
    }
}
