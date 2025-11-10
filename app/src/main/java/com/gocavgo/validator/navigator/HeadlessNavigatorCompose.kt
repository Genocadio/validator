package com.gocavgo.validator.navigator

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gocavgo.validator.dataclass.SavePlaceResponse
import com.gocavgo.validator.booking.BookingNfcManager
import com.gocavgo.validator.ui.components.NetworkStatusIndicator
import com.gocavgo.validator.ui.components.TripConfirmationData
import com.gocavgo.validator.ui.components.TripConfirmationDialog
import com.gocavgo.validator.ui.components.WaypointProgressOverlay
import com.gocavgo.validator.navigator.TripSectionValidator

/**
 * Custom numeric keyboard composable for ticket verification
 */
@Composable
fun NumericKeyboard(
    onDigitClick: (String) -> Unit,
    onDeleteClick: () -> Unit,
    onClearClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Number buttons (1-9)
        repeat(3) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                repeat(3) { col ->
                    val number = (row * 3) + col + 1
                    NumberButton(
                        number = number.toString(),
                        onClick = { onDigitClick(number.toString()) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        // Bottom row: 0, Delete, Clear
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            NumberButton(
                number = "0",
                onClick = { onDigitClick("0") }
            )
            ActionButton(
                text = "⌫",
                onClick = onDeleteClick,
                backgroundColor = MaterialTheme.colorScheme.errorContainer
            )
            ActionButton(
                text = "C",
                onClick = onClearClick,
                backgroundColor = MaterialTheme.colorScheme.secondaryContainer
            )
        }
    }
}

@Composable
private fun NumberButton(
    number: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .size(60.dp)
            .clip(CircleShape),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Text(
            text = number,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .size(60.dp)
            .clip(CircleShape),
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor
        )
    ) {
        Text(
            text = text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

/**
 * Ticket input display showing current input and validation status
 */
@Composable
fun TicketInputDisplay(
    currentInput: String,
    isValidationInProgress: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isValidationInProgress -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Enter Ticket Number",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = if (isValidationInProgress) "Validating..." else currentInput,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    isValidationInProgress -> MaterialTheme.colorScheme.onTertiaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(16.dp)
            )
        }
    }
}

/**
 * Destination selection dialog
 */
@Composable
fun DestinationSelectionDialog(
    destinations: List<AvailableDestination>,
    currentLocation: String,
    onDestinationSelected: (AvailableDestination) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Select Destination",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "From: $currentLocation",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    items(destinations) { destination ->
                        DestinationItem(
                            destination = destination,
                            onClick = {
                                onDestinationSelected(destination)
                                onDismiss()
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationItem(
    destination: AvailableDestination,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "${if (destination.isFinalDestination) "🏁" else "🚩"} ${getLocationDisplayName(destination.location)}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (destination.isFinalDestination) "Final Destination" else "Waypoint",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Text(
                text = "${destination.price.toInt()} RWF",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * Success prompt for booking creation
 */
@Composable
fun BookingSuccessPrompt(
    ticketNumber: String,
    fromLocation: String,
    toLocation: String,
    price: String,
    isVisible: Boolean,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(
            initialOffsetY = { -it },
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
        ) + fadeIn(),
        exit = slideOutVertically(
            targetOffsetY = { -it },
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
        ) + fadeOut()
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "✅ BOOKING SUCCESS",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Ticket: $ticketNumber",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                
                Text(
                    text = "$fromLocation → $toLocation",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                
                Text(
                    text = "Price: $price",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * Failure prompt for booking creation
 */
@Composable
fun BookingFailurePrompt(
    errorMessage: String,
    isVisible: Boolean,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(
            initialOffsetY = { -it },
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
        ) + fadeIn(),
        exit = slideOutVertically(
            targetOffsetY = { -it },
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
        ) + fadeOut()
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "❌ BOOKING FAILED",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = errorMessage,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Success prompt for ticket validation
 */
@Composable
fun ValidationSuccessPrompt(
    ticketNumber: String,
    isVisible: Boolean,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = scaleIn(
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
        ) + fadeIn(),
        exit = scaleOut(
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
        ) + fadeOut()
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF4CAF50)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "✓ VALID TICKET",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = ticketNumber,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Failure prompt for ticket validation
 */
@Composable
fun ValidationFailurePrompt(
    errorMessage: String,
    isVisible: Boolean,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = scaleIn(
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
        ) + fadeIn(),
        exit = scaleOut(
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
        ) + fadeOut()
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFF44336)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "❌ INVALID TICKET",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = errorMessage,
                    fontSize = 16.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * MQTT booking notification prompt
 */
@Composable
fun MqttBookingNotification(
    passengerName: String,
    pickup: String,
    dropoff: String,
    numTickets: Int,
    isPaid: Boolean,
    isVisible: Boolean,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(
            initialOffsetY = { -it },
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
        ) + fadeIn(),
        exit = slideOutVertically(
            targetOffsetY = { -it },
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
        ) + fadeOut()
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "🔔 NEW BOOKING",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "Passenger: $passengerName",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                
                Text(
                    text = "$pickup → $dropoff",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                
                Text(
                    text = "$numTickets ticket${if (numTickets > 1) "s" else ""} • ${if (isPaid) "PAID" else "UNPAID"}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isPaid) Color(0xFF4CAF50) else Color(0xFFFF9800)
                )
            }
        }
    }
}

/**
 * Helper function to get location display name
 */
private fun getLocationDisplayName(location: SavePlaceResponse): String {
    return location.custom_name?.takeIf { it.isNotBlank() } ?: location.google_place_name
}

/**
 * Passenger list dialog for showing passengers to pick up or drop off
 */
@Composable
fun PassengerListDialog(
    passengers: List<com.gocavgo.validator.service.BookingService.PassengerInfo>,
    listType: com.gocavgo.validator.navigator.HeadlessNavigActivity.PassengerListType,
    isVisible: Boolean,
    onPassengerClick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!isVisible) return
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (listType == com.gocavgo.validator.navigator.HeadlessNavigActivity.PassengerListType.PICKUP) 
                            "Passengers to Pick Up" 
                        else 
                            "Passengers to Drop Off",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${passengers.size}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (listType == com.gocavgo.validator.navigator.HeadlessNavigActivity.PassengerListType.PICKUP)
                            Color(0xFF4CAF50)
                        else
                            Color(0xFFF44336)
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Scrollable passenger list
                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    items(passengers) { passenger ->
                        PassengerListItem(
                            passenger = passenger,
                            onClick = { onPassengerClick(passenger.bookingId) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                
                // Close button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun PassengerListItem(
    passenger: com.gocavgo.validator.service.BookingService.PassengerInfo,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = passenger.passengerName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = if (passenger.isPickup) {
                        "To: ${passenger.destinationName}"
                    } else {
                        "From: ${passenger.originName}"
                    },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                if (passenger.numberOfTickets > 1) {
                    Text(
                        text = "${passenger.numberOfTickets} tickets",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = "View details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Data class for available destinations
 */
data class AvailableDestination(
    val id: Int,
    val locationId: Int,
    val location: SavePlaceResponse,
    val price: Double,
    val order: Int,
    val isFinalDestination: Boolean
)

/**
 * Main screen composable for Auto Mode Headless Activity
 */
@Composable
fun AutoModeHeadlessScreen(
    messageText: String,
    countdownText: String,
    currentInput: String,
    isValidationInProgress: Boolean,
    nextWaypointName: String,
    pickupCount: Int,
    dropoffCount: Int,
    onDigitClick: (String) -> Unit,
    onDeleteClick: () -> Unit,
    onClearClick: () -> Unit,
    showBookingSuccess: Boolean,
    showBookingFailure: Boolean,
    showValidationSuccess: Boolean,
    showValidationFailure: Boolean,
    showMqttNotification: Boolean,
    bookingSuccessData: BookingNfcManager.BookingSuccessData,
    bookingFailureMessage: String,
    validationSuccessTicket: String,
    validationFailureMessage: String,
    mqttNotificationData: BookingNfcManager.MqttNotificationData,
    showPassengerListDialog: Boolean,
    passengerListType: BookingNfcManager.PassengerListType,
    passengerList: List<com.gocavgo.validator.service.BookingService.PassengerInfo>,
    onPickupCountClick: () -> Unit,
    onDropoffCountClick: () -> Unit,
    onPassengerClick: (String) -> Unit,
    onPassengerListDismiss: () -> Unit,
    onBookingSuccessDismiss: () -> Unit,
    onBookingFailureDismiss: () -> Unit,
    onValidationSuccessDismiss: () -> Unit,
    onValidationFailureDismiss: () -> Unit,
    onMqttNotificationDismiss: () -> Unit,
    showDestinationSelectionDialog: Boolean,
    availableDestinations: List<AvailableDestination>,
    currentLocationForDialog: String,
    onDestinationSelected: (AvailableDestination) -> Unit,
    onDestinationSelectionDismiss: () -> Unit,
    showConfirmationDialog: Boolean,
    confirmationTripData: TripConfirmationData?,
    onConfirmStart: () -> Unit,
    onConfirmCancel: () -> Unit,
    isConnected: Boolean,
    connectionType: String,
    isMetered: Boolean,
    showMapDownloadDialog: Boolean,
    mapDownloadProgress: Int,
    mapDownloadTotalSize: Int,
    mapDownloadMessage: String,
    mapDownloadStatus: String,
    onMapDownloadCancel: () -> Unit,
    showWaypointOverlay: Boolean = false,
    waypointProgressData: List<TripSectionValidator.WaypointProgressInfo> = emptyList(),
    onToggleWaypointOverlay: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        // Network status indicator overlay
        NetworkStatusIndicator(
            isConnected = isConnected,
            connectionType = connectionType,
            isMetered = isMetered
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(16.dp)
        ) {
            // Countdown display (if active)
            if (countdownText.isNotEmpty()) {
                Text(
                    text = countdownText,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // Passenger count display
            if (pickupCount > 0 || dropoffCount > 0 || nextWaypointName.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clickable(enabled = pickupCount > 0) { onPickupCountClick() },
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF4CAF50).copy(alpha = 0.2f)
                        )
                    ) {
                        Text(
                            text = "+$pickupCount",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    Card(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clickable(enabled = dropoffCount > 0) { onDropoffCountClick() },
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFF44336).copy(alpha = 0.2f)
                        )
                    ) {
                        Text(
                            text = "-$dropoffCount",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF44336),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // Next waypoint name - hidden only when waiting for trip
            if (nextWaypointName.isNotEmpty() && !messageText.contains("Waiting for trip")) {
                Text(
                    text = nextWaypointName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            Text(
                text = messageText,
                fontSize = 16.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(bottom = 32.dp)
                    .then(
                        if (messageText.contains("Next:") || messageText.contains("km/h")) {
                            Modifier.clickable { onToggleWaypointOverlay() }
                        } else {
                            Modifier
                        }
                    )
            )

            // Ticket input display
            TicketInputDisplay(
                currentInput = currentInput,
                isValidationInProgress = isValidationInProgress,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Numeric keyboard
            NumericKeyboard(
                onDigitClick = onDigitClick,
                onDeleteClick = onDeleteClick,
                onClearClick = onClearClick,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        // Success/Failure prompts (reuse from HeadlessNavigActivity)
        BookingSuccessPrompt(
            ticketNumber = bookingSuccessData.ticketNumber,
            fromLocation = bookingSuccessData.fromLocation,
            toLocation = bookingSuccessData.toLocation,
            price = bookingSuccessData.price,
            isVisible = showBookingSuccess,
            onDismiss = onBookingSuccessDismiss
        )

        BookingFailurePrompt(
            errorMessage = bookingFailureMessage,
            isVisible = showBookingFailure,
            onDismiss = onBookingFailureDismiss
        )

        ValidationSuccessPrompt(
            ticketNumber = validationSuccessTicket,
            isVisible = showValidationSuccess,
            onDismiss = onValidationSuccessDismiss
        )

        ValidationFailurePrompt(
            errorMessage = validationFailureMessage,
            isVisible = showValidationFailure,
            onDismiss = onValidationFailureDismiss
        )

        MqttBookingNotification(
            passengerName = mqttNotificationData.passengerName,
            pickup = mqttNotificationData.pickup,
            dropoff = mqttNotificationData.dropoff,
            numTickets = mqttNotificationData.numTickets,
            isPaid = mqttNotificationData.isPaid,
            isVisible = showMqttNotification,
            onDismiss = onMqttNotificationDismiss
        )

        PassengerListDialog(
            passengers = passengerList,
            listType = when(passengerListType) {
                BookingNfcManager.PassengerListType.PICKUP -> HeadlessNavigActivity.PassengerListType.PICKUP
                BookingNfcManager.PassengerListType.DROPOFF -> HeadlessNavigActivity.PassengerListType.DROPOFF
            },
            isVisible = showPassengerListDialog,
            onPassengerClick = onPassengerClick,
            onDismiss = onPassengerListDismiss
        )

        // Destination selection dialog (conditionally rendered)
        if (showDestinationSelectionDialog) {
            DestinationSelectionDialog(
                destinations = availableDestinations,
                currentLocation = currentLocationForDialog,
                onDestinationSelected = onDestinationSelected,
                onDismiss = onDestinationSelectionDismiss
            )
        }

        // Trip confirmation dialog (conditionally rendered)
        if (showConfirmationDialog && confirmationTripData != null) {
            TripConfirmationDialog(
                data = confirmationTripData,
                onConfirm = onConfirmStart,
                onCancel = onConfirmCancel
            )
        }

        // Map download dialog overlay
        if (showMapDownloadDialog) {
            MapDownloadDialog(
                progress = mapDownloadProgress,
                totalSize = mapDownloadTotalSize,
                message = mapDownloadMessage,
                status = mapDownloadStatus,
                onCancel = onMapDownloadCancel
            )
        }
        
        // Waypoint progress overlay (floating top center with close button for AutoMode)
        // Rendered last to ensure it appears on top of all other content
        if (showWaypointOverlay) {
            WaypointProgressOverlay(
                progressData = waypointProgressData,
                onClose = onToggleWaypointOverlay,
                isTopCenter = true
            )
        }
    }
}

/**
 * Dialog for displaying map download progress
 */
@Composable
fun MapDownloadDialog(
    progress: Int,
    totalSize: Int,
    message: String,
    status: String,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* Dialog cannot be dismissed during download */ },
        title = {
            Text(
                text = "Downloading Map Data",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Status message
                Text(
                    text = status.ifEmpty { "Preparing download..." },
                    style = MaterialTheme.typography.bodyMedium
                )

                // Progress bar
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = ProgressIndicatorDefaults.linearColor,
                    trackColor = ProgressIndicatorDefaults.linearTrackColor,
                    strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                )

                // Progress text
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "$progress%",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (totalSize > 0) {
                        Text(
                            text = "${totalSize}MB",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Detailed message
                if (message.isNotEmpty()) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "This may take several minutes depending on your internet connection. The app will work normally once complete.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}
