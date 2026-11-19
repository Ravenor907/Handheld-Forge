package io.github.ravenor907.handheldforge

import android.content.Context
import io.github.ravenor907.handheldforge.core.ImageStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

/**
 * The phone side of [ImageStore]: what the Storage line on the main screen shows, and the
 * clean-ups the app and the service run. Disk work happens on one background thread, in order.
 */
object Storage {
    data class State(
        val entries: List<ImageStore.Entry> = emptyList(),
        /** Systems whose images are kept after a drive is made. */
        val kept: Set<String> = emptySet(),
        /** The latest clean-up's one-line note, or "" when nothing was freed. */
        val note: String = "",
        val loaded: Boolean = false,
    ) {
        val bytes: Long get() = entries.sumOf { it.bytes }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "storage").apply { isDaemon = true } }

    fun store(context: Context) = ImageStore(ForgeService.isoDir(context), ImageStore.ownerFrom(ForgeService.catalog(context)))

    private fun publish(context: Context, note: String? = null) {
        val store = store(context)
        _state.value = State(store.entries(), store.kept(), note ?: _state.value.note, loaded = true)
    }

    /** On start: leftovers, images unused for two weeks, and the app's cache. Skipped while a job runs. */
    fun cleanOnStart(context: Context) = io.execute {
        val app = context.applicationContext
        try {
            if (Jobs.state.value is JobState.Running) return@execute publish(app)
            val s = store(app)
            s.cleanLeftovers()
            val unused = s.removeUnused()
            app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            publish(app, if (unused > 0) ImageStore.freedNote(unused, "images not used for ${ImageStore.UNUSED_DAYS} days") else null)
        } catch (e: Exception) {
            runCatching { publish(app) }
        }
    }

    fun refresh(context: Context) = io.execute { runCatching { publish(context.applicationContext) } }

    fun setKeep(context: Context, owner: String, on: Boolean) {
        // Shown at once; the disk catches up on the storage thread.
        _state.value = _state.value.let { s ->
            s.copy(kept = if (on) s.kept + owner else s.kept - owner, entries = s.entries.map { if (it.owner == owner) it.copy(keep = on) else it })
        }
        keepOnDisk(context, owner, on)
    }

    private fun keepOnDisk(context: Context, owner: String, on: Boolean) = io.execute {
        val app = context.applicationContext
        runCatching {
            store(app).setKeep(owner, on)
            publish(app)
        }
    }

    fun removeAll(context: Context) = io.execute {
        val app = context.applicationContext
        runCatching {
            if (Jobs.state.value is JobState.Running) return@execute
            val freed = store(app).removeAll()
            publish(app, if (freed > 0) ImageStore.freedNote(freed, "the downloaded images") else "")
        }
    }

    /** Debug builds' screenshots: a made-up state, nothing on disk. */
    fun demo(state: State) {
        _state.value = state
    }

    /** From the service, after a job: show what it freed on the main screen. */
    fun noteFromJob(context: Context, note: String) = io.execute { runCatching { publish(context.applicationContext, note) } }
}
