package com.lucid.auto.appstore.domain

/**
 * The state shown on each row's action control.
 *
 * Exactly the three states in the product requirement — there are deliberately no
 * "downloading", "installing" or "failed" states here. Work in flight is a *presentation*
 * concern carried by [AppItem.isBusy], and failures are surfaced outside the list, so a row can
 * never render anything other than Install, Update or Installed.
 */
enum class AppState {
    /** Not present on the device, but available from the catalogue. Tapping installs it. */
    INSTALL,

    /** Installed, and the catalogue offers a strictly higher versionCode. Tapping updates it. */
    UPDATE,

    /** Installed and current (or not in the catalogue at all). Tapping does nothing. */
    INSTALLED,
    ;

    /**
     * Whether this state is one the user can act on at all.
     *
     * Requirement FR-4.4: "tapping on installed will not do anything".
     *
     * Note this is the *state's* capability, not the row's: a row that is currently busy is also
     * non-interactive. Use [AppItem.isActionable] for the row-level answer.
     */
    val isActionable: Boolean
        get() = this == INSTALL || this == UPDATE
}
