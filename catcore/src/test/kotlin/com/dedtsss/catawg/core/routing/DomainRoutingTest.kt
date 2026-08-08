package com.dedtsss.catawg.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainRoutingTest {
    private val t0 = "2026-08-08T10:00:00Z"
    private val t1 = "2026-08-08T10:05:00Z"

    private fun ip(address: String, current: Boolean = true) =
        ResolvedIp(address = address, firstSeenAt = t0, lastSeenAt = t0, isCurrent = current)

    private fun rule(
        id: String = "rule",
        domain: String = "example.com",
        mode: DomainMatchMode = DomainMatchMode.SUFFIX,
        target: DomainRouteTarget = DomainRouteTarget.LOCAL_DIRECT,
        enabled: Boolean = true,
        ipv4: List<ResolvedIp> = emptyList(),
        ipv6: List<ResolvedIp> = emptyList(),
    ) =
        DomainRule(
            id = id,
            tunnelId = 7,
            domain = domain,
            matchMode = mode,
            routeTarget = target,
            enabled = enabled,
            resolvedIpv4 = ipv4,
            resolvedIpv6 = ipv6,
            lastResolvedAt = t0,
            lastResolveStatus = DomainResolutionStatus.SUCCESS,
            comment = "fixture",
        )

    @Test
    fun `normalizes URL and shared browser text without accepting literal IPs`() {
        assertEquals("example.com", DomainNormalizer.normalize(" https://Example.COM/path?q=1 "))
        assertEquals("sub.example.com", DomainNormalizer.fromSharedText("Open https://sub.example.com/news now"))
        assertEquals("xn--e1afmkfd.xn--p1ai", DomainNormalizer.normalize("пример.рф"))
        assertNull(DomainNormalizer.normalize("192.0.2.7"))
        assertNull(DomainNormalizer.normalize("https://[2001:db8::7]/"))
        assertEquals("example.com", ShareTargetParser.parse("example.com\n")?.domain)
    }

    @Test
    fun `exact rule wins while suffix rule includes subdomains`() {
        val suffix = rule(id = "suffix", domain = "example.com")
        val exact = rule(id = "exact", domain = "api.example.com", mode = DomainMatchMode.EXACT)

        assertTrue(DomainRuleMatcher.matches(suffix, "api.example.com"))
        assertFalse(DomainRuleMatcher.matches(exact, "www.api.example.com"))
        assertEquals("exact", DomainRuleMatcher.effectiveRule(listOf(suffix, exact), "api.example.com")?.id)
        assertEquals("suffix", DomainRuleMatcher.effectiveRule(listOf(suffix, exact), "www.example.com")?.id)
    }

    @Test
    fun `planner emits only current local-direct v4 and v6 host exclusions`() {
        val local = rule(ipv4 = listOf(ip("198.51.100.8"), ip("198.51.100.9", current = false)), ipv6 = listOf(ip("2001:db8::8")))
        val defaultTunnel = rule(id = "vpn", target = DomainRouteTarget.DEFAULT_TUNNEL, ipv4 = listOf(ip("198.51.100.10")))
        val disabled = rule(id = "disabled", enabled = false, ipv4 = listOf(ip("198.51.100.11")))

        val exclusions = DomainRoutingPlanner.exclusions(listOf(local, defaultTunnel, disabled))

        assertEquals(
            setOf("198.51.100.8/32", "2001:db8::8/128"),
            exclusions.map { "${it.address}/${it.prefixLength}" }.toSet(),
        )
    }

    @Test
    fun `changed DNS keeps history but routes only the newly current answers`() {
        val before = rule(ipv4 = listOf(ip("198.51.100.1")))
        val merged =
            DomainRoutingPlanner.mergeResolution(
                before,
                DomainResolution(
                    domain = before.domain,
                    ipv4 = listOf("198.51.100.2", "198.51.100.3"),
                    resolvedAt = t1,
                ),
            )

        assertEquals(setOf("198.51.100.2", "198.51.100.3"), merged.resolvedIpv4.filter { it.isCurrent }.map { it.address }.toSet())
        assertFalse(merged.resolvedIpv4.first { it.address == "198.51.100.1" }.isCurrent)
        assertEquals(
            setOf("198.51.100.2", "198.51.100.3"),
            DomainRoutingPlanner.exclusions(listOf(merged)).map { it.address }.toSet(),
        )
    }

    @Test
    fun `shared IP index and diagnosis explain the local routing limitation`() {
        val main = rule(id = "main", domain = "example.com", ipv4 = listOf(ip("203.0.113.10")))
        val other = rule(id = "other", domain = "cdn.example.net", enabled = false, ipv4 = listOf(ip("203.0.113.10")))
        val conflicts = SharedIpIndex.conflicts(listOf(main, other))

        assertEquals(listOf("example.com", "cdn.example.net"), conflicts.single().domains)
        val diagnosis =
            DomainDiagnostics.explain(
                input = "https://www.example.com/health",
                rules = listOf(main, other),
                currentResolution = DomainResolution("www.example.com", ipv4 = listOf("203.0.113.10")),
            )

        assertNotNull(diagnosis)
        assertEquals(DomainRouteTarget.LOCAL_DIRECT, diagnosis!!.ipv4.single().route)
        assertEquals(listOf("cdn.example.net"), diagnosis.ipv4.single().sharedWithDomains)
        assertFalse(diagnosis.stale)
    }

    @Test
    fun `JSON and TXT imports preserve logical rule metadata`() {
        val original = rule(id = "portable", domain = "пример.рф", ipv4 = listOf(ip("192.0.2.4")))
        val json = DomainRuleCodec.toJson(listOf(original))
        val decoded = DomainRuleCodec.fromJson(json).single()
        assertEquals(original.id, decoded.id)
        assertEquals(original.comment, decoded.comment)
        assertEquals(original.resolvedIpv4, decoded.resolvedIpv4)

        val txt = DomainRuleCodec.toTxt(listOf(original))
        val txtRule = DomainRuleCodec.fromTxt(txt, tunnelId = 99).single()
        assertEquals("xn--e1afmkfd.xn--p1ai", txtRule.domain)
        assertEquals(99, txtRule.tunnelId)
        assertEquals(DomainRuleSource.IMPORT, txtRule.source)
    }

    @Test
    fun `rebuild decision is active-only and ignores route ordering`() {
        val first = RouteExclusion("198.51.100.1", 32, "a")
        val second = RouteExclusion("2001:db8::1", 128, "a")

        assertFalse(DomainRouteRebuildDecision.requiresVpnRebuild(listOf(first, second), listOf(second, first), true))
        assertFalse(
            DomainRouteRebuildDecision.requiresVpnRebuild(
                listOf(first.copy(ruleId = "old")),
                listOf(first.copy(ruleId = "replacement")),
                true,
            )
        )
        assertFalse(DomainRouteRebuildDecision.requiresVpnRebuild(listOf(first), listOf(second), false))
        assertTrue(DomainRouteRebuildDecision.requiresVpnRebuild(listOf(first), listOf(second), true))
    }

    @Test
    fun `deleting an enabled local-direct rule removes its exclusion and requires an active rebuild`() {
        val activeRule = rule(ipv4 = listOf(ip("198.51.100.77")))
        val beforeDelete = DomainRoutingPlanner.exclusions(listOf(activeRule))
        val afterDelete = DomainRoutingPlanner.exclusions(emptyList())

        assertEquals(listOf("198.51.100.77/32"), beforeDelete.map { "${it.address}/${it.prefixLength}" })
        assertTrue(DomainRouteRebuildDecision.requiresVpnRebuild(beforeDelete, afterDelete, tunnelIsActive = true))
    }
}
