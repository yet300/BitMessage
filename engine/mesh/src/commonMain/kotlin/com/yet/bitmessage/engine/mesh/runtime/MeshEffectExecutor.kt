package com.yet.bitmessage.engine.mesh.runtime

import com.yet.bitmessage.engine.mesh.MeshEffect
import com.yet.bitmessage.engine.mesh.MeshEvent
import com.yet.bitmessage.engine.mesh.MeshFailureCode
import com.yet.bitmessage.foundation.MonotonicTime
import kotlinx.coroutines.CancellationException

fun interface MeshEffectExecutor {
    suspend fun execute(effect: MeshEffect): MeshEvent?
}

internal suspend fun executeEffect(
    executor: MeshEffectExecutor,
    effect: MeshEffect,
    observedAt: MonotonicTime,
): MeshEvent? =
    try {
        executor.execute(effect)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        MeshEvent.EffectFailed(
            correlationId = effect.correlationId,
            generation = effect.generation,
            observedAt = observedAt,
            code = MeshFailureCode.EFFECT_EXECUTION_FAILED,
        )
    }
