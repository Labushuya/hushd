// SPDX-License-Identifier: GPL-3.0-or-later
package dev.labushuya.hushd.core.automation

/**
 * Commands Engine -> A11y-Service.
 * Lebt in core/automation, damit beide Module (core und service:accessibility)
 * darauf zugreifen können ohne zirkuläre Module-Dependency.
 */
sealed interface ServiceCommand {
    data class ClickToggleForPackage(val targetPackage: String) : ServiceCommand
    data class VerifyToggleOff(val targetPackage: String) : ServiceCommand
    data object GlobalBack : ServiceCommand
    data object DisableSelf : ServiceCommand
}
