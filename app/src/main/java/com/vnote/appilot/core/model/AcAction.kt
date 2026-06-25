package com.vnote.appilot.core.model

import kotlinx.serialization.Serializable

/**
 * An action a tap target can perform on the AC remote screen.
 *
 * Sealed (not a plain enum) so [Custom] can carry a user-supplied name while the
 * common actions stay singletons. Callers can [when] exhaustively without `else`.
 */
@Serializable
sealed interface AcAction {

    /** Lower the setpoint by one step. */
    @Serializable
    data object TEMP_DOWN : AcAction

    /** Raise the setpoint by one step. */
    @Serializable
    data object TEMP_UP : AcAction

    /** Toggle swing / oscillation. */
    @Serializable
    data object SWING : AcAction

    /** Escape hatch for any user-named action not covered above. */
    @Serializable
    data class Custom(val name: String) : AcAction
}
