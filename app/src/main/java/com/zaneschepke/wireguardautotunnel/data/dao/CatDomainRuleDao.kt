package com.zaneschepke.wireguardautotunnel.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.zaneschepke.wireguardautotunnel.data.entity.CatDomainRule
import kotlinx.coroutines.flow.Flow

@Dao
interface CatDomainRuleDao {
    @Upsert suspend fun upsert(rule: CatDomainRule)

    @Query("SELECT * FROM cat_domain_rules ORDER BY domain COLLATE NOCASE")
    fun allFlow(): Flow<List<CatDomainRule>>

    @Query("SELECT * FROM cat_domain_rules WHERE id = :id LIMIT 1")
    suspend fun get(id: String): CatDomainRule?

    @Query("SELECT * FROM cat_domain_rules WHERE tunnel_id = :tunnelId ORDER BY domain COLLATE NOCASE")
    suspend fun forTunnel(tunnelId: Int): List<CatDomainRule>

    @Query("DELETE FROM cat_domain_rules WHERE id = :id") suspend fun delete(id: String)

    @Query("DELETE FROM cat_domain_rules WHERE tunnel_id = :tunnelId") suspend fun deleteForTunnel(tunnelId: Int)
}
