package com.zaneschepke.wireguardautotunnel.di

import com.dedtsss.catawg.core.ai.CatAiProvider
import com.dedtsss.catawg.core.ai.DisabledCatAiProvider
import com.dedtsss.catawg.core.protocol.CatServerClient
import com.dedtsss.catawg.core.protocol.InMemoryCatServerClient
import com.dedtsss.catawg.core.routing.DomainResolver
import com.dedtsss.catawg.core.routing.DomainRouteProvider
import com.zaneschepke.wireguardautotunnel.cat.diagnostics.ClientDiagnosticsObserver
import com.zaneschepke.wireguardautotunnel.cat.routing.AndroidDomainResolver
import com.zaneschepke.wireguardautotunnel.cat.routing.AndroidDomainRouteProvider
import com.zaneschepke.wireguardautotunnel.cat.routing.DomainRoutingCoordinator
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val catModule = module {
    // These local defaults keep the client/server and AI boundaries testable without credentials
    // or network traffic. A paired TLS implementation can replace them later.
    single<CatServerClient> { InMemoryCatServerClient() }
    single<CatAiProvider> { DisabledCatAiProvider }
    singleOf(::AndroidDomainRouteProvider) bind DomainRouteProvider::class
    single<DomainResolver> { AndroidDomainResolver(androidContext(), get(named(Dispatcher.IO))) }
    singleOf(::DomainRoutingCoordinator)
    singleOf(::ClientDiagnosticsObserver)
}
