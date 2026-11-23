package com.biomeria.launcher.data.accounts

internal enum class JavaAccountMode(val label: String, val sessionUserType: String) {
    OFFLINE("Offline", "legacy"),
    MICROSOFT("Microsoft", "msa"),
    ELY_BY("Ely.by", "mojang");

    companion object {
        fun fromProfile(value: String): JavaAccountMode = entries.firstOrNull { it.label.equals(value, ignoreCase = true) } ?: OFFLINE
    }
}

internal data class JavaAccountIdentity(val playerName: String, val profileId: String)
