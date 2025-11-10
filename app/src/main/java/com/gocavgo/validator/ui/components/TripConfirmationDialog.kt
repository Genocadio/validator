package com.gocavgo.validator.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gocavgo.validator.dataclass.TripResponse

/**
 * Data class for trip confirmation dialog
 */
data class TripConfirmationData(
    val trip: TripResponse,
    val expectedDepartureTime: String,
    val currentTime: String,
    val delayMinutes: Int
)

/**
 * Dialog shown when a trip's departure time has passed
 * Asks user to confirm whether to start navigation or cancel
 */
@Composable
fun TripConfirmationDialog(
    data: TripConfirmationData,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* Non-dismissible */ },
        title = {
            Text(
                text = "Trip Departure Time Passed",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Route information
                Text(
                    text = "Route",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${data.trip.route.origin.custom_name ?: data.trip.route.origin.google_place_name} → ${data.trip.route.destination.custom_name ?: data.trip.route.destination.google_place_name}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                
                // Time information
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Expected Departure",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = data.expectedDepartureTime,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    
                    Column {
                        Text(
                            text = "Current Time",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = data.currentTime,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                
                // Delay indicator
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = if (data.delayMinutes >= 60) {
                            "Delay: ${data.delayMinutes / 60}h ${data.delayMinutes % 60}min"
                        } else {
                            "Delay: ${data.delayMinutes} minutes"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                
                // Auto-start warning
                Text(
                    text = "Navigation will auto-start in 3 minutes if no action is taken.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Start Navigation")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel Trip")
            }
        }
    )
}



















