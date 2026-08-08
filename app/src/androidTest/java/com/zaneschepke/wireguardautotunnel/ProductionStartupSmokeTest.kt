package com.zaneschepke.wireguardautotunnel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dedtsss.catawg.core.diagnostics.ClientDiagnosticRecorder
import com.dedtsss.catawg.core.diagnostics.DiagnosticStore
import com.dedtsss.catawg.core.routing.DomainRuleRepository
import com.zaneschepke.wireguardautotunnel.cat.diagnostics.CatDiagnosticsSyncCoordinator
import com.zaneschepke.wireguardautotunnel.cat.diagnostics.ClientDiagnosticsObserver
import com.zaneschepke.wireguardautotunnel.cat.routing.DomainRoutingCoordinator
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Runs in the production application process. If Application.onCreate cannot start Koin or
 * resolve the objects it touches, instrumentation never reaches this assertion and the build
 * fails instead of publishing an APK that crashes on first launch.
 */
@RunWith(AndroidJUnit4::class)
class ProductionStartupSmokeTest {
    @Test
    fun applicationStartsAndProductionCatGraphResolves() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(targetContext.applicationContext is WireGuardAutoTunnel)

        val koin = GlobalContext.get()
        assertNotNull(koin.get<ClientDiagnosticsObserver>())
        assertNotNull(koin.get<ClientDiagnosticRecorder>())
        assertNotNull(koin.get<DiagnosticStore>())
        assertNotNull(koin.get<DomainRuleRepository>())
        assertNotNull(koin.get<DomainRoutingCoordinator>())
        assertNotNull(koin.get<CatDiagnosticsSyncCoordinator>())
    }
}
