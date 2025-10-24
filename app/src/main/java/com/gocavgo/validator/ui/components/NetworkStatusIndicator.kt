package com.gocavgo.validator.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Network status indicator that shows a colored dot based on connection quality
 * Similar to iOS camera indicator - positioned in top-right corner
 */
@Composable
fun NetworkStatusIndicator(
    isConnected: Boolean,
    connectionType: String,
    isMetered: Boolean,
    modifier: Modifier = Modifier
) {
    val networkStatus = determineNetworkStatus(isConnected, connectionType, isMetered)
    
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopEnd
    ) {
        AnimatedVisibility(
            visible = networkStatus != NetworkStatus.GOOD,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300)),
            modifier = Modifier
                .padding(top = 16.dp, end = 16.dp)
                .size(10.dp)
                .background(
                    color = when (networkStatus) {
                        NetworkStatus.OFFLINE -> Color(0xFFFF0000) // Red
                        NetworkStatus.WEAK -> Color(0xFFFF9800)    // Orange
                        NetworkStatus.GOOD -> Color.Transparent   // Hidden
                    },
                    shape = CircleShape
                )
        ) {
            // Empty content - the background circle is the indicator
        }
    }
}

/**
 * Determines the network status based on connection parameters
 */
private fun determineNetworkStatus(
    isConnected: Boolean,
    connectionType: String,
    isMetered: Boolean
): NetworkStatus {
    return when {
        !isConnected -> NetworkStatus.OFFLINE
        isWeakConnection(connectionType, isMetered) -> NetworkStatus.WEAK
        else -> NetworkStatus.GOOD
    }
}

/**
 * Checks if the connection is considered weak
 * Weak = metered AND slow connection types (2G, 3G, EDGE, GPRS, etc.)
 */
private fun isWeakConnection(connectionType: String, isMetered: Boolean): Boolean {
    val weakTypes = listOf("EDGE", "GPRS", "1xRTT", "CDMA", "2G", "3G")
    return isMetered && weakTypes.any { connectionType.contains(it, ignoreCase = true) }
}

/**
 * Network status enumeration
 */
enum class NetworkStatus {
    OFFLINE,      // Red - !isConnected
    WEAK,         // Orange - isMetered && (EDGE, GPRS, 2G, 3G, 1xRTT, CDMA)
    GOOD          // Hidden - Everything else
}




