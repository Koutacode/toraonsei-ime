package com.toraonsei.session

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppContextProvider {

    data class FocusedApp(
        val packageName: String,
        val label: String
    )

    private val _focusedApp = MutableStateFlow<FocusedApp?>(null)
    val focusedApp: StateFlow<FocusedApp?> = _focusedApp.asStateFlow()

    fun updateFromAccessibility(context: Context, packageName: CharSequence?) {
        val pkg = packageName?.toString()?.trim().orEmpty()
        if (pkg.isBlank() || pkg == context.packageName || isSystemShell(pkg)) {
            return
        }
        val label = resolveLabel(context, pkg)
        _focusedApp.value = FocusedApp(pkg, label)
    }

    fun clear() {
        _focusedApp.value = null
    }

    private fun resolveLabel(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private fun isSystemShell(pkg: String): Boolean {
        return pkg.startsWith("com.android.systemui") ||
            pkg.startsWith("com.sec.android.app.launcher") ||
            pkg.startsWith("com.google.android.apps.nexuslauncher") ||
            pkg.startsWith("com.android.launcher")
    }
}
