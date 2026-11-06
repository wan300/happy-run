package com.lepao.companion.core

import java.net.Inet4Address
import java.net.NetworkInterface

object Net {
    /** The current LAN address, used only for the manifest's `ip` observation field. */
    fun localIpv4(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            val addresses = interfaces
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
            val siteLocal = addresses.firstOrNull { it.isSiteLocalAddress }
            (siteLocal ?: addresses.firstOrNull())?.hostAddress ?: "192.168.1.2"
        } catch (_: Exception) {
            "192.168.1.2"
        }
    }
}
