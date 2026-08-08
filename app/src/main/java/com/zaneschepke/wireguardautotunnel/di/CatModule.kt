package com.zaneschepke.wireguardautotunnel.di

import com.dedtsss.catawg.core.ai.CatAiProvider
import com.dedtsss.catawg.core.protocol.CatServerClient
import com.dedtsss.catawg.core.protocol.CatServerCredentialStore
import com.dedtsss.catawg.core.routing.DomainResolver
import com.dedtsss.catawg.core.routing.DomainRouteProvider
import com.zaneschepke.wireguardautotunnel.cat.diagnostics.CatDiagnosticsSyncCoordinator
import com.zaneschepke.wireguardautotunnel.cat.diagnostics.ClientDiagnosticsObserver
import com.zaneschepke.wireguardautotunnel.cat.routing.AndroidDomainResolver
import com.zaneschepke.wireguardautotunnel.cat.routing.AndroidDomainRouteProvider
import com.zaneschepke.wireguardautotunnel.cat.routing.DomainRoutingCoordinator
import com.zaneschepke.wireguardautotunnel.cat.server.AndroidCatServerClient
import com.zaneschepke.wireguardautotunnel.cat.server.CatServerAiProvider
import com.zaneschepke.wireguardautotunnel.data.cat.AndroidKeystoreCatServerCredentialStore
import com.zaneschepke.wireguardautotunnel.data.cat.CatConfigProfileStore
import com.zaneschepke.wireguardautotunnel.data.cat.CatServerSettingsStore
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val catModule = module {
    // Production always resolves the real pinned client. Tests provide InMemoryCatServerClient in
    // their own module instead of allowing a mock to ship in the APK.
    single<CatServerCredentialStore> { AndroidKeystoreCatServerCredentialStore(androidContext()) }
    singleOf(::CatServerSettingsStore)
    single<CatServerClient> {
        AndroidCatServerClient(
            settingsStore = get(),
            credentials = get(),
            ioDispatcher = get(named(Dispatcher.IO)),
            applicationScope = get(named(Scope.APPLICATION)),
        )
    }
    single<CatAiProvider> { CatServerAiProvider(get(), get(), get()) }
    singleOf(::CatDiagnosticsSyncCoordinator)
    singleOf(::CatConfigProfileStore)
    singleOf(::AndroidDomainRouteProvider) bind DomainRouteProvider::class
    single<DomainResolver> { AndroidDomainResolver(androidContext(), get(named(Dispatcher.IO))) }
    singleOf(::DomainRoutingCoordinator)
    singleOf(::ClientDiagnosticsObserver)
}
