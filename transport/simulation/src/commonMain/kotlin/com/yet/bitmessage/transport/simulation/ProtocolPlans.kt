package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.engine.mesh.MeshFailureCode
import com.yet.bitmessage.engine.mesh.MeshResult
import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.EntropyGenerated
import com.yet.bitmessage.foundation.EntropyRequest
import com.yet.bitmessage.foundation.EntropySource
import com.yet.bitmessage.foundation.Generation
import com.yet.bitmessage.protocol.bitchat.PacketIdentityInput
import kotlin.random.Random
import kotlin.time.Duration

data class ProtocolEntropyRecord(
    val correlationId: CorrelationId,
    val byteCount: Int,
    val bytes: Bytes,
)

/** Node-local protocol randomness; scenario compilation has a separate random stream. */
class NodeProtocolEntropy(seed: Int) : EntropySource {
    private val random = Random(seed)
    private val mutableTranscript = mutableListOf<ProtocolEntropyRecord>()

    val transcript: List<ProtocolEntropyRecord>
        get() = mutableTranscript.toList()

    override fun generate(request: EntropyRequest): EntropyGenerated {
        val generated = ByteArray(request.byteCount)
        random.nextBytes(generated)
        val owned = Bytes.copyOf(generated)
        mutableTranscript += ProtocolEntropyRecord(request.correlationId, request.byteCount, owned)
        return EntropyGenerated(request.correlationId, owned)
    }
}

sealed interface PlannedOutcome<out T> {
    val result: MeshResult<T>

    data class Immediate<T>(override val result: MeshResult<T>) : PlannedOutcome<T>

    data class Delayed<T>(
        val delay: Duration,
        override val result: MeshResult<T>,
    ) : PlannedOutcome<T> {
        init {
            require(delay.isFinite() && delay >= Duration.ZERO) {
                "Planned delay must be finite and nonnegative."
            }
        }
    }
}

/** Only a named request ordinal can permit a signature; all other requests fail closed. */
class VerificationPlan(rules: List<Pair<Long, PlannedOutcome<Boolean>>>) {
    private val byOrdinal = checkedRules(rules)

    constructor(rules: Map<Long, PlannedOutcome<Boolean>>) : this(rules.toList())

    internal fun outcome(requestOrdinal: Long): PlannedOutcome<Boolean> {
        require(requestOrdinal > 0) { "Verification request ordinal must be positive." }
        return byOrdinal[requestOrdinal] ?: PlannedOutcome.Immediate(MeshResult.Success(false))
    }

    companion object {
        val EMPTY = VerificationPlan(emptyList())

        fun valid(requestOrdinal: Long): VerificationPlan =
            VerificationPlan(listOf(requestOrdinal to PlannedOutcome.Immediate(MeshResult.Success(true))))
    }
}

/** The normal path uses real SHA-256; this map contains explicit exceptional outcomes only. */
class DigestPlan private constructor(
    rules: List<Pair<Long, PlannedOutcome<Bytes>>>,
    private val eventOrigins: Map<Long, DigestEventOrigin>,
) {
    private val byOrdinal = checkedRules(rules)

    constructor(rules: List<Pair<Long, PlannedOutcome<Bytes>>>) : this(rules, emptyMap())

    constructor(rules: Map<Long, PlannedOutcome<Bytes>>) : this(rules.toList())

    internal fun outcome(requestOrdinal: Long, input: PacketIdentityInput): PlannedOutcome<Bytes> {
        require(requestOrdinal > 0) { "Digest request ordinal must be positive." }
        return byOrdinal[requestOrdinal] ?:
            PlannedOutcome.Immediate(MeshResult.Success(SimulationSha256.digest(input.canonicalBytes)))
    }

    internal fun eventCorrelationId(requestOrdinal: Long, original: CorrelationId): CorrelationId =
        eventOrigins[requestOrdinal]?.correlationId ?: original

    internal fun eventGeneration(requestOrdinal: Long, original: Generation): Generation =
        eventOrigins[requestOrdinal]?.generation ?: original

    companion object {
        val EMPTY = DigestPlan(emptyList())

        fun failure(requestOrdinal: Long): DigestPlan =
            DigestPlan(listOf(requestOrdinal to PlannedOutcome.Immediate(MeshResult.Failure(MeshFailureCode.DIGEST_FAILED))))

        fun collision(requestOrdinal: Long, digest: Bytes): DigestPlan {
            require(digest.size == 32) { "A forced SHA-256 digest must contain 32 bytes." }
            return DigestPlan(listOf(requestOrdinal to PlannedOutcome.Immediate(MeshResult.Success(digest))))
        }

        fun staleCorrelation(requestOrdinal: Long, correlationId: CorrelationId): DigestPlan {
            require(requestOrdinal > 0) { "Digest request ordinal must be positive." }
            return DigestPlan(emptyList(), mapOf(requestOrdinal to DigestEventOrigin(correlationId = correlationId)))
        }

        fun staleGeneration(requestOrdinal: Long, generation: Generation): DigestPlan {
            require(requestOrdinal > 0) { "Digest request ordinal must be positive." }
            return DigestPlan(emptyList(), mapOf(requestOrdinal to DigestEventOrigin(generation = generation)))
        }
    }
}

private data class DigestEventOrigin(
    val correlationId: CorrelationId? = null,
    val generation: Generation? = null,
)

private fun <T> checkedRules(rules: List<Pair<Long, PlannedOutcome<T>>>): Map<Long, PlannedOutcome<T>> {
    require(rules.all { (ordinal, _) -> ordinal > 0 }) { "Plan request ordinals must be positive." }
    val indexed = rules.toMap()
    require(indexed.size == rules.size) { "Plan request ordinals must be unique." }
    return indexed
}
