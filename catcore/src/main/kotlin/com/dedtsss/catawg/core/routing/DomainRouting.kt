package com.dedtsss.catawg.core.routing

import java.net.IDN
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A durable logical routing rule. DNS answers are deliberately stored separately from the logical
 * suffix rule: DNS cannot enumerate all possible subdomains, but observed answers can be applied to
 * Android's IP based VPN route table.
 */
@Serializable
data class DomainRule(
    val id: String = UUID.randomUUID().toString(),
    val tunnelId: Int,
    val domain: String,
    val matchMode: DomainMatchMode = DomainMatchMode.SUFFIX,
    val routeTarget: DomainRouteTarget = DomainRouteTarget.LOCAL_DIRECT,
    val enabled: Boolean = true,
    val resolvedIpv4: List<ResolvedIp> = emptyList(),
    val resolvedIpv6: List<ResolvedIp> = emptyList(),
    val lastResolvedAt: String? = null,
    val lastResolveStatus: DomainResolutionStatus = DomainResolutionStatus.NEVER,
    val source: DomainRuleSource = DomainRuleSource.MANUAL,
    val comment: String? = null,
)

@Serializable
enum class DomainMatchMode {
    EXACT,
    SUFFIX,
}

@Serializable
enum class DomainRouteTarget {
    LOCAL_DIRECT,
    DEFAULT_TUNNEL,
    SERVER_EGRESS,
    BLOCK,
}

@Serializable
enum class DomainRuleSource {
    MANUAL,
    SHARE,
    IMPORT,
}

@Serializable
enum class DomainResolutionStatus {
    NEVER,
    SUCCESS,
    EMPTY,
    TIMEOUT,
    FAILED,
}

@Serializable
data class ResolvedIp(
    val address: String,
    val firstSeenAt: String,
    val lastSeenAt: String,
    /**
     * A historical address is retained for diagnostics but never added to the VPN exclusion set.
     */
    val isCurrent: Boolean = true,
)

@Serializable
data class DomainResolution(
    val domain: String,
    val ipv4: List<String> = emptyList(),
    val ipv6: List<String> = emptyList(),
    val status: DomainResolutionStatus = DomainResolutionStatus.SUCCESS,
    val resolvedAt: String = Instant.now().toString(),
    val message: String? = null,
)

/** Platform adapters resolve on an intended underlying Android network when one is available. */
interface DomainResolver {
    suspend fun resolve(domain: String): DomainResolution
}

interface DomainRuleRepository {
    val rules: Flow<List<DomainRule>>

    suspend fun get(id: String): DomainRule?

    suspend fun forTunnel(tunnelId: Int): List<DomainRule>

    suspend fun upsert(rule: DomainRule)

    suspend fun delete(id: String)

    suspend fun deleteForTunnel(tunnelId: Int)
}

/** A synchronous cache boundary used by the tunnel module while a VpnService.Builder is open. */
interface DomainRouteProvider {
    fun exclusionsFor(tunnelId: Int): List<RouteExclusion>
}

@Serializable
data class RouteExclusion(val address: String, val prefixLength: Int, val ruleId: String)

object DomainNormalizer {
    private val urlRegex =
        Regex(
            """(?i)(https?://[^\s]+|//[^\s]+|(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}(?:/[^\s]*)?)"""
        )
    private val ipv4Regex = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")

    /** Returns an ASCII hostname, never a URL, and does not silently strip a meaningful host. */
    fun normalize(input: String?): String? {
        val raw =
            input?.trim()?.trim('"', '\'', '(', ')', '[', ']', '{', '}', ',', ';') ?: return null
        if (raw.isBlank()) return null

        val candidate =
            runCatching {
                    when {
                        raw.startsWith("//") -> URI("https:$raw").host
                        raw.contains("://") -> URI(raw).host
                        raw.startsWith("mailto:", ignoreCase = true) -> URI(raw).host
                        else -> raw.substringBefore('/').substringBefore('?').substringBefore('#')
                    }
                }
                .getOrNull() ?: raw.substringBefore('/').substringBefore('?').substringBefore('#')

        val host = candidate.trim().trimEnd('.').removePrefix("[").removeSuffix("]")
        if (host.isBlank() || host.contains(':') || ipv4Regex.matches(host)) return null
        val ascii =
            runCatching { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase() }.getOrNull()
                ?: return null
        if (
            ascii.length !in 1..253 || ascii.startsWith('.') || ascii.endsWith('.') || ".." in ascii
        )
            return null
        if (ascii.split('.').any { it.isEmpty() || it.length > 63 }) return null
        return ascii
    }

    /** Handles Chrome/Firefox shared URL, a bare domain, and text that contains either one. */
    fun fromSharedText(text: String?): String? {
        normalize(text)?.let {
            return it
        }
        val candidate = text?.let { urlRegex.find(it)?.value } ?: return null
        return normalize(candidate)
    }
}

object DomainRuleMatcher {
    fun matches(rule: DomainRule, hostname: String): Boolean {
        val normalized = DomainNormalizer.normalize(hostname) ?: return false
        return when (rule.matchMode) {
            DomainMatchMode.EXACT -> normalized == rule.domain
            DomainMatchMode.SUFFIX ->
                normalized == rule.domain || normalized.endsWith(".${rule.domain}")
        }
    }

    /** Exact wins, otherwise the most specific suffix wins. Disabled rules never participate. */
    fun effectiveRule(rules: Iterable<DomainRule>, hostname: String): DomainRule? {
        return rules
            .asSequence()
            .filter { it.enabled && matches(it, hostname) }
            .sortedWith(
                compareByDescending<DomainRule> { it.matchMode == DomainMatchMode.EXACT }
                    .thenByDescending { it.domain.length }
            )
            .firstOrNull()
    }
}

object DomainRoutingPlanner {
    /**
     * Combines common rules with a tunnel's local snapshot. A local rule with the same normalized
     * host and match mode is an explicit override of the common rule; unrelated rules coexist.
     */
    fun effectiveRules(
        globalRules: Iterable<DomainRule>,
        localRules: Iterable<DomainRule>,
    ): List<DomainRule> {
        val local = localRules.toList()
        val localKeys = local.map { it.domain to it.matchMode }.toSet()
        return globalRules.filterNot { it.domain to it.matchMode in localKeys } + local
    }

    fun exclusions(rules: Iterable<DomainRule>): List<RouteExclusion> {
        return rules
            .asSequence()
            .filter { it.enabled && it.routeTarget == DomainRouteTarget.LOCAL_DIRECT }
            .flatMap { rule ->
                sequence {
                    rule.resolvedIpv4
                        .filter { it.isCurrent }
                        .forEach { yield(RouteExclusion(it.address, 32, rule.id)) }
                    rule.resolvedIpv6
                        .filter { it.isCurrent }
                        .forEach { yield(RouteExclusion(it.address, 128, rule.id)) }
                }
            }
            .distinctBy { it.address to it.prefixLength }
            .sortedWith(compareBy<RouteExclusion> { it.address }.thenBy { it.prefixLength })
            .toList()
    }

    /** Retains an observed history while constraining it for on-device diagnostics storage. */
    fun mergeResolution(
        rule: DomainRule,
        resolution: DomainResolution,
        maxHistoryPerFamily: Int = 32,
    ): DomainRule {
        require(maxHistoryPerFamily > 0)
        val now = resolution.resolvedAt
        fun merge(existing: List<ResolvedIp>, observed: List<String>): List<ResolvedIp> {
            val currentAnswers =
                observed.map(String::trim).filter(String::isNotBlank).distinct().toSet()
            val byAddress = existing.associateBy { it.address }.toMutableMap()
            // A failed lookup must not silently remove the last usable direct-route cache. Empty
            // and successful answers are authoritative; failed/timeout answers are not.
            if (
                resolution.status == DomainResolutionStatus.SUCCESS ||
                    resolution.status == DomainResolutionStatus.EMPTY
            ) {
                byAddress.replaceAll { address, current ->
                    current.copy(isCurrent = address in currentAnswers)
                }
            }
            currentAnswers.forEach { address ->
                val current = byAddress[address]
                byAddress[address] =
                    if (current == null) ResolvedIp(address, now, now, isCurrent = true)
                    else current.copy(lastSeenAt = now, isCurrent = true)
            }
            return byAddress.values
                .sortedWith(
                    compareByDescending<ResolvedIp> { it.isCurrent }
                        .thenByDescending { it.lastSeenAt }
                )
                .take(maxHistoryPerFamily)
        }
        return rule.copy(
            resolvedIpv4 = merge(rule.resolvedIpv4, resolution.ipv4),
            resolvedIpv6 = merge(rule.resolvedIpv6, resolution.ipv6),
            lastResolvedAt = now,
            lastResolveStatus = resolution.status,
        )
    }
}

@Serializable
data class SharedIpConflict(
    val address: String,
    val ruleIds: List<String>,
    val domains: List<String>,
)

object SharedIpIndex {
    fun conflicts(rules: Iterable<DomainRule>): List<SharedIpConflict> {
        val indexed = linkedMapOf<String, MutableList<DomainRule>>()
        // The reverse index is an observed-data diagnostic aid, not an active-routing table. It
        // deliberately includes disabled and historical rules so a user can understand why an IP
        // is shared before enabling a rule.
        rules.forEach { rule ->
            (rule.resolvedIpv4 + rule.resolvedIpv6).forEach { resolved ->
                indexed.getOrPut(resolved.address) { mutableListOf() }.add(rule)
            }
        }
        return indexed
            .filterValues { it.map { rule -> rule.id }.distinct().size > 1 }
            .map { (address, related) ->
                SharedIpConflict(
                    address = address,
                    ruleIds = related.map { it.id }.distinct(),
                    domains = related.map { it.domain }.distinct(),
                )
            }
            .sortedBy { it.address }
    }
}

/** Pure decision boundary used by Android lifecycle code and JVM tests. */
object DomainRouteRebuildDecision {
    fun requiresVpnRebuild(
        before: Collection<RouteExclusion>,
        after: Collection<RouteExclusion>,
        tunnelIsActive: Boolean,
    ): Boolean =
        tunnelIsActive &&
            before.map { it.address to it.prefixLength }.toSet() !=
                after.map { it.address to it.prefixLength }.toSet()
}

@Serializable
data class DiagnosedAddress(
    val address: String,
    val route: DomainRouteTarget,
    /** Rule whose current resolved IP makes this address use the route, if known. */
    val ruleId: String? = null,
    val ruleDomain: String? = null,
    /** A hostname rule may be different from the IP route owner because Android routes by IP. */
    val hostnameRuleId: String? = null,
    val hostnameRuleDomain: String? = null,
    /** Locally saved domains that have observed this IP; this is not reverse-IP attribution. */
    val knownByDomains: List<String> = emptyList(),
    val sharedWithDomains: List<String> = emptyList(),
)

@Serializable
data class DomainDiagnosis(
    val domain: String,
    val ipv4: List<DiagnosedAddress>,
    val ipv6: List<DiagnosedAddress>,
    val lastResolvedAt: String? = null,
    val resolutionStatus: DomainResolutionStatus? = null,
    val stale: Boolean,
    val changedIp: Boolean,
    val evidenceNote: String =
        "Android applies direct routing by resolved IP. Shared-IP links are only locally observed saved rules, not proof that an unknown site belongs to a domain.",
)

object DomainDiagnostics {
    fun explain(
        input: String,
        rules: Iterable<DomainRule>,
        currentResolution: DomainResolution? = null,
        now: Instant = Instant.now(),
        staleAfterSeconds: Long = 3600,
    ): DomainDiagnosis? {
        val domain = DomainNormalizer.fromSharedText(input) ?: return null
        val ruleList = rules.toList()
        val hostnameRule = DomainRuleMatcher.effectiveRule(ruleList, domain)
        val related = hostnameRule?.let { listOf(it) }.orEmpty()
        val conflicts = SharedIpIndex.conflicts(ruleList).associateBy { it.address }
        val useCurrent = currentResolution?.takeIf { it.domain == domain }
        val cachedIpv4 =
            hostnameRule?.resolvedIpv4.orEmpty().filter { it.isCurrent }.map { it.address }
        val cachedIpv6 =
            hostnameRule?.resolvedIpv6.orEmpty().filter { it.isCurrent }.map { it.address }
        val ipv4 =
            if (useCurrent?.status == DomainResolutionStatus.SUCCESS) useCurrent.ipv4
            else cachedIpv4
        val ipv6 =
            if (useCurrent?.status == DomainResolutionStatus.SUCCESS) useCurrent.ipv6
            else cachedIpv6
        fun describe(entries: List<String>): List<DiagnosedAddress> =
            entries.distinct().map { address ->
                val knownRules = ruleList.filter { candidate ->
                    (candidate.resolvedIpv4 + candidate.resolvedIpv6).any { it.address == address }
                }
                // A current LOCAL_DIRECT answer is what VpnService.Builder.excludeRoute() has
                // actually installed. It takes precedence over a hostname rule because Android
                // cannot route by hostname after DNS has resolved it.
                val ipRouteOwner =
                    knownRules
                        .asSequence()
                        .filter { candidate ->
                            candidate.enabled &&
                                candidate.routeTarget == DomainRouteTarget.LOCAL_DIRECT &&
                                (candidate.resolvedIpv4 + candidate.resolvedIpv6).any {
                                    it.address == address && it.isCurrent
                                }
                        }
                        .sortedBy { it.domain }
                        .firstOrNull()
                val routeOwner = ipRouteOwner ?: hostnameRule
                val target = routeOwner?.routeTarget ?: DomainRouteTarget.DEFAULT_TUNNEL
                val knownDomains = knownRules.map { it.domain }.distinct().sorted()
                DiagnosedAddress(
                    address = address,
                    route = target,
                    ruleId = routeOwner?.id,
                    ruleDomain = routeOwner?.domain,
                    hostnameRuleId = hostnameRule?.id,
                    hostnameRuleDomain = hostnameRule?.domain,
                    knownByDomains = knownDomains,
                    sharedWithDomains =
                        conflicts[address]?.domains.orEmpty().filter { it != routeOwner?.domain },
                )
            }
        val last =
            useCurrent?.resolvedAt?.takeIf { useCurrent.status == DomainResolutionStatus.SUCCESS }
                ?: related.maxOfOrNull { it.lastResolvedAt ?: "" }?.ifBlank { null }
        val stale =
            if (useCurrent?.status == DomainResolutionStatus.SUCCESS) false
            else
                last?.let {
                    runCatching { Instant.parse(it).plusSeconds(staleAfterSeconds).isBefore(now) }
                        .getOrDefault(true)
                } ?: true
        val cachedAddresses = (cachedIpv4 + cachedIpv6).toSet()
        val currentAddresses = (useCurrent?.ipv4.orEmpty() + useCurrent?.ipv6.orEmpty()).toSet()
        val changed =
            (useCurrent?.status == DomainResolutionStatus.SUCCESS &&
                cachedAddresses != currentAddresses) ||
                related.any { ruleEntry ->
                    (ruleEntry.resolvedIpv4 + ruleEntry.resolvedIpv6).any { !it.isCurrent }
                }
        return DomainDiagnosis(
            domain = domain,
            ipv4 = describe(ipv4),
            ipv6 = describe(ipv6),
            lastResolvedAt = last,
            resolutionStatus = useCurrent?.status ?: hostnameRule?.lastResolveStatus,
            stale = stale,
            changedIp = changed,
        )
    }
}

@Serializable
data class DomainRuleExport(
    val schemaVersion: String = SCHEMA_VERSION,
    val exportedAt: String = Instant.now().toString(),
    val rules: List<DomainRule>,
) {
    companion object {
        const val SCHEMA_VERSION = "cat.domain-rules.v1"
    }
}

object DomainRuleCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun toJson(rules: List<DomainRule>): String =
        json.encodeToString(DomainRuleExport(rules = rules))

    fun fromJson(value: String): List<DomainRule> =
        json.decodeFromString<DomainRuleExport>(value).rules

    /** TXT is intentionally a simple portable list; JSON is the lossless metadata format. */
    fun toTxt(rules: List<DomainRule>): String = buildString {
        appendLine("# Cat AWG Tunnel domain rules; JSON preserves metadata and DNS history.")
        rules.forEach { rule ->
            append(rule.domain)
            append('\t')
            append(rule.matchMode.name)
            append('\t')
            append(rule.routeTarget.name)
            append('\t')
            append(rule.enabled)
            appendLine()
        }
    }

    fun fromTxt(
        value: String,
        tunnelId: Int,
        source: DomainRuleSource = DomainRuleSource.IMPORT,
    ): List<DomainRule> =
        value
            .lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank() || trimmed.startsWith("#")) return@mapNotNull null
                val fields = trimmed.split('\t')
                val domain =
                    DomainNormalizer.normalize(fields.firstOrNull()) ?: return@mapNotNull null
                DomainRule(
                    tunnelId = tunnelId,
                    domain = domain,
                    matchMode =
                        fields.getOrNull(1)?.let {
                            runCatching { DomainMatchMode.valueOf(it) }.getOrNull()
                        } ?: DomainMatchMode.SUFFIX,
                    routeTarget =
                        fields.getOrNull(2)?.let {
                            runCatching { DomainRouteTarget.valueOf(it) }.getOrNull()
                        } ?: DomainRouteTarget.LOCAL_DIRECT,
                    enabled = fields.getOrNull(3)?.toBooleanStrictOrNull() ?: true,
                    source = source,
                )
            }
            .toList()
}

data class ShareTargetCandidate(val domain: String, val rawText: String)

object ShareTargetParser {
    fun parse(sharedText: CharSequence?): ShareTargetCandidate? {
        val raw = sharedText?.toString()?.trim() ?: return null
        val domain = DomainNormalizer.fromSharedText(raw) ?: return null
        return ShareTargetCandidate(domain = domain, rawText = raw)
    }
}
