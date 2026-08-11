package com.zaneschepke.wireguardautotunnel.data.cat

import androidx.datastore.preferences.core.stringPreferencesKey
import com.dedtsss.catawg.core.configurator.ConfigurationChange
import com.dedtsss.catawg.core.configurator.ConfigurationExperiment
import com.dedtsss.catawg.core.configurator.PublicConfigProfile
import com.dedtsss.catawg.core.protocol.isSecretBearingConfigKey
import com.zaneschepke.wireguardautotunnel.data.DataStoreManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class StoredConfigProfiles(
    val profiles: List<PublicConfigProfile> = emptyList(),
    val changes: List<ConfigurationChange> = emptyList(),
    val experiments: List<ConfigurationExperiment> = emptyList(),
)

/** Persists only public, redacted configurator profiles and explicit change metadata. */
class CatConfigProfileStore(private val dataStoreManager: DataStoreManager) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun profiles(): List<PublicConfigProfile> = read().profiles

    suspend fun changes(): List<ConfigurationChange> = read().changes

    suspend fun experiments(): List<ConfigurationExperiment> = read().experiments

    suspend fun save(profile: PublicConfigProfile) {
        require(profile.parameters.keys.none(::isSecretBearingConfigKey)) {
            "Secret-bearing fields cannot be stored as public Cat profiles"
        }
        val current = read()
        val profiles =
            (current.profiles.filterNot { it.id == profile.id } + profile).takeLast(MAX_PROFILES)
        write(current.copy(profiles = profiles))
    }

    suspend fun recordChange(change: ConfigurationChange) {
        val current = read()
        write(
            current.copy(
                changes =
                    (current.changes.filterNot { it.id == change.id } + change).takeLast(
                        MAX_CHANGES
                    )
            )
        )
    }

    /**
     * Keeps a deliberately small, local experiment journal.  The model accepts only public field
     * names, so a private or preshared key cannot be recorded as a changed parameter.
     */
    suspend fun recordExperiment(experiment: ConfigurationExperiment) {
        require(experiment.changedParameters.keys.none(::isSecretBearingConfigKey)) {
            "Secret-bearing fields cannot be stored in configuration experiments"
        }
        val current = read()
        write(
            current.copy(
                experiments =
                    (current.experiments.filterNot { it.id == experiment.id } + experiment)
                        .takeLast(MAX_EXPERIMENTS)
            )
        )
    }

    private suspend fun read(): StoredConfigProfiles =
        dataStoreManager.getFromStore(KEY)?.let {
            runCatching { json.decodeFromString<StoredConfigProfiles>(it) }.getOrNull()
        } ?: StoredConfigProfiles()

    private suspend fun write(value: StoredConfigProfiles) {
        dataStoreManager.saveToDataStore(KEY, json.encodeToString(value))
    }

    private companion object {
        const val MAX_PROFILES = 30
        const val MAX_CHANGES = 60
        const val MAX_EXPERIMENTS = 80
        val KEY = stringPreferencesKey("CAT_CONFIG_PUBLIC_PROFILES_V1")
    }
}
