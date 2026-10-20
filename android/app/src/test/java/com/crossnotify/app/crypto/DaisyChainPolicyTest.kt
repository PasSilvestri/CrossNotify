package com.crossnotify.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DaisyChainPolicyTest {

    @Test
    fun `increments hops, appends target channel to path, and preserves origin id`() {
        val initial = NotificationPlaintext(
            id = "notif-hop-0",
            pkg = "com.whatsapp",
            app = "WhatsApp",
            title = "Alice",
            text = "Hello",
            postedAt = 1792014058000L,
            origin = OriginInfo(
                id = "orig-uuid-1",
                hops = 0,
                path = listOf("channel-A")
            )
        )

        val hop1 = DaisyChainPolicy.prepareForwardedPayload(initial, "channel-B", "notif-hop-1")
        assertNotNull(hop1)
        assertEquals("notif-hop-1", hop1!!.id)
        assertEquals("orig-uuid-1", hop1.origin.id)
        assertEquals(1, hop1.origin.hops)
        assertEquals(listOf("channel-A", "channel-B"), hop1.origin.path)

        val hop2 = DaisyChainPolicy.prepareForwardedPayload(hop1, "channel-C", "notif-hop-2")
        assertNotNull(hop2)
        assertEquals(2, hop2!!.origin.hops)
        assertEquals(listOf("channel-A", "channel-B", "channel-C"), hop2.origin.path)

        val hop3 = DaisyChainPolicy.prepareForwardedPayload(hop2, "channel-D", "notif-hop-3")
        assertNotNull(hop3)
        assertEquals(3, hop3!!.origin.hops)

        // Hop 4 must be dropped (hops >= 3)
        val hop4 = DaisyChainPolicy.prepareForwardedPayload(hop3, "channel-E", "notif-hop-4")
        assertNull(hop4)
    }

    @Test
    fun `drops forwarding if target channel is already in path`() {
        val incoming = NotificationPlaintext(
            id = "notif-1",
            pkg = "com.whatsapp",
            app = "WhatsApp",
            title = "Alice",
            text = "Loop check",
            postedAt = 1792014058000L,
            origin = OriginInfo(
                id = "orig-loop",
                hops = 1,
                path = listOf("channel-A", "channel-B")
            )
        )

        // Forwarding back to channel-A must be blocked
        assertNull(DaisyChainPolicy.prepareForwardedPayload(incoming, "channel-A"))
        // Forwarding back to channel-B must be blocked
        assertNull(DaisyChainPolicy.prepareForwardedPayload(incoming, "channel-B"))
    }
}
