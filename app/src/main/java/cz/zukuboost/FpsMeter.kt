package cz.zukuboost

import kotlin.math.roundToInt

/**
 * Měří FPS hry. Primárně z časů prezentace snímků (SurfaceFlinger --latency),
 * když to na daném systému nejde, přepne na SurfaceFlinger timestats.
 */
class FpsMeter(private val pkg: String) {

    private var layer: String? = null
    private var misses = 0
    private var latencyWorked = false
    private var useTimestats = false
    private var timestatsOn = false
    private val ws = Regex("\\s+")
    private val avgRe = Regex("averageFPS = ([0-9.]+)")

    suspend fun sample(): Int? = if (useTimestats) sampleTimestats() else sampleLatency()

    private suspend fun sampleLatency(): Int? {
        val l = layer ?: findLayer()?.also { layer = it }
        if (l == null) {
            if (++misses >= 3 && !latencyWorked) useTimestats = true
            return null
        }
        val quoted = l.replace("'", "'\\''")
        val out = Shell.exec("dumpsys SurfaceFlinger --latency '$quoted'", log = false)
        val now = System.nanoTime()

        // 1. řádek je perioda displeje, dál: desiredPresent actualPresent frameReady
        val presents = out.lineSequence().drop(1).mapNotNull { line ->
            val parts = line.trim().split(ws)
            if (parts.size >= 3) parts[1].toLongOrNull() else null
        }.filter { it > 0L && it != Long.MAX_VALUE }.toList()

        val latest = presents.maxOrNull()
        val ref = if (latest != null && now - latest in 0L..500_000_000L) latest else now
        val fps = presents.count { ref - it in 0L until 1_000_000_000L }

        if (fps > 0) {
            latencyWorked = true
            misses = 0
        } else {
            misses++
            // Vrstvu hledáme znovu až po delší době – výpis všech vrstev (--list) je pro SurfaceFlinger náročnější
            // a statická scéna (pauza, menu) má legitimně 0 nových snímků
            if (misses % 10 == 0) layer = null
            if (misses >= 6 && !latencyWorked) useTimestats = true
        }
        return fps
    }

    private suspend fun findLayer(): String? {
        val layers = Shell.exec("dumpsys SurfaceFlinger --list", log = false)
            .lines().map { it.trim() }
            .filter { it.contains(pkg) && !it.contains("Background") }
        return layers.lastOrNull { it.startsWith("SurfaceView") && it.contains("BLAST") }
            ?: layers.lastOrNull { it.startsWith("SurfaceView") }
            ?: layers.lastOrNull()
    }

    private suspend fun sampleTimestats(): Int? {
        if (!timestatsOn) {
            Shell.exec("dumpsys SurfaceFlinger --timestats -enable -clear")
            timestatsOn = true
            return null
        }
        val out = Shell.exec("dumpsys SurfaceFlinger --timestats -dump -clear", log = false)
        return out.split("layerName = ")
            .filter { it.contains(pkg) }
            .mapNotNull { avgRe.find(it)?.groupValues?.get(1)?.toFloatOrNull() }
            .maxOrNull()
            ?.roundToInt()
    }

    suspend fun close() {
        if (timestatsOn) {
            Shell.exec("dumpsys SurfaceFlinger --timestats -disable")
            timestatsOn = false
        }
    }
}
