package com.yet.bitmessage.engine.mesh.runtime

import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.Generation
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.foundation.TimerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class MeshTimerKey(
    val correlationId: CorrelationId,
    val timerId: TimerId,
)

data class MeshTimerRequest(
    val key: MeshTimerKey,
    val generation: Generation,
    val observedAt: MonotonicTime,
    val deadline: MonotonicTime,
    val onElapsed: suspend () -> Unit,
)

interface MeshTimerDriver {
    suspend fun schedule(request: MeshTimerRequest)

    suspend fun cancel(key: MeshTimerKey)

    suspend fun cancelAll()
}

fun interface MeshTimerDriverFactory {
    fun create(scope: CoroutineScope): MeshTimerDriver
}

class CoroutineMeshTimerDriver private constructor(
    private val scope: CoroutineScope,
) : MeshTimerDriver {
    private val mutex = Mutex()
    private val jobs = mutableMapOf<MeshTimerKey, Job>()
    private var closed = false

    override suspend fun schedule(request: MeshTimerRequest) {
        val registration = mutex.withLock {
            if (closed) return
            lateinit var timerJob: Job
            timerJob = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    delay(request.deadline.elapsedSince(request.observedAt))
                    request.onElapsed()
                } finally {
                    withContext(NonCancellable) {
                        mutex.withLock {
                            if (jobs[request.key] === timerJob) jobs.remove(request.key)
                        }
                    }
                }
            }
            jobs.put(request.key, timerJob) to timerJob
        }
        registration.first?.cancel()
        registration.second.start()
    }

    override suspend fun cancel(key: MeshTimerKey) {
        mutex.withLock { jobs.remove(key) }?.cancel()
    }

    override suspend fun cancelAll() {
        val registered = mutex.withLock {
            closed = true
            jobs.values.toList().also { jobs.clear() }
        }
        registered.forEach(Job::cancel)
    }

    companion object Factory : MeshTimerDriverFactory {
        override fun create(scope: CoroutineScope): MeshTimerDriver = CoroutineMeshTimerDriver(scope)
    }
}
