package com.dedtsss.catawg.core.configurator

import com.dedtsss.catawg.core.diagnostics.DiagnosticCategory
import com.dedtsss.catawg.core.diagnostics.DiagnosticEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AwgConfiguratorTest {
    private val validAwg2 =
        """
        [Interface]
        PrivateKey = client-private-key
        Address = 10.7.0.2/32, fd00::2/128
        DNS = 1.1.1.1, example.internal
        ListenPort = 51820
        MTU = 1280
        Jc = 4
        Jmin = 100
        Jmax = 200
        S1 = 0
        S2 = 0
        S3 = 0
        S4 = 0
        H1 = 1
        H2 = 2
        H3 = 3
        H4 = 4

        [Peer]
        PublicKey = server-public-key
        PresharedKey = peer-secret
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = vpn.example.com:51820
        PersistentKeepalive = 25
        """
            .trimIndent()

    @Test
    fun `parser serializes deterministic portable AWG2 document`() {
        val parser = AwgConfigParser()
        val parsed = parser.parse(validAwg2)
        val reparsed = parser.parse(parser.serialize(parsed))

        assertEquals(parsed, reparsed)
        assertEquals("4", parsed.interfaceValues.getValue("Jc"))
        assertEquals("vpn.example.com:51820", parsed.peers.single().getValue("Endpoint"))
        val validation = AwgConfigValidator().validate(parsed, ConfigProtocol.AWG2)
        assertTrue("issues=${validation.issues}", validation.isValid)
    }

    @Test
    fun `validator catches deterministic ranges dependencies and AWG3 gate`() {
        val invalid =
            validAwg2.replace("Jmin = 100", "Jmin = 400").replace("Jmax = 200", "Jmax = 2")
        val invalidResult = AwgConfigValidator().validate(invalid, ConfigProtocol.AWG2)
        assertTrue(invalidResult.issues.any { it.code == "AWG_JUNK_RANGE" })

        val awg3 = AwgConfigValidator().validate(validAwg2, ConfigProtocol.AWG3)
        assertFalse(awg3.isValid)
        assertTrue(awg3.issues.any { it.code == "CLIENT_AWG3_UNSUPPORTED" })
    }

    @Test
    fun `AWG header accepts documented uint32 ranges and warns instead of rejecting unusual values`() {
        val withHeaderRange = validAwg2.replace("H1 = 1", "H1 = 100-100000")
        val result = AwgConfigValidator().validate(withHeaderRange, ConfigProtocol.AWG2)

        assertTrue("issues=${result.issues}", result.isValid)
        assertFalse(result.issues.any { it.code == "INVALID_HEADER_RANGE" })

        val unsupported =
            AwgConfigValidator()
                .validate(
                    validAwg2.replace("MTU = 1280", "MTU = 256\nCustomVendorOption = retain-me"),
                    ConfigProtocol.AWG2,
                )
        assertTrue(unsupported.isValid)
        assertTrue(
            unsupported.issues.any {
                it.code == "UNSUPPORTED_PARAMETER" && it.level == ValidationLevel.WARNING
            }
        )
    }

    @Test
    fun `parser rejects ambiguous duplicate and missing interface sections`() {
        val parser = AwgConfigParser()

        assertTrue(runCatching { parser.parse("[Peer]\nPublicKey = key") }.isFailure)
        assertTrue(
            runCatching { parser.parse(validAwg2.replace("Jc = 4", "Jc = 4\nJc = 5")) }.isFailure
        )
    }

    @Test
    fun `candidate generation does not apply and public profile omits all secret values`() {
        val document = AwgConfigParser().parse(validAwg2)
        val candidate = AwgConfigGenerator().candidate("Candidate", ConfigProtocol.AWG2, document)
        val public = candidate.toPublic()

        assertEquals(setOf("awg2"), candidate.capabilityRequirements)
        assertFalse(public.parameters.keys.any { it.contains("PrivateKey", ignoreCase = true) })
        assertFalse(public.parameters.keys.any { it.contains("PresharedKey", ignoreCase = true) })
        assertFalse(
            public.parameters.values.any { it == "client-private-key" || it == "peer-secret" }
        )
        assertTrue("issues=${candidate.validation.issues}", candidate.validation.isValid)
    }

    @Test
    fun `wireguard profile rejects AWG fields rather than silently treating them as generic values`() {
        val result = AwgConfigValidator().validate(validAwg2, ConfigProtocol.WIREGUARD)
        assertTrue(result.issues.any { it.code == "AWG_FIELDS_IN_WG" })
    }

    @Test
    fun `profile history links explicit diagnostic action and measured result`() = runBlocking {
        val profile =
            AwgConfigGenerator()
                .candidate("Baseline", ConfigProtocol.AWG2, AwgConfigParser().parse(validAwg2))
        val repository = InMemoryAwgProfileRepository()
        repository.save(profile)
        repository.recordChange(
            ConfigurationChange(
                newProfileId = profile.id,
                reason = "user applied validated candidate",
                diagnosticAction = DiagnosticAction(description = "Compare reconnect history"),
                result =
                    ConfigurationResult(applied = true, incidentDelta = -1, reconnectDelta = -2),
            )
        )

        assertEquals(profile, repository.get(profile.id))
        assertEquals(-2, repository.changes(profile.id).single().result?.reconnectDelta)
    }

    @Test
    fun `deterministic advisor produces candidate only transport advice and public fingerprint excludes keys`() {
        val profile =
            AwgConfigGenerator()
                .candidate("Candidate", ConfigProtocol.AWG2, AwgConfigParser().parse(validAwg2))
                .toPublic()
        val changedPrivate =
            AwgConfigGenerator()
                .candidate(
                    "Candidate",
                    ConfigProtocol.AWG2,
                    AwgConfigParser()
                        .parse(validAwg2.replace("client-private-key", "different-private-key")),
                )
                .toPublic()
        val advice =
            DeterministicConfigurationAdvisor()
                .recommend(
                    ConfigurationAdvisorContext(
                        profile = profile,
                        diagnostics =
                            listOf(
                                DiagnosticEvent(
                                    category = DiagnosticCategory.NETWORK,
                                    code = "NETWORK_AVAILABLE",
                                    summary = "available",
                                ),
                                DiagnosticEvent(
                                    category = DiagnosticCategory.TUNNEL,
                                    code = "TUNNEL_BOUNCE_FAILED",
                                    summary = "failed",
                                ),
                                DiagnosticEvent(
                                    category = DiagnosticCategory.TUNNEL,
                                    code = "TUNNEL_RECONNECT_FAILED",
                                    summary = "failed",
                                ),
                            ),
                    )
                )

        assertTrue(advice.any { it.configurationArea == "endpoint / AWG2 masking" })
        assertTrue(advice.all { it.requiresUserConfirmation })
        assertEquals(
            ConfigurationFingerprint.of(profile),
            ConfigurationFingerprint.of(changedPrivate),
        )
        assertTrue(ConfigurationFingerprint.changedParameters(profile, changedPrivate).isEmpty())
    }
}
