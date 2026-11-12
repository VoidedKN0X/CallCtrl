package com.vknxworks.callctrl.ui.nav

import kotlinx.serialization.Serializable

// Tabs
@Serializable data object KeypadRoute
@Serializable data object RecentsRoute
@Serializable data object PeopleRoute

// Opened from the profile picture: the You page, and Settings from there
@Serializable data object YouRoute
@Serializable data object SettingsRoute
@Serializable data object BlockedRoute
