package com.humblesolutions.ainotetaker.navigation

/**
 * Every destination, as one sealed hierarchy.
 *
 * Route strings rather than typed arguments because a registration in progress
 * has to survive process death, and a URL-shaped route is what the navigation
 * library can restore from a saved bundle.
 */
sealed class Route(val path: String) {
    data object Notetaker : Route("notetaker")
    data object Profile : Route("profile")

    // The person's meetings, and one meeting's summary.
    data object Meetings : Route("meetings")
    data object Meeting : Route("meeting/{id}") {
        fun of(id: String) = "meeting/$id"
    }
}
