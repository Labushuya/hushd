// SPDX-License-Identifier: GPL-3.0-or-later
package dev.labushuya.hushd.core.automation

/**
 * Commands Engine -> A11y-Service.
 * Lebt in core/automation, damit beide Module (core und service:accessibility)
 * darauf zugreifen können ohne zirkuläre Module-Dependency.
 */
sealed interface ServiceCommand {
    /**
     * Instructs the service to find [appLabel] in the battery list and complete the full
     * MagicOS dialog flow for [targetPackage].
     *
     * [appLabel] is the user-visible application name (e.g. "WhatsApp") used to locate
     * the correct row in HwPowerManagerActivity before the system internally navigates
     * to DetailOfSoftConsumptionActivity.
     */
    data class ClickToggleForPackage(
        val targetPackage: String,
        val appLabel: String,
    ) : ServiceCommand

    data class VerifyToggleOff(val targetPackage: String) : ServiceCommand
    data object GlobalBack : ServiceCommand
    data object DisableSelf : ServiceCommand
}
