package com.lepao.companion.core

import android.content.Context
import java.io.File
import java.util.UUID

/** Encrypted per-run session directory and the single in-process cache used by the UI. */
object RunStore {

    private lateinit var appContext: Context
    private lateinit var secure: SecureStore

    @Volatile
    private var cached: RunState? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        secure = SecureStore(appContext)
    }

    fun runsDir(): File = File(appContext.filesDir, "runs").apply { mkdirs() }

    fun newId(): String {
        val suffix = UUID.randomUUID().toString().take(6)
        val stamp = RunClock.local(RunClock.nowEpoch())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return "run-$stamp-$suffix"
    }

    fun runDir(id: String): File {
        val safe = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return File(runsDir(), safe)
    }

    fun stateFile(id: String): File = File(runDir(id), "state.enc.json")

    fun auditFile(id: String): File = File(runDir(id), "audit.log")

    fun artifactFile(id: String, name: String): File = File(runDir(id), name)

    fun save(state: RunState) {
        state.touch()
        secure.writeJson(stateFile(state.id), state.toJson())
        cached = state
    }

    fun load(id: String): RunState? {
        cached?.let { if (it.id == id) return it }
        val json = secure.readJson(stateFile(id)) ?: return null
        return runCatching { RunState.fromJson(json) }.getOrNull()
    }

    fun latest(): RunState? {
        cached?.let { return it }
        val newest = list(limit = 1).firstOrNull()
        cached = newest
        return newest
    }

    fun list(limit: Int = 30): List<RunState> {
        val directories = runsDir().listFiles()?.filter { it.isDirectory } ?: return emptyList()
        val states = ArrayList<RunState>()
        for (directory in directories) {
            val file = File(directory, "state.enc.json")
            if (!file.isFile) continue
            val json = secure.readJson(file) ?: continue
            runCatching { RunState.fromJson(json) }.getOrNull()?.let(states::add)
        }
        return states.sortedByDescending { it.updatedAt }.take(limit)
    }

    fun blockingRun(): RunState? {
        cached?.let { if (it.stage.blocksNewRun) return it }
        return list().firstOrNull { it.stage.blocksNewRun }
    }

    fun invalidate(id: String? = null) {
        if (id == null || cached?.id == id) cached = null
    }
}
