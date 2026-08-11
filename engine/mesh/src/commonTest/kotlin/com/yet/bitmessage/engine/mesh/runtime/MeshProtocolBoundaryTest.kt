package com.yet.bitmessage.engine.mesh.runtime

import com.yet.bitmessage.engine.mesh.MeshEffect
import com.yet.bitmessage.engine.mesh.MeshEngine
import com.yet.bitmessage.engine.mesh.MeshEvent
import com.yet.bitmessage.engine.mesh.MeshFixtures
import com.yet.bitmessage.engine.mesh.MeshLimits
import com.yet.bitmessage.engine.mesh.PacketSource
import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.protocol.bitchat.DecodeError
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.protocol.bitchat.SigningTranscript
import com.yet.bitmessage.transport.api.LinkCapabilities
import com.yet.bitmessage.transport.api.LinkEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MeshProtocolBoundaryTest {
    @Test
    fun failedStructuralDecodeNeverEntersMeshState() = runTest {
        val executor = CapturingExecutor()
        val runtime = MeshRuntime(MeshEngine(), executor, backgroundScope, MeshLimits())
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        assertEquals(
            SubmitResult.Accepted,
            runtime.trySubmit(opened(), MeshFixtures.now),
        )
        runCurrent()
        val before = runtime.state.value

        val result = runtime.trySubmit(
            LinkEvent.PayloadReceived(MeshFixtures.linkA, Bytes.copyOf(byteArrayOf(2))),
            MeshFixtures.now,
        )
        runCurrent()

        assertEquals(SubmitResult.StructuralDecodeRejected(DecodeError.TRUNCATED), result)
        assertEquals(before, runtime.state.value)
        assertTrue(runtime.state.value?.pendingAdmissions.orEmpty().isEmpty())
        assertEquals(1L, runtime.structuralDecodeRejectionCount.value)
        assertTrue(executor.effects.isEmpty())
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun validLinkPayloadIsDecodedBeforeAdmissionReduction() = runTest {
        val executor = CapturingExecutor()
        val runtime = MeshRuntime(MeshEngine(), executor, backgroundScope, MeshLimits())
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        runtime.trySubmit(opened(), MeshFixtures.now)
        runCurrent()

        assertEquals(
            SubmitResult.Accepted,
            runtime.trySubmit(
                LinkEvent.PayloadReceived(
                    MeshFixtures.linkA,
                    MeshFixtures.broadcastPacket.rawPacket.wireBytes,
                ),
                MeshFixtures.now,
            ),
        )
        runCurrent()

        assertEquals(listOf(MeshEffect.ComputePacketDigest::class), executor.effects.map { it::class })
        assertEquals(1, runtime.state.value?.pendingAdmissions?.size)
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun rawPayloadCannotBeWrappedAsAReducerLinkEvent() {
        assertFailsWith<IllegalArgumentException> {
            MeshEvent.LinkObserved(
                generation = MeshFixtures.generation,
                observedAt = MeshFixtures.now,
                event = LinkEvent.PayloadReceived(
                    MeshFixtures.linkA,
                    MeshFixtures.broadcastPacket.rawPacket.wireBytes,
                ),
            )
        }
    }

    @Test
    fun adapterCarriesProtocolOwnedSigningEvidence() {
        val signed = MeshFixtures.signedPacket
        val accepted = assertIs<PacketIngress.Accepted>(
            MeshProtocolAdapter.decode(
                generation = MeshFixtures.generation,
                observedAt = MeshFixtures.now,
                source = PacketSource.Link(MeshFixtures.linkA),
                bytes = signed.rawPacket.wireBytes,
            ),
        )
        val expected = assertIs<DecodeResult.Success<Bytes>>(SigningTranscript.build(signed)).value

        assertEquals(signed, accepted.event.packet)
        assertEquals(expected, accepted.event.signingTranscript)
    }

    private class CapturingExecutor : MeshEffectExecutor {
        val effects = mutableListOf<MeshEffect>()

        override suspend fun execute(effect: MeshEffect): MeshEvent? {
            effects += effect
            return null
        }
    }

    private fun opened(): LinkEvent.Opened =
        LinkEvent.Opened(
            linkId = MeshFixtures.linkA,
            capabilities = LinkCapabilities(
                maxWriteBytes = 512,
                writeReady = true,
            ),
        )
}
