package com.siroha.flashtool.core

import java.io.File

/**
 * Turns qdl's output lines into an overall progress bar. qdl has no
 * percentage of its own, but it prints `flashed "<label>" successfully ...`
 * after each <program> entry, and the rawprogram XML tells us how big every
 * entry is - so the bar advances by each finished partition's share of the
 * total bytes. It is capped below 100 % until the run actually ends, since
 * patches and the bootable flag still follow the last partition.
 */
class QdlProgressTracker(entries: List<RawProgramPartition>) {
    private val programs = entries.filter { it.filename.isNotBlank() }
    private val weightPerOccurrence: Map<String, Double>
    private val averageWeight: Double
    private val totalWeight: Double
    private var doneWeight = 0.0
    private var doneCount = 0

    init {
        val raw = programs.map { it.label to weightOf(it) }
        val sums = HashMap<String, Double>()
        val counts = HashMap<String, Int>()
        for ((label, weight) in raw) {
            sums[label] = (sums[label] ?: 0.0) + weight
            counts[label] = (counts[label] ?: 0) + 1
        }
        weightPerOccurrence = sums.mapValues { (label, sum) -> sum / (counts[label] ?: 1) }
        totalWeight = raw.sumOf { it.second }.coerceAtLeast(1.0)
        averageWeight = if (programs.isEmpty()) 1.0 else totalWeight / programs.size
    }

    val isUseful: Boolean get() = programs.isNotEmpty()

    fun onLine(line: String) {
        val text = line.trim()
        if (text.isEmpty()) return
        val flashed = FLASHED.find(text)
        when {
            flashed != null -> {
                val label = flashed.groupValues[1]
                doneWeight += weightPerOccurrence[label] ?: averageWeight
                doneCount++
                val fraction = (doneWeight / totalWeight).toFloat().coerceAtMost(0.98f)
                OperationStatus.reportFraction(fraction, "Flashed $label  ($doneCount/${programs.size})")
            }
            text.contains("waiting for", ignoreCase = true) ->
                OperationStatus.setStage("Waiting for the EDL (9008) device...")
            text.contains("applied", ignoreCase = true) ->
                OperationStatus.setStage("Applying patches...")
            text.contains("bootable", ignoreCase = true) ->
                OperationStatus.setStage("Setting the bootable partition...")
        }
    }

    companion object {
        private val FLASHED = Regex("""flashed\s+"([^"]+)"""", RegexOption.IGNORE_CASE)

        private fun weightOf(p: RawProgramPartition): Double {
            fun attr(name: String): Long? =
                p.rawAttributes.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()?.toLongOrNull()
            val sectors = attr("num_partition_sectors") ?: return 1.0
            val sectorSize = attr("SECTOR_SIZE_IN_BYTES") ?: 512L
            return (sectors * sectorSize).toDouble().coerceAtLeast(1.0)
        }

        /** Builds a tracker from the rawprogram files that will actually be passed to qdl. */
        fun fromFiles(paths: List<String>): QdlProgressTracker =
            QdlProgressTracker(paths.flatMap { runCatching { RawProgramXml.parsePartitions(File(it)) }.getOrDefault(emptyList()) })
    }
}
