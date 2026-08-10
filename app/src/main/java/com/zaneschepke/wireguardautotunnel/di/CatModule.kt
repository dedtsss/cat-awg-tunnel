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
import com.zaneschepke.wireguardautotunnel.cat.server.CatPairingImportStore
import com.zaneschepke.wireguardautotunnel.data.cat.AndroidKeystoreCatServerCredentialStore
import com.zaneschepke.wireguardautotunnel.data.cat.CatConfigProfileStore
import com.zaneschepke.wireguardautotunnel.data.cat.CatServerSettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
    singleOf(::CatPairingImportStore)
    single<CatServerClient> {
        AndroidCatServerClient(
            settingsStore = get(),
            credentials = get(),
            ioDispatcher = get(named(Dispatcher.IO)),
            applicationScope = get(named(Scope.APPLICATION)),
        )
    }
    single<CatAiProvider> { CatServerAiProvider(get(), get(), get()) }
    single {
        CatDiagnosticsSyncCoordinator(
            store = get(),
            client = get(),
            credentials = get(),
            settingsStore = get(),
            ioDispatcher = get<CoroutineDispatcher>(named(Dispatcher.IO)),
        )
    }
    singleOf(::CatConfigProfileStore)
    singleOf(::AndroidDomainRouteProvider) bind DomainRouteProvider::class
    single<DomainResolver> { AndroidDomainResolver(androidContext(), get(named(Dispatcher.IO))) }
    // These constructors use qualified application/IO bindings. Keep them explicit: Koin's
    // constructor DSL cannot infer qualifiers and would silently look for unqualified instances.
    single {
        DomainRoutingCoordinator(
            repository = get(),
            resolver = get(),
            routeProvider = get(),
            backend = get(),
            diagnostics = get(),
            networkMonitor = get(),
            applicationScope = get<CoroutineScope>(named(Scope.APPLICATION)),
            ioDispatcher = get<CoroutineDispatcher>(named(Dispatcher.IO)),
        )
    }
    single {
        ClientDiagnosticsObserver(
            backend = get(),
            networkMonitor = get(),
            recorder = get(),
            scope = get<CoroutineScope>(named(Scope.APPLICATION)),
            ioDispatcher = get<CoroutineDispatcher>(named(Dispatcher.IO)),
        )
    }
}
