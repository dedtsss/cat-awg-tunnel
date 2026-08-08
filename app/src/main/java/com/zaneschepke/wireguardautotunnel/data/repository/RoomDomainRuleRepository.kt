package com.zaneschepke.wireguardautotunnel.data.repository

import com.dedtsss.catawg.core.routing.DomainMatchMode
import com.dedtsss.catawg.core.routing.DomainResolutionStatus
import com.dedtsss.catawg.core.routing.DomainRouteTarget
import com.dedtsss.catawg.core.routing.DomainRule
import com.dedtsss.catawg.core.routing.DomainRuleRepository
import com.dedtsss.catawg.core.routing.DomainRuleSource
import com.dedtsss.catawg.core.routing.ResolvedIp
import com.zaneschepke.wireguardautotunnel.data.dao.CatDomainRuleDao
import com.zaneschepke.wireguardautotunnel.data.entity.CatDomainRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RoomDomainRuleRepository(
    private val dao: CatDomainRuleDao,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : DomainRuleRepository {
    override val rules: Flow<List<DomainRule>> = dao.allFlow().map { entries -> entries.map(::toDomain) }

    override suspend fun get(id: String): DomainRule? = dao.get(id)?.let(::toDomain)

    override suspend fun forTunnel(tunnelId: Int): List<DomainRule> = dao.forTunnel(tunnelId).map(::toDomain)

    override suspend fun upsert(rule: DomainRule) {
        dao.upsert(rule.toEntity())
    }

    override suspend fun delete(id: String) {
        dao.delete(id)
    }

    override suspend fun deleteForTunnel(tunnelId: Int) {
        dao.deleteForTunnel(tunnelId)
    }

    private fun DomainRule.toEntity() =
        CatDomainRule(
            id = id,
            tunnel_id = tunnelId,
            domain = domain,
            match_mode = matchMode.name,
            route_target = routeTarget.name,
            enabled = enabled,
            resolved_ipv4_json = json.encodeToString(resolvedIpv4),
            resolved_ipv6_json = json.encodeToString(resolvedIpv6),
            last_resolved_at = lastResolvedAt,
            last_resolve_status = lastResolveStatus.name,
            source = source.name,
            comment = comment,
        )

    private fun toDomain(entity: CatDomainRule) =
        DomainRule(
            id = entity.id,
            tunnelId = entity.tunnel_id,
            domain = entity.domain,
            matchMode = enumOr(entity.match_mode, DomainMatchMode.SUFFIX),
            routeTarget = enumOr(entity.route_target, DomainRouteTarget.LOCAL_DIRECT),
            enabled = entity.enabled,
            resolvedIpv4 = decodeIps(entity.resolved_ipv4_json),
            resolvedIpv6 = decodeIps(entity.resolved_ipv6_json),
            lastResolvedAt = entity.last_resolved_at,
            lastResolveStatus = enumOr(entity.last_resolve_status, DomainResolutionStatus.NEVER),
            source = enumOr(entity.source, DomainRuleSource.MANUAL),
            comment = entity.comment,
        )

    private inline fun <reified T : Enum<T>> enumOr(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private fun decodeIps(value: String): List<ResolvedIp> =
        runCatching { json.decodeFromString<List<ResolvedIp>>(value) }.getOrDefault(emptyList())
}
