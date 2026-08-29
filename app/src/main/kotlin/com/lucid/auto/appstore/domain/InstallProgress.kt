package com.lucid.auto.appstore.domain

/**
 * Transient per-package work state, shown as short status text on the row's version line.
 *
 * [Failed] deliberately carries **no message**. The row is allowed to say *that* something failed;
 * it is not allowed to say why. The reason travels back through [UpdateCoordinator]'s return
 * value and is reported above the list, so no error text can reach a row even by accident.
 */
sealed interface InstallProgress {
    data class Downloading(val percent: Int) : InstallProgress
    data object Installing : InstallProgress
    data object Failed : InstallProgress

    /** Whether this state means work is actively running (so the control must be inert). */
    val isBusy: Boolean
        get() = this is Downloading || this is Installing
}
