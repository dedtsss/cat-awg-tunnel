package com.zaneschepke.wireguardautotunnel.cat.routing

import android.content.Context
import android.net.ConnectivityManager
import com.dedtsss.catawg.core.routing.DomainResolution
import com.dedtsss.catawg.core.routing.DomainResolutionStatus
import com.dedtsss.catawg.core.routing.DomainResolver
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * DNS runs off the UI thread and first asks Android's currently selected network. This is the
 * closest public-network context available to an app before a VPN interface is rebuilt.
 */
class AndroidDomainResolver(context: Context, private val ioDispatcher: CoroutineDispatcher) : DomainResolver {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override suspend fun resolve(domain: String): DomainResolution =
        withContext(ioDispatcher) {
            try {
                withTimeout(5_000L) {
                    val network = connectivityManager.activeNetwork
                    val answers = network?.getAllByName(domain) ?: InetAddress.getAllByName(domain)
                    DomainResolution(
                        domain = domain,
                        ipv4 = answers.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.distinct(),
                        ipv6 = answers.filterIsInstance<Inet6Address>().mapNotNull { it.hostAddress }.distinct(),
                        status = if (answers.isEmpty()) DomainResolutionStatus.EMPTY else DomainResolutionStatus.SUCCESS,
                    )
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                DomainResolution(domain = domain, status = DomainResolutionStatus.TIMEOUT, message = "DNS lookup timed out")
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                DomainResolution(
                    domain = domain,
                    status = DomainResolutionStatus.FAILED,
                    message = error.message?.take(200),
                )
            }
        }
}
