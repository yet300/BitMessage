package com.yet.bitmessage.transport.api

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.Generation
import com.yet.bitmessage.model.LinkId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LinkContractTest {
    @Test
    fun capabilitiesRequireAPositiveWriteLimitAndCarryReadiness() {
        assertFailsWith<IllegalArgumentException> {
            LinkCapabilities(maxWriteBytes = 0, writeReady = false)
        }

        assertEquals(
            LinkCapabilities(maxWriteBytes = 512, writeReady = true),
            LinkCapabilities(maxWriteBytes = 512, writeReady = true),
        )
    }

    @Test
    fun observationsKeepDistinctLinkIdentityAndImmutablePayloadBytes() {
        val source = byteArrayOf(1, 2, 3)
        val linkA = LinkId.of("link-a")
        val linkB = LinkId.of("link-b")
        val opened = LinkEvent.Opened(linkA, LinkCapabilities(256, writeReady = false))
        val ready = LinkEvent.ReadinessChanged(linkA, LinkCapabilities(256, writeReady = true))
        val payload = LinkEvent.PayloadReceived(linkB, Bytes.copyOf(source))
        val closed = LinkEvent.Closed(linkA, LinkCloseReason.REMOTE_CLOSED)
        source[0] = 9

        assertEquals(linkA, opened.linkId)
        assertEquals(true, ready.capabilities.writeReady)
        assertEquals(1u.toUByte(), payload.bytes[0])
        assertEquals(linkB, payload.linkId)
        assertEquals(LinkCloseReason.REMOTE_CLOSED, closed.reason)
    }

    @Test
    fun writeAndCloseCommandsCarryCorrelationAndGeneration() {
        val source = byteArrayOf(1, 2, 3)
        val linkId = LinkId.of("link-a")
        val correlationId = CorrelationId.of("write-1")
        val generation = Generation(4)
        val write = LinkCommand.Write(
            linkId = linkId,
            correlationId = correlationId,
            generation = generation,
            bytes = Bytes.copyOf(source),
        )
        val close = LinkCommand.Close(
            linkId = linkId,
            correlationId = CorrelationId.of("close-1"),
            generation = generation,
            reason = LinkCloseReason.LOCAL_REQUEST,
        )
        source[0] = 9

        assertEquals(1u.toUByte(), write.bytes[0])
        assertEquals(linkId, write.linkId)
        assertEquals(correlationId, write.correlationId)
        assertEquals(generation, write.generation)
        assertEquals(LinkCloseReason.LOCAL_REQUEST, close.reason)
    }

    @Test
    fun everyLinkResultRepeatsTheRequestIdentity() {
        val linkId = LinkId.of("link-a")
        val correlationId = CorrelationId.of("write-1")
        val generation = Generation(7)
        val results = listOf<LinkResult>(
            LinkResult.Written(linkId, correlationId, generation),
            LinkResult.Backpressured(linkId, correlationId, generation),
            LinkResult.PayloadTooLarge(linkId, correlationId, generation, maximumBytes = 256),
            LinkResult.Disconnected(linkId, correlationId, generation),
            LinkResult.Unsupported(linkId, correlationId, generation),
            LinkResult.Failed(linkId, correlationId, generation, LinkFailureCode.TRANSIENT),
        )

        results.forEach { result ->
            assertEquals(linkId, result.linkId)
            assertEquals(correlationId, result.correlationId)
            assertEquals(generation, result.generation)
        }
        assertEquals(256, assertIs<LinkResult.PayloadTooLarge>(results[2]).maximumBytes)
        assertEquals(LinkFailureCode.TRANSIENT, assertIs<LinkResult.Failed>(results[5]).code)
    }

    @Test
    fun closeAndFailureReasonsAreClosedTypedDomains() {
        assertEquals(
            listOf(
                LinkCloseReason.LOCAL_REQUEST,
                LinkCloseReason.REMOTE_CLOSED,
                LinkCloseReason.TRANSPORT_FAILED,
            ),
            LinkCloseReason.entries,
        )
        assertEquals(
            listOf(
                LinkFailureCode.TRANSIENT,
                LinkFailureCode.PERMANENT,
                LinkFailureCode.CANCELLED,
            ),
            LinkFailureCode.entries,
        )
    }
}
