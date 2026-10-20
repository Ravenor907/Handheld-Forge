package io.github.ravenor907.ravendisk.core.model

import io.github.ravenor907.ravendisk.core.fs.FsInfo
import io.github.ravenor907.ravendisk.core.health.HealthReport
import io.github.ravenor907.ravendisk.core.table.Partition
import io.github.ravenor907.ravendisk.core.table.TableRead

/**
 * A value together with how we know it. The UI shows detected facts plainly, marks inferred ones,
 * and shows the reason for anything unavailable instead of a made-up value.
 */
sealed class Fact<out T> {
    data class Detected<T>(val value: T, val source: String) : Fact<T>()
    data class Inferred<T>(val value: T, val reason: String) : Fact<T>()
    data class Unavailable(val reason: String) : Fact<Nothing>()

    val valueOrNull: T? get() = when (this) {
        is Detected -> value
        is Inferred -> value
        is Unavailable -> null
    }

    fun <R> map(f: (T) -> R): Fact<R> = when (this) {
        is Detected -> Detected(f(value), source)
        is Inferred -> Inferred(f(value), reason)
        is Unavailable -> this
    }
}

fun <T> detected(v: T, source: String) = Fact.Detected(v, source)
fun <T> inferred(v: T, reason: String) = Fact.Inferred(v, reason)
fun unavailable(reason: String) = Fact.Unavailable(reason)

enum class Connection(val label: String) {
    USB("USB"),
    USB_CARD_READER("USB card reader"),
    SD_CARD("SD card slot"),
    INTERNAL("Internal"),
    SIMULATED("Simulated"),
    UNKNOWN("Unknown"),
}

enum class MediaKind(val label: String) {
    FLASH_DRIVE("Flash drive"),
    SSD("SSD"),
    HDD("Hard drive"),
    NVME("NVMe SSD"),
    SD_CARD("SD card"),
    INTERNAL_STORAGE("Internal storage"),
    UNKNOWN("Drive"),
}

/** A physical device (or LUN) as discovered by a backend. */
data class DiskInfo(
    /** Stable within a session; validated before any use in a command. */
    val id: String,
    val backend: String,
    val model: String,
    val vendor: Fact<String>,
    val serial: Fact<String>,
    val connection: Connection,
    val media: Fact<MediaKind>,
    val capacityBytes: Fact<Long>,
    val logicalSector: Fact<Int>,
    val physicalSector: Fact<Int>,
    val readOnly: Fact<Boolean>,
    val removable: Fact<Boolean>,
    /** e.g. "USB 3 speed", "Bulk-Only Transport, LUN 0". */
    val transport: Fact<String>,
    val discard: Fact<Boolean>,
    val table: TableRead?,
    val volumes: List<VolumeInfo>,
    val health: HealthReport?,
    /** Mounted by Android (and where), as far as we can tell. */
    val mounted: Fact<List<String>>,
    /** Why this device can't be accessed further (permission, UAS-only, no medium), or null. */
    val blocked: String? = null,
)

/** One partition plus what's inside it. */
data class VolumeInfo(
    val partition: Partition,
    val fs: FsInfo?,
    val mountPoint: String? = null,
)
