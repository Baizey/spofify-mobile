package dev.spofify.mobile.playback

/** Socket input to the shipped channel; never model a remote sound with local audio. */
internal fun accountSnapshot(connection: String, local: Boolean): String {
    val owner = if (local) connection else "remote-connection"
    return """{"type":"snapshot","connectionId":"$connection","owner":"$owner","devices":[{"id":"$connection","deviceId":"phone","name":"Phone","canPlay":true},{"id":"remote-connection","deviceId":"remote","name":"Remote","canPlay":true}],"player":{"queue":[{"id":"track","name":"Song","artists":[],"albumId":"album","albumName":"Album","duration":120,"key":1,"order":1,"intent":"low"}],"cursor":{"key":1,"chain":"visit","desired":true,"playing":${!local}},"repeat":"off","shuffle":false}}"""
}
