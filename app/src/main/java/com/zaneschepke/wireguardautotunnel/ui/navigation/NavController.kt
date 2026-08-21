package com.zaneschepke.wireguardautotunnel.ui.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.zaneschepke.wireguardautotunnel.cat.runtime.CatRuntimeLog

class NavController(
    private val backStack: NavBackStack<NavKey>,
    private val isDisclosureShown: Boolean,
    private val onChange: (previous: NavKey?) -> Unit = {},
    private val onExitApp: () -> Unit = {},
) {
    fun push(route: NavKey) {
        val previous = currentRoute
        onChange(previous)
        backStack.add(route)
        CatRuntimeLog.navigation(previous, route, "push")
    }

    fun pop(): Boolean {
        if (!canPop) {
            onExitApp()
            return true
        }
        val previous = currentRoute
        onChange(previous)
        backStack.removeLastOrNull()
        CatRuntimeLog.navigation(previous, currentRoute, "pop")
        return true
    }

    fun popUpTo(route: NavKey) {
        val previous = currentRoute
        onChange(previous)

        val targetRoute =
            if (route is Route.AutoTunnel && !isDisclosureShown) Route.LocationDisclosure else route
        backStack.clear()
        if (route is Route.Tunnels) backStack.add(targetRoute)
        else backStack.addAll(setOf(Route.Tunnels, targetRoute))
        CatRuntimeLog.navigation(previous, targetRoute, "pop_up_to")
    }

    val currentRoute: NavKey?
        get() = backStack.lastOrNull()

    val canPop: Boolean
        get() = backStack.size > 1
}
