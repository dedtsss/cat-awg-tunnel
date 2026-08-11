package com.dedtsss.catawg.core.configurator

import com.dedtsss.catawg.core.diagnostics.DiagnosticEvent
import com.dedtsss.catawg.core.diagnostics.Incident
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * A narrow, sanitized boundary for configuration advice. The bundled implementation is entirely
 * deterministic. A future provider may implement this interface, but must receive this public
 * context rather than a raw WireGuard/AWG file or device credentials.
 */
interface ConfigurationAdvisor {
    fun recommend(context: ConfigurationAdvisorContext): List<DiagnosticRecommendation>
}

@Serializable
data class ConfigurationAdvisorContext(
    val profile: PublicConfigProfile? = null,
    val diagnostics: List<DiagnosticEvent> = emptyList(),
    val incidents: List<Incident> = emptyList(),
    val networkContext: Map<String, String> = emptyMap(),
)

@Serializable
enum class ConfigurationLayer {
    NETWORK,
    TRANSPORT,
    DNS,
    ROUTING,
    CONFIGURATION,
    SERVER,
}

@Serializable
data class DiagnosticRecommendation(
    val id: String = UUID.randomUUID().toString(),
    /** Event codes and incident classifications only; no raw diagnostic strings or secrets. */
    val evidence: List<String>,
    val suspectedLayer: ConfigurationLayer,
    val configurationArea: String,
    val recommendation: String,
    val confidence: Double,
    val requiresUserConfirmation: Boolean = true,
)

/**
 * Conservative rules deliberately distinguish "do not change configuration" from a candidate
 * suggestion. A recommendation never invokes an apply operation.
 */
class DeterministicConfigurationAdvisor : ConfigurationAdvisor {
    override fun recommend(context: ConfigurationAdvisorContext): List<DiagnosticRecommendation> {
        val codes = context.diagnostics.map { it.code }
        val classifications = context.incidents.map { it.classification }
        val recommendations = mutableListOf<DiagnosticRecommendation>()

        val networkLosses = codes.count { it == "NETWORK_LOST" }
        if (networkLosses >= 2 || "REPEATED_NETWORK_INTERRUPTION" in classifications) {
            recommendations +=
                DiagnosticRecommendation(
                    evidence =
                        listOf("NETWORK_LOST", "REPEATED_NETWORK_INTERRUPTION").filter {
                            it in codes || it in classifications
                        },
                    suspectedLayer = ConfigurationLayer.NETWORK,
                    configurationArea = "underlying network",
                    recommendation =
                        "Есть повторные перебои базовой сети. Сначала проверьте Wi‑Fi или мобильную сеть; изменение AWG-параметров сейчас не подтверждено.",
                    confidence = 0.82,
                )
        }

        val failedRecovery =
            codes.count { it == "TUNNEL_BOUNCE_FAILED" || it == "TUNNEL_RECONNECT_FAILED" } >= 2 ||
                "RECONNECT_LOOP" in classifications ||
                "TUNNEL_FAILED_TO_RECOVER" in classifications
        if (failedRecovery && "NETWORK_AVAILABLE" in codes) {
            recommendations +=
                DiagnosticRecommendation(
                    evidence =
                        (codes.filter {
                                it in
                                    setOf(
                                        "NETWORK_AVAILABLE",
                                        "TUNNEL_BOUNCE_FAILED",
                                        "TUNNEL_RECONNECT_FAILED",
                                    )
                            } +
                                classifications.filter {
                                    it in setOf("RECONNECT_LOOP", "TUNNEL_FAILED_TO_RECOVER")
                                })
                            .distinct(),
                    suspectedLayer = ConfigurationLayer.TRANSPORT,
                    configurationArea = "endpoint / AWG2 masking",
                    recommendation =
                        "Базовая сеть доступна, но восстановление туннеля повторно не удалось. Проверьте доступность UDP-пути, согласованность AWG2-параметров с сервером и альтернативный порт. Конфигурацию не изменяйте автоматически.",
                    confidence = 0.68,
                )
        }

        if (
            codes.count { it == "DNS_RESOLUTION_FAILED" } >= 2 || "DNS_FAILURE" in classifications
        ) {
            recommendations +=
                DiagnosticRecommendation(
                    evidence =
                        listOf("DNS_RESOLUTION_FAILED", "DNS_FAILURE").filter {
                            it in codes || it in classifications
                        },
                    suspectedLayer = ConfigurationLayer.DNS,
                    configurationArea = "domain routing / DNS",
                    recommendation =
                        "Повторяется ошибка DNS. Обновите IP доменного правила и сравните IPv4/IPv6 в диагностике сайта, прежде чем менять параметры AWG2.",
                    confidence = 0.75,
                )
        }

        if (codes.any { it == "IP_FAMILY_MISMATCH" } || "IPV4_IPV6_MISMATCH" in classifications) {
            recommendations +=
                DiagnosticRecommendation(
                    evidence =
                        listOf("IP_FAMILY_MISMATCH", "IPV4_IPV6_MISMATCH").filter {
                            it in codes || it in classifications
                        },
                    suspectedLayer = ConfigurationLayer.ROUTING,
                    configurationArea = "IPv4 / IPv6 routing",
                    recommendation =
                        "Есть различие между IPv4 и IPv6. Сравните адреса и маршрут сайта; этот признак сам по себе не доказывает проблему AWG2-конфигурации.",
                    confidence = 0.62,
                )
        }

        if (recommendations.isEmpty() && context.profile != null) {
            recommendations +=
                DiagnosticRecommendation(
                    evidence = emptyList(),
                    suspectedLayer = ConfigurationLayer.CONFIGURATION,
                    configurationArea = "candidate",
                    recommendation =
                        "Сейчас нет достаточных диагностических признаков для настройки параметров. Сохраните профиль как вариант и меняйте его только после явного подтверждения.",
                    confidence = 0.9,
                )
        }
        return recommendations
    }
}

@Serializable
data class DiagnosticSnapshot(
    val incidentCount: Int = 0,
    val reconnectFailureCount: Int = 0,
    val eventCodes: List<String> = emptyList(),
)

object DiagnosticSnapshotBuilder {
    fun from(events: List<DiagnosticEvent>, incidents: List<Incident>): DiagnosticSnapshot =
        DiagnosticSnapshot(
            incidentCount = incidents.size,
            reconnectFailureCount =
                events.count {
                    it.code == "TUNNEL_BOUNCE_FAILED" || it.code == "TUNNEL_RECONNECT_FAILED"
                },
            eventCodes = events.map { it.code }.distinct().sorted(),
        )
}

@Serializable
data class ConfigurationExperiment(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: String = Instant.now().toString(),
    val networkContext: Map<String, String> = emptyMap(),
    /** SHA-256 over public parameters only; never a secret-bearing configuration fingerprint. */
    val configFingerprint: String,
    val changedParameters: Map<String, String> = emptyMap(),
    val beforeDiagnostics: DiagnosticSnapshot = DiagnosticSnapshot(),
    val afterDiagnostics: DiagnosticSnapshot? = null,
    val result: String? = null,
    val userAccepted: Boolean? = null,
    val note: String? = null,
)

object ConfigurationFingerprint {
    fun of(profile: PublicConfigProfile): String {
        val canonical = buildString {
            append(profile.protocol.name)
            append('\n')
            profile.parameters.toSortedMap().forEach { (key, value) ->
                if (!isSecretLikeKey(key)) append(key).append('=').append(value).append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun changedParameters(
        before: PublicConfigProfile?,
        after: PublicConfigProfile,
    ): Map<String, String> {
        val beforeValues = before?.parameters.orEmpty()
        return (beforeValues.keys + after.parameters.keys)
            .filterNot(::isSecretLikeKey)
            .sorted()
            .mapNotNull { key ->
                val old = beforeValues[key]
                val new = after.parameters[key]
                if (old == new) null else key to "${old ?: "—"} → ${new ?: "—"}"
            }
            .toMap()
    }

    private fun isSecretLikeKey(key: String): Boolean {
        val normalized = key.lowercase().filter(Char::isLetterOrDigit)
        return listOf("privatekey", "presharedkey", "password", "token", "secret", "apikey")
            .any(normalized::contains)
    }
}

data class AwgParameterExplanation(
    val technicalName: String,
    val russianName: String,
    val purpose: String,
    val increaseEffect: String,
    val decreaseEffect: String,
    val validRange: String? = null,
    val risks: String,
    val recommendedState: String? = null,
)

/** Explanations describe documented behaviour, never an invented universal "best" tuning preset. */
object AwgParameterExplanationCatalog {
    private fun item(
        key: String,
        name: String,
        purpose: String,
        range: String? = null,
        up: String = "Зависит от сети и сервера; меняйте только согласованно с peer.",
        down: String = "Зависит от сети и сервера; меняйте только согласованно с peer.",
        risks: String = "Несовпадение с сервером может сорвать подключение.",
        recommended: String? = null,
    ) = AwgParameterExplanation(key, name, purpose, up, down, range, risks, recommended)

    val all =
        listOf(
            item(
                "PrivateKey",
                "Закрытый ключ",
                "Секретный ключ интерфейса клиента.",
                risks = "Никому не передавайте; в историю и рекомендации не попадает.",
            ),
            item("PublicKey", "Открытый ключ peer", "Открытый ключ удалённой стороны."),
            item(
                "PresharedKey",
                "Предварительный общий ключ",
                "Дополнительный секрет peer.",
                risks = "Никому не передавайте; нужен тот же ключ у peer.",
            ),
            item(
                "Address",
                "Адрес интерфейса",
                "IP-адреса, назначенные этому устройству в туннеле.",
                risks = "Пересечение или неверная маска нарушит маршрутизацию.",
            ),
            item(
                "DNS",
                "DNS-серверы",
                "Резолверы, используемые при активном туннеле.",
                risks =
                    "Неверный DNS выглядит как проблема VPN, хотя handshake может быть исправен.",
            ),
            item(
                "MTU",
                "MTU",
                "Максимальный размер пакета интерфейса.",
                "Больше — меньше накладных расходов, но выше риск фрагментации.",
                "Меньше — меньше риск фрагментации, но больше накладных расходов.",
                "1..65535",
                "Не выбирайте значение автоматически: путь и сервер могут отличаться.",
            ),
            item(
                "ListenPort",
                "Локальный UDP-порт",
                "Порт, на котором интерфейс принимает UDP.",
                "Меняет локальный порт.",
                "Меняет локальный порт.",
                "1..65535",
                "Конфликт порта не даст интерфейсу запуститься.",
            ),
            item(
                "AllowedIPs",
                "Разрешённые сети",
                "Сети, маршрутизируемые через peer.",
                risks =
                    "0.0.0.0/0 и ::/0 означают полный туннель; неверная сеть меняет маршрут трафика.",
            ),
            item(
                "Endpoint",
                "Адрес сервера",
                "Hostname/IP и UDP-порт peer.",
                risks = "Неверный порт или адрес не даст handshake.",
            ),
            item(
                "PersistentKeepalive",
                "Периодический keepalive",
                "Поддерживает NAT-сопоставление при простое.",
                "Чаще поддерживает NAT, но создаёт больше служебного трафика.",
                "Реже уменьшает служебный трафик, но NAT может истечь.",
                "0..65535 секунд",
                "Нужность зависит от NAT; магического значения нет.",
            ),
            item(
                "Jc",
                "Количество junk-пакетов",
                "Число AWG2 junk-пакетов перед handshake.",
                range = "0..65535",
                up = "Больше служебных пакетов перед handshake.",
                down = "Меньше служебных пакетов; 0 отключает их.",
                risks = "Значение должно быть совместимо с peer и реальным UDP-путём.",
                recommended =
                    "Официальная документация упоминает 4–12 как ориентир, не как обязательное значение.",
            ),
            item(
                "Jmin",
                "Минимальный размер junk",
                "Нижняя граница размера AWG2 junk-пакета.",
                range = "0..65535",
                up = "Увеличивает минимальный размер пакета.",
                down = "Уменьшает минимальный размер пакета.",
                risks =
                    "При включённом Jc не может быть больше Jmax; большие пакеты могут фрагментироваться.",
            ),
            item(
                "Jmax",
                "Максимальный размер junk",
                "Верхняя граница размера AWG2 junk-пакета.",
                range = "0..65535",
                up = "Увеличивает размер и риск фрагментации.",
                down = "Уменьшает размер junk-пакета.",
                risks = "Не ставьте наугад выше MTU реального канала.",
            ),
            item(
                "S1",
                "Padding initial handshake",
                "Добавляет padding к начальному handshake-сообщению.",
                range = "0..65535",
                risks = "Согласуйте с backend/peer; неверное сочетание может не пройти handshake.",
            ),
            item(
                "S2",
                "Padding handshake response",
                "Добавляет padding к ответу handshake.",
                range = "0..65535",
                risks = "Согласуйте с backend/peer; неверное сочетание может не пройти handshake.",
            ),
            item(
                "S3",
                "Padding cookie",
                "Добавляет padding к cookie-сообщению.",
                range = "0..65535",
                risks = "Согласуйте с backend/peer; неверное сочетание может не пройти handshake.",
            ),
            item(
                "S4",
                "Padding transport",
                "Добавляет padding к транспортным сообщениям.",
                range = "0..65535",
                risks = "Может увеличить служебные данные; согласуйте с backend/peer.",
            ),
            item(
                "H1",
                "Заголовок initial handshake",
                "uint32 или диапазон uint32 для первого handshake-сообщения.",
                range = "0..4294967295 или start-end",
                risks =
                    "Не ограничивается 1..4; диапазон должен быть корректным и совместимым с peer.",
            ),
            item(
                "H2",
                "Заголовок handshake response",
                "uint32 или диапазон uint32 для ответа handshake.",
                range = "0..4294967295 или start-end",
                risks = "Диапазон должен быть корректным и совместимым с peer.",
            ),
            item(
                "H3",
                "Заголовок cookie",
                "uint32 или диапазон uint32 для cookie-сообщения.",
                range = "0..4294967295 или start-end",
                risks = "Диапазон должен быть корректным и совместимым с peer.",
            ),
            item(
                "H4",
                "Заголовок transport",
                "uint32 или диапазон uint32 для транспортного сообщения.",
                range = "0..4294967295 или start-end",
                risks = "Диапазон должен быть корректным и совместимым с peer.",
            ),
            item(
                "I1–I5",
                "Сигнатурные пакеты",
                "Необязательные AWG2 пакеты перед handshake.",
                up = "Больше/длиннее увеличивает служебный трафик.",
                down = "Меньше/короче уменьшает служебный трафик.",
                risks =
                    "Слишком большой пакет может фрагментироваться; данные должны поддерживаться backend.",
            ),
        )
}
