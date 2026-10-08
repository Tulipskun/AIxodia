package com.tulipskun.aixodia.ui.settings

import com.tulipskun.aixodia.data.model.ProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderFilterTest {
    private fun p(id: String, reachable: Boolean = false, probed: Boolean = false, endpoint: String = "https://$id.example") =
        ProviderStatus(id = id, adapter = "openai", endpoint = endpoint, reachable = reachable, probed = probed)

    private val fleet = listOf(
        p("zeta", reachable = true, probed = true),
        p("alpha", reachable = false, probed = true),
        p("mid"),
        p("beta", reachable = true, probed = true),
        p("omega", reachable = true, probed = true, endpoint = "https://gateway.example"),
    )

    @Test fun workingComesFirstThenBrokenThenUntested() {
        val ids = filterProviders(fleet, emptySet(), "", ProviderFilter.ALL).map { it.id }
        assertEquals(listOf("beta", "omega", "zeta", "mid", "alpha"), ids)
    }

    @Test fun filtersByStatus() {
        assertEquals(listOf("alpha"), filterProviders(fleet, emptySet(), "", ProviderFilter.BROKEN).map { it.id })
        assertEquals(listOf("mid"), filterProviders(fleet, emptySet(), "", ProviderFilter.UNTESTED).map { it.id })
    }

    @Test fun searchMatchesNameAndEndpoint() {
        assertEquals(listOf("omega"), filterProviders(fleet, emptySet(), "gateway", ProviderFilter.ALL).map { it.id })
        assertEquals(listOf("beta"), filterProviders(fleet, emptySet(), "  BE ", ProviderFilter.ALL).map { it.id })
    }

    @Test fun probedIdsCountAsTested() {
        val counts = countProviders(fleet, setOf("mid"))
        assertEquals(5, counts.all)
        assertEquals(3, counts.working)
        assertEquals(2, counts.broken)
        assertEquals(0, counts.untested)
    }

    @Test fun hundredProvidersStayFiltrable() {
        val many = (1..100).map { p("provider-%03d".format(it), reachable = it % 4 == 0, probed = true) }
        val working = filterProviders(many, emptySet(), "", ProviderFilter.WORKING)
        assertEquals(25, working.size)
        assertEquals("provider-004", working.first().id)
        assertEquals(1, filterProviders(many, emptySet(), "provider-077", ProviderFilter.ALL).size)
    }
}
