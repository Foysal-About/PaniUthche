package com.paniuthche.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayTest {
    private val msg = SosMessage(id = "a", people = 3, elderly = true, children = false, pregnant = false, note = "")

    @Test fun newMessageForwardedWithHopIncrement() = assertEquals(1, relayCopy(msg, emptySet())?.hops)
    @Test fun duplicateDropped() = assertNull(relayCopy(msg, setOf("a")))
    @Test fun stopsAtMaxHops() = assertNull(relayCopy(msg.copy(hops = MAX_HOPS), emptySet()))
    @Test fun ownMessageStartsAtZeroHops() = assertEquals(0, relayCopy(msg.copy(hops = -1), emptySet())?.hops)
}
