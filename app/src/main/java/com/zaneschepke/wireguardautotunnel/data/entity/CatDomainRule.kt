package com.zaneschepke.wireguardautotunnel.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Room representation; JSON columns retain bounded DNS observation history without a second table. */
@Entity(
    tableName = "cat_domain_rules",
    indices = [Index(value = ["tunnel_id"]), Index(value = ["domain"])],
)
data class CatDomainRule(
    @PrimaryKey val id: String,
    val tunnel_id: Int,
    val domain: String,
    val match_mode: String,
    val route_target: String,
    val enabled: Boolean,
    val resolved_ipv4_json: String,
    val resolved_ipv6_json: String,
    val last_resolved_at: String?,
    val last_resolve_status: String,
    val source: String,
    val comment: String?,
)
