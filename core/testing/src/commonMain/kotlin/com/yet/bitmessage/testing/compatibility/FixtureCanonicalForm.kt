package com.yet.bitmessage.testing.compatibility

object FixtureCanonicalForm {
    fun encode(
        formatVersion: Int,
        profile: String,
        fixture: CompatibilityFixture,
    ): String = buildString {
        part("formatVersion", formatVersion.toString())
        part("profile", profile)
        part("id", fixture.id)
        part("category", fixture.category.name)
        part("direction", fixture.direction.name)
        part("sourceRepository", fixture.provenance.sourceRepository)
        part("sourceCommitSha", fixture.provenance.sourceCommitSha)
        part("sourcePath", fixture.provenance.sourcePath)
        part("sourceTest", fixture.provenance.sourceTest)
        part("producer", fixture.provenance.producer.name)
        fixture.provenance.acceptedBy
            .map(UpstreamClient::name)
            .sorted()
            .forEachIndexed { index, value -> part("acceptedBy[$index]", value) }
        part("wireBytesHex", fixture.wireBytesHex)
        part("outcomeStatus", fixture.expectedOutcome.status.name)
        part("rejectCode", fixture.expectedOutcome.rejectCode?.name)
        fixture.semanticFields
            .sortedBy(SemanticField::id)
            .forEachIndexed { index, field ->
                part("semantic[$index].id", field.id)
                part("semantic[$index].value", field.value)
            }
        part("signingTranscriptHex", fixture.signingTranscriptHex)
        part("signingTranscriptSha256", fixture.signingTranscriptSha256)
        fixture.securityLimits
            .sortedBy(SecurityLimit::id)
            .forEachIndexed { index, limit ->
                part("limit[$index].id", limit.id)
                part("limit[$index].value", limit.value)
            }
        part("decisionState", fixture.decisionState.name)
        part("blockedReason", fixture.blockedReason)
        part("historicalRepository", fixture.historicalProvenance?.sourceRepository)
        part("historicalCommitSha", fixture.historicalProvenance?.sourceCommitSha)
        part("historicalPath", fixture.historicalProvenance?.sourcePath)
        part("historicalTest", fixture.historicalProvenance?.sourceTest)
        part("historicalPromotion", fixture.historicalProvenance?.promotionDecision?.name)
    }

    private fun StringBuilder.part(key: String, value: String?) {
        append(key.length).append(':').append(key).append('=')
        if (value == null) {
            append('N')
        } else {
            append('V').append(value.length).append(':').append(value)
        }
        append('\n')
    }
}
