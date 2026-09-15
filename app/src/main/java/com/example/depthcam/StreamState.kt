package com.example.depthcam

/**
 * Shared live streaming state: written by the activity and the streamer's
 * background threads, read by the settings page on every draw.
 */
class StreamState {

    // STREAM VIDEO: 0 = off, 1 = streaming the depth view to a relay server.
    @Volatile var streamMode = 0
    @Volatile var streamUrl = ""        // relay server host[:port]; persisted
    @Volatile var streamKey = ""        // stream ID on the server; persisted
    @Volatile var streamUrlOk = false   // server connection verified
    @Volatile var streamKeyOk = false   // key accepted (no concurrent stream)
    @Volatile var streamKeyBusy = false // a concurrent stream holds the key

    // Server-issued device validation key: lets a restarted app reclaim its
    // stream key from a stale session. Persisted, never shown in any UI.
    @Volatile var streamToken = ""
}
