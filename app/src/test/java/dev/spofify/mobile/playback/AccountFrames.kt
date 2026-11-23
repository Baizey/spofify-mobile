package dev.spofify.mobile.playback

/** Local v3 socket input to the shipped AccountChannel. A follower gets proof only from observation. */
internal fun accountSnapshot(
    connection: String,
    local: Boolean,
    nowMs: Long = System.currentTimeMillis(),
    generation: Long = 1,
    revision: Long = 1,
    occurrence: String = "visit",
): String {
    val owner = if (local) "phone" else "remote"
    val observation = if (local) "" else ",\"observation\":{\"key\":1,\"occurrence\":\"$occurrence\",\"positionMs\":0,\"sampledAtMs\":$nowMs,\"status\":\"playing\"}"
    return """{"type":"snapshot","sessionId":"room","connectionId":"$connection","revision":$revision,"owner":{"generation":$generation,"deviceId":"$owner"},"devices":[{"deviceId":"phone","name":"Phone","canPlay":true},{"deviceId":"remote","name":"Remote","canPlay":true}],"player":{"queue":[{"id":"track","name":"Song","artists":[],"albumId":"album","albumName":"Album","duration":120,"key":1,"order":1,"intent":"low"}],"cursor":{"key":1,"occurrence":"$occurrence","positionMs":0,"desiredPlaying":true},"repeat":"off","shuffle":false}$observation,"serverTimeMs":$nowMs}"""
}
