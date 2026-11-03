package com.tulipskun.aixodia.ui.settings

import com.tulipskun.aixodia.data.model.ProviderStatus

/** Which providers the Settings list and the provider picker show. */
enum class ProviderFilter { ALL, WORKING, BROKEN, UNTESTED }

/** How many provider cards the Settings list renders at once. */
const val PROVIDER_PAGE = 12

private fun ProviderStatus.tested(probedIds: Set<String>) = probed || id in probedIds

/** Pure so the rules for a long list can be tested on the JVM. */
fun filterProviders(
    providers: List<ProviderStatus>,
    probedIds: Set<String>,
    query: String,
    filter: ProviderFilter,
): List<ProviderStatus> {
    val needle = query.trim()
    return providers
        .asSequence()
        .filter { p ->
            when (filter) {
                ProviderFilter.ALL -> true
                ProviderFilter.WORKING -> p.reachable && p.tested(probedIds)
                ProviderFilter.BROKEN -> !p.reachable && p.tested(probedIds)
                ProviderFilter.UNTESTED -> !p.tested(probedIds)
            }
        }
        .filter { p ->
            needle.isEmpty() ||
                p.id.contains(needle, ignoreCase = true) ||
                p.adapter.contains(needle, ignoreCase = true) ||
                p.endpoint.contains(needle, ignoreCase = true)
        }
        // Working first, then untested (they still need a test), then broken; names sort inside each group.
        .sortedWith(compareBy<ProviderStatus> { rank(it, probedIds) }.thenBy { it.id.lowercase() })
        .toList()
}

private fun rank(p: ProviderStatus, probedIds: Set<String>): Int = when {
    p.reachable && p.tested(probedIds) -> 0
    !p.tested(probedIds) -> 1
    else -> 2
}

/** Counts for the filter chips, so every chip shows how many it would match. */
data class ProviderCounts(val all: Int, val working: Int, val broken: Int, val untested: Int)

fun countProviders(providers: List<ProviderStatus>, probedIds: Set<String>): ProviderCounts {
    var working = 0
    var broken = 0
    var untested = 0
    for (p in providers) {
        when {
            p.reachable && p.tested(probedIds) -> working++
            !p.tested(probedIds) -> untested++
            else -> broken++
        }
    }
    return ProviderCounts(providers.size, working, broken, untested)
}
