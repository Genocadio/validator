package com.gocavgo.validator.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gocavgo.validator.navigator.TripSectionValidator

/**
 * Shared composable for waypoint progress overlay
 * Used across HeadlessNavigActivity, NavigActivity, and AutoModeHeadlessActivity
 * @param progressData List of waypoint progress information
 * @param onClose Optional callback to close the overlay (for AutoModeHeadlessActivity)
 * @param isTopCenter If true, positions overlay at top center (for AutoModeHeadlessActivity), otherwise top end
 */
@Composable
fun WaypointProgressOverlay(
    progressData: List<TripSectionValidator.WaypointProgressInfo>,
    onClose: (() -> Unit)? = null,
    isTopCenter: Boolean = false
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = if (isTopCenter) Alignment.TopCenter else Alignment.TopEnd
    ) {
        Card(
            modifier = Modifier
                .width(300.dp)
                .heightIn(max = 400.dp)
                .padding(top = if (isTopCenter) 100.dp else 80.dp)
                .zIndex(1000f), // High z-index to ensure it's on top
            elevation = CardDefaults.cardElevation(
                defaultElevation = if (isTopCenter) 12.dp else 4.dp // Higher elevation for floating dialog
            ),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isTopCenter) 
                    Color.White // Fully opaque white background for high contrast in AutoMode
                else 
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                // Header with title and close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Waypoint Progress",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isTopCenter) 
                            Color.Black // High contrast for AutoMode
                        else 
                            MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        fontWeight = if (isTopCenter) FontWeight.Bold else FontWeight.Normal
                    )
                    
                    // Close button (only show if onClose callback is provided)
                    if (onClose != null) {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = if (isTopCenter) 
                                    Color.Black // High contrast for AutoMode
                                else 
                                    MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f) // Allow scrolling within the container
                ) {
                    items(progressData) { waypoint ->
                        WaypointProgressItem(waypoint, isTopCenter)
                    }
                }
            }
        }
    }
}

/**
 * Individual waypoint progress item
 * Used within WaypointProgressOverlay
 */
@Composable
fun WaypointProgressItem(
    waypoint: TripSectionValidator.WaypointProgressInfo,
    isTopCenter: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = when {
                    waypoint.isPassed -> Color(0xFF4CAF50).copy(alpha = 0.2f)
                    waypoint.isNext -> Color(0xFF2196F3).copy(alpha = 0.2f)
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(4.dp)
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status indicator
        Box(
            modifier = Modifier
                .width(12.dp)
                .height(12.dp)
                .background(
                    color = when {
                        waypoint.isPassed -> Color(0xFF4CAF50)
                        waypoint.isNext -> Color(0xFF2196F3)
                        else -> Color.Gray
                    },
                    shape = androidx.compose.foundation.shape.CircleShape
                )
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = waypoint.waypointName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isTopCenter) 
                    Color.Black // High contrast for AutoMode
                else 
                    MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isTopCenter) FontWeight.SemiBold else FontWeight.Normal
            )
            
            if (waypoint.isPassed && waypoint.passedTimestamp != null) {
                val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                val formattedTime = timeFormat.format(waypoint.passedTimestamp)
                Text(
                    text = "Passed at: $formattedTime",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isTopCenter) 
                        Color.Black.copy(alpha = 0.7f) // High contrast for AutoMode
                    else 
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            } else if (!waypoint.isPassed) {
                // Show remaining time and distance for unpassed waypoints
                val remainingInfo = mutableListOf<String>()
                
                waypoint.remainingTimeInSeconds?.let { timeInSeconds ->
                    val minutes = timeInSeconds / 60
                    val seconds = timeInSeconds % 60
                    remainingInfo.add("${minutes}m ${seconds}s")
                }
                
                waypoint.remainingDistanceInMeters?.let { distanceInMeters ->
                    val distanceKm = distanceInMeters / 1000.0
                    if (distanceKm >= 1.0) {
                        remainingInfo.add(String.format("%.1f km", distanceKm))
                    } else {
                        remainingInfo.add("${distanceInMeters.toInt()}m")
                    }
                }
                
                if (remainingInfo.isNotEmpty()) {
                    Text(
                        text = remainingInfo.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (waypoint.isNext) 
                            Color(0xFF2196F3) 
                        else if (isTopCenter) 
                            Color.Black.copy(alpha = 0.7f) // High contrast for AutoMode
                        else 
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontWeight = if (waypoint.isNext && isTopCenter) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
                
                if (waypoint.isNext) {
                    Text(
                        text = "Next",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF2196F3),
                        fontWeight = if (isTopCenter) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

