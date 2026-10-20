package com.crossnotify.app.crypto

import java.util.UUID

/**
 * Manages forwarding rules and loop prevention when daisy-chaining notifications across channels:
 * - Drops payloads if `hops` exceed [MAX_HOPS] or if the target channel already appears in `path`.
 * - Otherwise increments `hops`, records the outgoing channel in `path`, preserves the original
 *   `origin.id` for deduplication, and assigns a new message ID for the next transmission.
 */
object DaisyChainPolicy {
    const val MAX_HOPS = 3

    fun canForward(origin: OriginInfo, targetRelayChannelId: String): Boolean {
        if (targetRelayChannelId.isBlank()) return false
        if (origin.hops >= MAX_HOPS) return false
        if (targetRelayChannelId in origin.path) return false
        return true
    }

    fun prepareForwardedPayload(
        incoming: NotificationPlaintext,
        targetRelayChannelId: String,
        newNotificationId: String = UUID.randomUUID().toString()
    ): NotificationPlaintext? {
        if (!canForward(incoming.origin, targetRelayChannelId)) {
            return null
        }
        val nextOrigin = OriginInfo(
            id = incoming.origin.id.ifBlank { incoming.id },
            hops = incoming.origin.hops + 1,
            path = incoming.origin.path + targetRelayChannelId
        )
        return incoming.copy(
            id = newNotificationId,
            origin = nextOrigin
        )
    }
}
