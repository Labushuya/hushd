// SPDX-License-Identifier: GPL-3.0-or-later
package dev.labushuya.hushd.core.automation

import kotlinx.coroutines.channels.Channel

/**
 * Schmales Interface, das der A11y-Service implementiert.
 * Dadurch hängt [BulkAutostopEngine] nicht am konkreten Service-Typ
 * (welcher im `service:accessibility`-Modul lebt) und es entsteht keine
 * zirkuläre Module-Dependency.
 */
interface A11yServiceHandle {
    val events: Channel<NodeEvent>
}
