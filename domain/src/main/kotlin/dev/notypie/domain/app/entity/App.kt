package dev.notypie.domain.app.entity

class App(
    val appId: String,
    val appName: String,
    val appToken: String,

    val tracking: Boolean = true,
    val isAvailable: Boolean = false
) {

}
