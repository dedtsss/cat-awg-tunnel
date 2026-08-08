package com.dedtsss.catawg.core.ai

import com.dedtsss.catawg.core.configurator.Recommendation
import kotlinx.serialization.Serializable

/** Client configuration only; provider secrets never belong in an APK or this model. */
@Serializable
data class CatAiSettings(
    val backendUrl: String? = null,
    val deviceToken: String? = null,
    val selectedModel: String? = null,
    val memoryEnabled: Boolean = false,
)

@Serializable
data class AiAssistantRequest(val message: String, val context: Map<String, String> = emptyMap())

@Serializable
data class AiAssistantResponse(
    val message: String,
    val recommendations: List<Recommendation> = emptyList(),
)

interface CatAiProvider {
    val available: Boolean

    suspend fun ask(request: AiAssistantRequest): AiAssistantResponse?
}

/**
 * Functional offline default: routing, diagnostics and validation never depend on AI availability.
 */
object DisabledCatAiProvider : CatAiProvider {
    override val available: Boolean = false

    override suspend fun ask(request: AiAssistantRequest): AiAssistantResponse? = null
}

class StaticCatAiProvider(private val response: AiAssistantResponse?) : CatAiProvider {
    override val available: Boolean = response != null

    override suspend fun ask(request: AiAssistantRequest): AiAssistantResponse? = response
}
