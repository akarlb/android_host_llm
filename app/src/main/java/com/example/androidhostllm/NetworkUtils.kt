package com.example.androidhostllm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class ModelDirectoryInfo(
    val directory: File,
    val usesInternalFallback: Boolean,
)

enum class ActiveNetworkType(val label: String) {
    WIFI("Wi-Fi"),
    MOBILE("mobile"),
    NONE("none"),
    UNKNOWN("unknown"),
}

object NetworkUtils {
    fun modelDirectory(context: Context): ModelDirectoryInfo {
        val external = context.applicationContext.getExternalFilesDir(null)
        val base = external ?: context.filesDir
        val directory = File(base, "models")
        directory.mkdirs()
        return ModelDirectoryInfo(directory, external == null)
    }

    fun availableBytes(path: File): Long = runCatching {
        val target = if (path.exists()) path else path.parentFile ?: path
        val statFs = StatFs(target.absolutePath)
        statFs.availableBytes
    }.getOrDefault(0L)

    fun activeNetworkType(context: Context): ActiveNetworkType {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return ActiveNetworkType.UNKNOWN
        val network = cm.activeNetwork ?: return ActiveNetworkType.NONE
        val caps = cm.getNetworkCapabilities(network) ?: return ActiveNetworkType.UNKNOWN
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ActiveNetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ActiveNetworkType.MOBILE
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> ActiveNetworkType.UNKNOWN
            else -> ActiveNetworkType.NONE
        }
    }

    fun lanIpv4Candidates(): List<String> {
        val preferred = mutableListOf<Pair<String, String>>()
        val others = mutableListOf<Pair<String, String>>()
        val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrElse { emptyList() }
        for (networkInterface in interfaces) {
            if (!runCatching { networkInterface.isUp && !networkInterface.isLoopback }.getOrDefault(false)) continue
            val name = networkInterface.name.orEmpty()
            val addresses = runCatching { Collections.list(networkInterface.inetAddresses) }.getOrElse { emptyList() }
            for (address in addresses) {
                if (address !is Inet4Address || address.isLoopbackAddress) continue
                val hostAddress = address.hostAddress ?: continue
                if (hostAddress == "127.0.0.1") continue
                val entry = name to hostAddress
                if (isPreferredInterface(name)) preferred += entry else others += entry
            }
        }
        return (preferred + others).map { it.second }.distinct()
    }

    private fun isPreferredInterface(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("wlan") || lower.startsWith("wifi") || lower.startsWith("ap") || lower.startsWith("swlan")
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mib = bytes / (1024.0 * 1024.0)
        return if (mib >= 1024.0) String.format("%.2f GB", mib / 1024.0) else String.format("%.0f MB", mib)
    }
}
