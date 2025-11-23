package com.gocavgo.validator.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import com.gocavgo.validator.util.Logging
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat

/**
 * Utility class for network-related operations and permissions
 */
object NetworkUtils {

private const val TAG = "NetworkUtils"

/**
 * Check if the app has necessary network permissions
 */
fun hasNetworkPermissions(context: Context): Boolean {
    val hasInternet = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.INTERNET
    ) == PackageManager.PERMISSION_GRANTED

    val hasNetworkState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_NETWORK_STATE
    ) == PackageManager.PERMISSION_GRANTED

    return hasInternet && hasNetworkState
}

/**
 * Get required permissions for network monitoring
 */
fun getRequiredPermissions(): Array<String> {
    return arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
    )
}

/**
 * Get optional permissions for enhanced network monitoring
 */
fun getOptionalPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION, // For cell tower location
                Manifest.permission.READ_PHONE_STATE      // For detailed cellular info
        )
    } else {
        arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.READ_PHONE_STATE
        )
    }
}

/**
 * Get detailed network information for debugging
 */
@SuppressLint("MissingPermission")
fun getDetailedNetworkInfo(context: Context): NetworkDebugInfo {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val activeNetwork = connectivityManager.activeNetwork
    val networkCapabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

    return NetworkDebugInfo(
            hasActiveNetwork = activeNetwork != null,
            hasInternet = networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false,
            hasValidated = networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false,
            isMetered = connectivityManager.isActiveNetworkMetered,
            transportTypes = getTransportTypes(networkCapabilities),
            linkDownstreamBandwidth = networkCapabilities?.linkDownstreamBandwidthKbps ?: 0,
            linkUpstreamBandwidth = networkCapabilities?.linkUpstreamBandwidthKbps ?: 0,
            cellularInfo = getCellularInfo(context),
            wifiInfo = getWifiInfo(context)
        )
}

private fun getTransportTypes(networkCapabilities: NetworkCapabilities?): List<String> {
    if (networkCapabilities == null) return emptyList()

    val transports = mutableListOf<String>()

    if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
        transports.add("WIFI")
    }
    if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
        transports.add("CELLULAR")
    }
    if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
        transports.add("ETHERNET")
    }
    if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
        transports.add("BLUETOOTH")
    }
    if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
        transports.add("VPN")
    }

    return transports
}

@RequiresPermission(Manifest.permission.READ_PHONE_STATE)
private fun getCellularInfo(context: Context): CellularInfo {
    try {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
        ) != PackageManager.PERMISSION_GRANTED) {
            return CellularInfo(hasPermission = false)
        }

        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager?
                ?: return CellularInfo(hasPermission = true, isAvailable = false)

        return CellularInfo(
                hasPermission = true,
                isAvailable = true,
                networkOperator = telephonyManager.networkOperatorName,
                networkType = getNetworkTypeString(telephonyManager.networkType),
                isRoaming = telephonyManager.isNetworkRoaming,
                simState = getSimStateString(telephonyManager.simState)
        )
    } catch (e: SecurityException) {
        Logging.w(TAG, "Security exception getting cellular info: ${e.message}")
        return CellularInfo(hasPermission = false)
    } catch (e: Exception) {
        Logging.w(TAG, "Error getting cellular info: ${e.message}")
        return CellularInfo(hasPermission = true, isAvailable = false)
    }
}

private fun getWifiInfo(context: Context): WifiInfo {
    // Note: Getting detailed WiFi info requires location permissions on newer Android versions
    return try {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

        WifiInfo(
                isAvailable = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                isConnected = networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                signalStrength = getWifiSignalStrength(networkCapabilities)
            )
    } catch (e: Exception) {
        Logging.w(TAG, "Error getting WiFi info: ${e.message}")
        WifiInfo(isAvailable = false, isConnected = false, signalStrength = 0)
    }
}

private fun getWifiSignalStrength(networkCapabilities: NetworkCapabilities?): Int {
    return try {
        if (networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            // Signal strength is not directly available through NetworkCapabilities
            // Return estimated strength based on capabilities
            when {
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> 4
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> 3
                    else -> 2
            }
        } else {
            0
        }
    } catch (e: Exception) {
        0
    }
}

private fun getNetworkTypeString(networkType: Int): String {
    return when (networkType) {
        TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
        TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_EHRPD -> "eHRPD"
        TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO rev. 0"
        TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO rev. A"
        TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO rev. B"
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
        TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
        TelephonyManager.NETWORK_TYPE_IDEN -> "iDen"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "Unknown"
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (networkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
                    else -> "Unknown ($networkType)"
            }
        } else {
            "Unknown ($networkType)"
        }
    }
}

private fun getSimStateString(simState: Int): String {
    return when (simState) {
        TelephonyManager.SIM_STATE_ABSENT -> "No SIM card"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Network locked"
        TelephonyManager.SIM_STATE_READY -> "Ready"
        TelephonyManager.SIM_STATE_NOT_READY -> "Not ready"
        TelephonyManager.SIM_STATE_PERM_DISABLED -> "Permanently disabled"
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "Card IO error"
        TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "Card restricted"
            else -> "Unknown"
    }
}

data class NetworkDebugInfo(
        val hasActiveNetwork: Boolean,
        val hasInternet: Boolean,
        val hasValidated: Boolean,
        val isMetered: Boolean,
        val transportTypes: List<String>,
        val linkDownstreamBandwidth: Int,
        val linkUpstreamBandwidth: Int,
        val cellularInfo: CellularInfo,
        val wifiInfo: WifiInfo
)

    data class CellularInfo(
        val hasPermission: Boolean,
        val isAvailable: Boolean = false,
        val networkOperator: String = "",
        val networkType: String = "",
        val isRoaming: Boolean = false,
        val simState: String = ""
)

    data class WifiInfo(
        val isAvailable: Boolean,
        val isConnected: Boolean = false,
        val signalStrength: Int = 0 // 0-4 scale
)

        /**
         * Create a comprehensive network report for debugging
         */
        @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
        fun createNetworkReport(context: Context): String {
    if (!hasNetworkPermissions(context)) {
        return "Network permissions not granted. Required: ${getRequiredPermissions().joinToString()}"
    }

    val debugInfo = getDetailedNetworkInfo(context)

    return buildString {
        appendLine("=== NETWORK DEBUG REPORT ===")
        appendLine("Has Active Network: ${debugInfo.hasActiveNetwork}")
        appendLine("Has Internet: ${debugInfo.hasInternet}")
        appendLine("Is Validated: ${debugInfo.hasValidated}")
        appendLine("Is Metered: ${debugInfo.isMetered}")
        appendLine("Transport Types: ${debugInfo.transportTypes.joinToString(", ")}")
        appendLine("Downstream BW: ${debugInfo.linkDownstreamBandwidth} Kbps")
        appendLine("Upstream BW: ${debugInfo.linkUpstreamBandwidth} Kbps")
        appendLine()
        appendLine("=== CELLULAR INFO ===")
        if (debugInfo.cellularInfo.hasPermission) {
            if (debugInfo.cellularInfo.isAvailable) {
                appendLine("Operator: ${debugInfo.cellularInfo.networkOperator}")
                appendLine("Network Type: ${debugInfo.cellularInfo.networkType}")
                appendLine("Is Roaming: ${debugInfo.cellularInfo.isRoaming}")
                appendLine("SIM State: ${debugInfo.cellularInfo.simState}")
            } else {
                appendLine("Cellular network not available")
            }
        } else {
            appendLine("READ_PHONE_STATE permission not granted")
        }
        appendLine()
        appendLine("=== WIFI INFO ===")
        appendLine("Available: ${debugInfo.wifiInfo.isAvailable}")
        appendLine("Connected: ${debugInfo.wifiInfo.isConnected}")
        appendLine("Signal Strength: ${debugInfo.wifiInfo.signalStrength}/4")
        appendLine("============================")
    }
}

/**
 * Simple connectivity test without detailed analysis
 */
fun isConnectedToInternet(context: Context): Boolean {
    if (!hasNetworkPermissions(context)) {
        return false
    }

    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val activeNetwork = connectivityManager.activeNetwork ?: return false
    val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

    return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/**
 * Check if current connection is metered (expensive)
 */
fun isConnectionMetered(context: Context): Boolean {
    if (!hasNetworkPermissions(context)) {
        return true // Assume metered if we can't check
    }

    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return connectivityManager.isActiveNetworkMetered
}

/**
 * Check if device is roaming
 */
@RequiresApi(Build.VERSION_CODES.P)
fun isRoaming(context: Context): Boolean {
    if (!hasNetworkPermissions(context)) {
        return false
    }

    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val activeNetwork = connectivityManager.activeNetwork ?: return false
    val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

    return !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
}

/**
 * Check if the connection is considered weak
 * Weak = metered AND slow connection types (2G, 3G, EDGE, GPRS, etc.)
 */
fun isWeakConnection(connectionType: String, isMetered: Boolean): Boolean {
    val weakTypes = listOf("EDGE", "GPRS", "1xRTT", "CDMA", "2G", "3G")
    return isMetered && weakTypes.any { connectionType.contains(it, ignoreCase = true) }
}
}