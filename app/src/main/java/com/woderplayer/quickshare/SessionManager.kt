package com.woderplayer.quickshare

/**
 * State of the current Xender-style transfer session. Once two phones are
 * connected (one QR scan), the session stays alive so EITHER side can send
 * and receive without re-scanning or reconnecting, until it is cleared.
 */
object SessionManager {
    @Volatile var active: Boolean = false
    @Volatile var peerIp: String? = null
    @Volatile var isHotspotOwner: Boolean = false
    @Volatile var onPeerConnected: ((String) -> Unit)? = null

    fun startSession(peerIp: String, isHotspotOwner: Boolean) {
        this.peerIp = peerIp
        this.isHotspotOwner = isHotspotOwner
        this.active = true
    }

    fun clear() {
        active = false
        peerIp = null
        isHotspotOwner = false
    }
}
