package io.github.ravenor907.ravendisk.core.job

/**
 * Shared vocabulary for long jobs (writing an image, testing a drive), used the same way by
 * Raven Disk Utility and Handheld Forge.
 */
enum class Stage { LOOKUP, DOWNLOAD, VERIFY, WRITE, CHECK }

/** (stage, done, total). Total 0 means the stage can't measure itself. */
typealias StageFn = (Stage, Long, Long) -> Unit
typealias LogFn = (String) -> Unit

/** The person stopped the job. */
class CancelledException : Exception("Stopped")

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
