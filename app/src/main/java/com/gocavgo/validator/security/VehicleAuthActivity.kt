package com.gocavgo.validator.security

import android.content.Intent
import android.os.Bundle
import com.gocavgo.validator.R
import android.util.Log
import android.widget.Button
import com.gocavgo.validator.util.Logging
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import com.gocavgo.validator.dataclass.VehicleResponseDto
import com.gocavgo.validator.security.VehicleSettingsManager
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import android.view.View
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.cancel
import java.util.concurrent.TimeUnit

class VehicleAuthActivity : AppCompatActivity() {
    // UI Components
    private var etCompanyCode: EditText? = null
    private var etMake: EditText? = null
    private var etModel: EditText? = null
    private var etCapacity: EditText? = null
    private var etLicensePlate: EditText? = null
    // Login-specific inputs
    private var etCompanyCodeLogin: EditText? = null
    private var etLicensePlateLogin: EditText? = null
    private var etPasswordLogin: EditText? = null
    private var tvVehicleType: TextView? = null
    private var btnRegisterVehicle: Button? = null
    private var btnGenerateKey: Button? = null
    private var btnLoginVehicle: Button? = null
    private var btnGenerateKeyLogin: Button? = null
    private var btnLogoutVehicle: Button? = null

    private var cardRegistrationInfo: View? = null
    private var tvRegisteredCompany: TextView? = null
    private var tvRegisteredLicense: TextView? = null
    private var tvRegistrationDate: TextView? = null
    private var containerInputFields: View? = null
    private var containerButtons: View? = null
    private var containerLogin: View? = null

    private var tabLayout: TabLayout? = null

    // Security Manager
    private lateinit var securityManager: VehicleSecurityManager
    private lateinit var settingsManager: VehicleSettingsManager
    
    // Coroutine scope for settings fetching
    private val settingsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // HTTP Client
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vehicle_auth)

        // Set this activity as active and disable its logging
        Logging.setActivityLoggingEnabled(TAG, false)

        securityManager = VehicleSecurityManager(this)
        settingsManager = VehicleSettingsManager.getInstance(this)

        initializeViews()
        setupCapacityListener()
        setupClickListeners()

        // Check if vehicle is already registered
        if (securityManager.isVehicleRegistered()) {
            loadSavedVehicleData()
        } else {
            // Generate key pair for new vehicles
            generateKeyPair()
        }
    }

    private fun loadSavedVehicleData() {
        Log.d(TAG, "Loading saved vehicle data...")

        // Hide input fields and buttons
        containerInputFields?.visibility = View.GONE
        containerButtons?.visibility = View.GONE
        containerLogin?.visibility = View.GONE
        tabLayout?.visibility = View.GONE

        // Show registration info card
        cardRegistrationInfo?.visibility = View.VISIBLE

        // Get vehicle info from security manager
        val vehicleInfo = securityManager.getVehicleInfo()
        if (vehicleInfo != null) {
            "Company: ${vehicleInfo.companyName ?: "Unknown Company"}".also { tvRegisteredCompany?.text = it }
            "License Plate: ${vehicleInfo.licensePlate ?: "Unknown"}".also { tvRegisteredLicense?.text = it }
            "Registered: ${vehicleInfo.registrationDateTime ?: "Unknown"}".also { tvRegistrationDate?.text = it }

            Toast.makeText(
                this,
                "Vehicle registered: ${vehicleInfo.licensePlate} (ID: ${vehicleInfo.vehicleId}) - Company: ${vehicleInfo.companyName}",
                Toast.LENGTH_LONG
            ).show()

            Log.d(
                TAG,
                "Loaded vehicle ID: ${vehicleInfo.vehicleId}, Company ID: ${vehicleInfo.companyId}"
            )
        } else {
            Log.e(TAG, "Failed to load vehicle data")
            Toast.makeText(this, "Error loading vehicle data", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initializeViews() {
        etCompanyCode = findViewById(R.id.et_company_code)
        etMake = findViewById(R.id.et_make)
        etModel = findViewById(R.id.et_model)
        etCapacity = findViewById(R.id.et_capacity)
        etLicensePlate = findViewById(R.id.et_license_plate)
        etCompanyCodeLogin = findViewById(R.id.et_company_code_login)
        etLicensePlateLogin = findViewById(R.id.et_license_plate_login)
        etPasswordLogin = findViewById(R.id.et_password_login)
        tvVehicleType = findViewById(R.id.tv_vehicle_type)
        btnRegisterVehicle = findViewById(R.id.btn_register_vehicle)
        btnGenerateKey = findViewById(R.id.btn_generate_key)
        btnLoginVehicle = findViewById(R.id.btn_login_vehicle)
        btnGenerateKeyLogin = findViewById(R.id.btn_generate_key_login)
        btnLogoutVehicle = findViewById(R.id.btn_logout_vehicle)

        // Registration info views
        cardRegistrationInfo = findViewById(R.id.card_registration_info)
        tvRegisteredCompany = findViewById(R.id.tv_registered_company)
        tvRegisteredLicense = findViewById(R.id.tv_registered_license)
        tvRegistrationDate = findViewById(R.id.tv_registration_date)

        // Container views
        containerInputFields = findViewById(R.id.container_input_fields)
        containerButtons = findViewById(R.id.container_buttons)
        containerLogin = findViewById(R.id.container_login)

        // Tabs
        tabLayout = findViewById(R.id.tab_vehicle_auth)
        tabLayout?.apply {
            addTab(newTab().setText("Login"))
            addTab(newTab().setText("Register"))
        }
    }

    private fun setupCapacityListener() {
        etCapacity?.addTextChangedListener { text ->
            val capacity = text.toString().trim()
            if (capacity.isNotEmpty()) {
                try {
                    val capacityInt = capacity.toInt()
                    val vehicleType = determineVehicleType(capacityInt)
                    "Vehicle Type: $vehicleType".also { tvVehicleType?.text = it }
                } catch (e: NumberFormatException) {
                    "Vehicle Type: Invalid capacity".also { tvVehicleType?.text = it }
                }
            } else {
                "Vehicle Type: Enter capacity".also { tvVehicleType?.text = it }
            }
        }
    }

    private fun determineVehicleType(capacity: Int): String {
        return when {
            capacity <= 5 -> "SEDAN"
            capacity <= 9 -> "SUV"
            capacity <= 29 -> "MINIBUS"
            else -> "BUS"
        }
    }

    private fun setupClickListeners() {
        btnRegisterVehicle?.setOnClickListener {
            btnRegisterVehicle?.isEnabled = false
            registerVehicle()
        }

        btnGenerateKey?.setOnClickListener {
            generateKeyPair()
        }

        btnLoginVehicle?.setOnClickListener {
            btnLoginVehicle?.isEnabled = false
            loginVehicle()
        }

        btnGenerateKeyLogin?.setOnClickListener {
            generateKeyPair()
        }

        btnLogoutVehicle?.setOnClickListener {
            logoutVehicle()
        }

        tabLayout?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                updateVisibleContainerForTab(tab.position)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // Default to Register tab visible unless explicitly registered
        updateVisibleContainerForTab(tabLayout?.selectedTabPosition ?: 1)
    }

    private fun generateKeyPair() {
        if (securityManager.generateNewKeyPair()) {
            val publicKeyBase64 = securityManager.getPublicKeyBase64()
            Log.d(TAG, "Key pair generated successfully")
            Log.d(TAG, "Public Key (Base64): $publicKeyBase64")
            Toast.makeText(this, "Key pair generated successfully", Toast.LENGTH_SHORT).show()
        } else {
            Log.e(TAG, "Failed to generate key pair")
            Toast.makeText(this, "Error generating key pair", Toast.LENGTH_LONG).show()
        }
    }

    private fun logoutVehicle() {
        try {
            Log.d(TAG, "Logging out vehicle and clearing credentials...")
            
            // Clear vehicle data from preferences
            securityManager.clearVehicleData()
            
            // Delete the existing key pair
            securityManager.deleteKeyPair()
            
            // Clear all input fields
            clearAllInputFields()
            
            // Reset UI to show registration/login forms
            resetUIForNewVehicle()
            
            // Generate new key pair for the new vehicle
            generateKeyPair()
            
            Toast.makeText(
                this,
                "Vehicle logged out successfully. You can now register or login with a new vehicle.",
                Toast.LENGTH_LONG
            ).show()
            
            Log.d(TAG, "Vehicle logout completed successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during vehicle logout", e)
            Toast.makeText(this, "Error during logout: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loginVehicle() {
        // Validate inputs for login
        if (!validateLoginInputs()) {
            btnLoginVehicle?.isEnabled = true
            return
        }

        try {
            // Get public key from security manager
            val publicKeyBase64 = securityManager.getPublicKeyBase64()
            if (publicKeyBase64 == null) {
                Toast.makeText(
                    this,
                    "No key found. Please generate a key first.",
                    Toast.LENGTH_LONG
                ).show()
                btnLoginVehicle?.isEnabled = true
                return
            }

            val loginData = VehicleLoginRequestDto(
                companyCode = etCompanyCodeLogin?.text.toString().trim().uppercase(),
                licensePlate = etLicensePlateLogin?.text.toString().trim(),
                password = etPasswordLogin?.text.toString().trim(),
                pubKey = publicKeyBase64
            )

            sendLoginToBackend(loginData)
        } catch (e: Exception) {
            Log.e(TAG, "Error during vehicle login", e)
            Toast.makeText(this, "Login failed: ${e.message}", Toast.LENGTH_LONG).show()
            btnLoginVehicle?.isEnabled = true
        }
    }

    private fun sendLoginToBackend(loginData: VehicleLoginRequestDto) {
        try {
            val json = gson.toJson(loginData)
            Log.d(TAG, "Sending login data: $json")

            val requestBody = json.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://api.gocavgo.com/api/main/vehicles/login")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "API call failed", e)
                    runOnUiThread {
                        val errorMessage = "Login failed: ${e.message ?: "Network error"}"
                        Toast.makeText(
                            this@VehicleAuthActivity,
                            errorMessage,
                            Toast.LENGTH_LONG
                        ).show()
                        btnLoginVehicle?.isEnabled = true
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string()
                    Log.d(TAG, "API Login Response: ${response.code} - $responseBody")

                    runOnUiThread {
                        if (response.isSuccessful && responseBody != null) {
                            try {
                                val vehicleResponse =
                                    gson.fromJson(responseBody, VehicleResponseDto::class.java)

                                securityManager.saveVehicleData(
                                    vehicleResponse.id,
                                    vehicleResponse.companyId,
                                    vehicleResponse.companyName,
                                    vehicleResponse.licensePlate
                                )

                                Toast.makeText(
                                    this@VehicleAuthActivity,
                                    "Vehicle logged in! ID: ${vehicleResponse.id}",
                                    Toast.LENGTH_LONG
                                ).show()

                                // Fetch and apply settings after successful authentication, then navigate
                                fetchAndApplySettings(vehicleResponse.id.toInt()) {
                                    // Navigate back to LauncherActivity to re-evaluate settings
                                    val intent = Intent(this@VehicleAuthActivity, com.gocavgo.validator.LauncherActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    }
                                    startActivity(intent)
                                    finish()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing login response", e)
                                Toast.makeText(
                                    this@VehicleAuthActivity,
                                    "Login successful but error saving data",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            // Extract user-friendly error message from JSON response
                            val errorMessage = extractErrorMessage(responseBody)
                            Toast.makeText(
                                this@VehicleAuthActivity,
                                errorMessage,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        btnLoginVehicle?.isEnabled = true
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error sending login to backend", e)
            Toast.makeText(this, "Error sending data: ${e.message}", Toast.LENGTH_LONG).show()
            btnLoginVehicle?.isEnabled = true
        }
    }

    private fun validateLoginInputs(): Boolean {
        if (etCompanyCodeLogin?.text.toString().trim().isEmpty()) {
            etCompanyCodeLogin?.error = "Company Code is required"
            return false
        }
        if (etLicensePlateLogin?.text.toString().trim().isEmpty()) {
            etLicensePlateLogin?.error = "License plate is required"
            return false
        }
        if (etPasswordLogin?.text.toString().trim().isEmpty()) {
            etPasswordLogin?.error = "Password is required"
            return false
        }
        return true
    }

    private fun updateVisibleContainerForTab(position: Int) {
        if (securityManager.isVehicleRegistered()) {
            // If already registered, show the registration info card and hide forms
            cardRegistrationInfo?.visibility = View.VISIBLE
            containerInputFields?.visibility = View.GONE
            containerButtons?.visibility = View.GONE
            containerLogin?.visibility = View.GONE
            tabLayout?.visibility = View.GONE
            return
        }

        tabLayout?.visibility = View.VISIBLE
        // Not registered yet: toggle between Login and Register
        when (position) {
            0 -> { // Login
                containerLogin?.visibility = View.VISIBLE
                containerInputFields?.visibility = View.GONE
                // Hide registration-specific buttons (security header, register & reg key)
                containerButtons?.visibility = View.GONE
                cardRegistrationInfo?.visibility = View.GONE
            }
            else -> { // Register
                containerLogin?.visibility = View.GONE
                containerInputFields?.visibility = View.VISIBLE
                containerButtons?.visibility = View.VISIBLE
                cardRegistrationInfo?.visibility = View.GONE
            }
        }
    }

    private fun registerVehicle() {
        // Validate inputs
        if (!validateInputs()) {
            btnRegisterVehicle?.isEnabled = true
            return
        }

        try {
            // Get public key from security manager
            val publicKeyBase64 = securityManager.getPublicKeyBase64()
            if (publicKeyBase64 == null) {
                Toast.makeText(
                    this,
                    "No key found. Please generate a key first.",
                    Toast.LENGTH_LONG
                ).show()
                btnRegisterVehicle?.isEnabled = true
                return
            }

            val capacity = etCapacity?.text.toString().trim().toInt()
            val vehicleType = determineVehicleType(capacity)

            // Create vehicle registration data
            val registrationData = VehicleRequestDto(
                etCompanyCode?.text.toString().trim().uppercase(),
                etMake?.text.toString().trim(),
                etModel?.text.toString().trim(),
                publicKeyBase64,
                capacity,
                etLicensePlate?.text.toString().trim(),
                vehicleType
            )

            // Send to backend
            sendRegistrationToBackend(registrationData)

        } catch (e: Exception) {
            Log.e(TAG, "Error during vehicle registration", e)
            Toast.makeText(this, "Registration failed: ${e.message}", Toast.LENGTH_LONG).show()
            btnRegisterVehicle?.isEnabled = true
        }
    }

    private fun sendRegistrationToBackend(registrationData: VehicleRequestDto) {
        try {
            val json = gson.toJson(registrationData)
            Log.d(TAG, "Sending registration data: $json")

            val requestBody = json.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://api.gocavgo.com/api/main/vehicles")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "API call failed", e)
                    runOnUiThread {
                        val errorMessage = "Registration failed: ${e.message ?: "Network error"}"
                        Toast.makeText(
                            this@VehicleAuthActivity,
                            errorMessage,
                            Toast.LENGTH_LONG
                        ).show()
                        btnRegisterVehicle?.isEnabled = true
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string()
                    Log.d(TAG, "API Response: ${response.code} - $responseBody")

                    runOnUiThread {
                        if (response.isSuccessful && responseBody != null) {
                            try {
                                // Parse the response
                                val vehicleResponse =
                                    gson.fromJson(responseBody, VehicleResponseDto::class.java)

                                // Save to permanent storage using security manager
                                securityManager.saveVehicleData(
                                    vehicleResponse.id,
                                    vehicleResponse.companyId,
                                    vehicleResponse.companyName,
                                    vehicleResponse.licensePlate
                                )

                                Toast.makeText(
                                    this@VehicleAuthActivity,
                                    "Vehicle registered successfully! ID: ${vehicleResponse.id}",
                                    Toast.LENGTH_LONG
                                ).show()

                                // Fetch and apply settings after successful authentication, then navigate
                                fetchAndApplySettings(vehicleResponse.id.toInt()) {
                                    // Navigate back to LauncherActivity to re-evaluate settings
                                    val intent = Intent(this@VehicleAuthActivity, com.gocavgo.validator.LauncherActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    }
                                    startActivity(intent)
                                    finish()
                                }

                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing response", e)
                                Toast.makeText(
                                    this@VehicleAuthActivity,
                                    "Registration successful but error saving data",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            // Extract user-friendly error message from JSON response
                            val errorMessage = extractErrorMessage(responseBody)
                            Toast.makeText(
                                this@VehicleAuthActivity,
                                errorMessage,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        btnRegisterVehicle?.isEnabled = true
                    }
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Error sending registration to backend", e)
            Toast.makeText(this, "Error sending data: ${e.message}", Toast.LENGTH_LONG).show()
            btnRegisterVehicle?.isEnabled = true
        }
    }

    private fun validateInputs(): Boolean {
        if (etCompanyCode?.text.toString().trim().isEmpty()) {
            etCompanyCode?.error = "Company Code is required"
            return false
        }

        if (etMake?.text.toString().trim().isEmpty()) {
            etMake?.error = "Make is required"
            return false
        }

        if (etModel?.text.toString().trim().isEmpty()) {
            etModel?.error = "Model is required"
            return false
        }

        if (etCapacity?.text.toString().trim().isEmpty()) {
            etCapacity?.error = "Capacity is required"
            return false
        }

        try {
            val capacity = etCapacity?.text.toString().trim().toInt()
            if (capacity < 1) {
                etCapacity?.error = "Capacity must be at least 1"
                return false
            }
        } catch (e: NumberFormatException) {
            etCapacity?.error = "Please enter a valid number"
            return false
        }

        if (etLicensePlate?.text.toString().trim().isEmpty()) {
            etLicensePlate?.error = "License plate is required"
            return false
        }

        return true
    }

    private fun clearAllInputFields() {
        // Clear registration fields
        etCompanyCode?.setText("")
        etMake?.setText("")
        etModel?.setText("")
        etCapacity?.setText("")
        etLicensePlate?.setText("")
        
        // Clear login fields
        etCompanyCodeLogin?.setText("")
        etLicensePlateLogin?.setText("")
        etPasswordLogin?.setText("")
        
        // Reset vehicle type display
        tvVehicleType?.text = "Vehicle Type: Enter capacity"
    }

    private fun resetUIForNewVehicle() {
        // Hide registration info card
        cardRegistrationInfo?.visibility = View.GONE
        
        // Show tabs and default to Register tab
        tabLayout?.visibility = View.VISIBLE
        tabLayout?.getTabAt(1)?.select() // Select Register tab (index 1)
        
        // Show input fields and buttons for registration
        containerInputFields?.visibility = View.VISIBLE
        containerButtons?.visibility = View.VISIBLE
        containerLogin?.visibility = View.GONE
        
        // Re-enable buttons
        btnRegisterVehicle?.isEnabled = true
        btnLoginVehicle?.isEnabled = true
    }

    // Data class for API request
    private data class VehicleRequestDto(
        val companyCode: String,
        val make: String,
        val model: String,
        val pubKey: String,
        val capacity: Int,
        val licensePlate: String,
        val vehicleType: String
    )

    private data class VehicleLoginRequestDto(
        val companyCode: String,
        val licensePlate: String,
        val password: String,
        val pubKey: String
    )

    // Data class for error response
    private data class ErrorResponseDto(
        val timestamp: String? = null,
        val status: Int? = null,
        val message: String? = null,
        val path: String? = null,
        val errors: List<ErrorDetail>? = null
    )

    private data class ErrorDetail(
        val field: String? = null,
        val message: String? = null
    )

    /**
     * Extract user-friendly error message from error response JSON
     */
    private fun extractErrorMessage(responseBody: String?): String {
        if (responseBody.isNullOrBlank()) {
            return "Unknown error occurred"
        }
        
        return try {
            val errorResponse = gson.fromJson(responseBody, ErrorResponseDto::class.java)
            
            // First try to get message from errors array (field-specific errors)
            errorResponse.errors?.firstOrNull()?.message?.let { 
                return it
            }
            
            // Fall back to main message field
            errorResponse.message ?: "Unknown error occurred"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse error response", e)
            // If parsing fails, return the raw response or a generic message
            responseBody.takeIf { it.length < 200 } ?: "An error occurred. Please try again."
        }
    }

    /**
     * Fetch settings from API and apply them after successful authentication
     */
    private fun fetchAndApplySettings(vehicleId: Int, onComplete: () -> Unit) {
        settingsScope.launch {
            try {
                Log.d(TAG, "Fetching settings after authentication for vehicle $vehicleId")
                val result = settingsManager.fetchSettingsFromApi(vehicleId)
                
                result.onSuccess { settings ->
                    Log.d(TAG, "Settings fetched successfully after authentication")
                    // Apply settings (check logout/deactivate)
                    runOnUiThread {
                        settingsManager.applySettings(this@VehicleAuthActivity, settings)
                        onComplete()
                    }
                }.onFailure { error ->
                    Log.e(TAG, "Failed to fetch settings after authentication: ${error.message}")
                    // Continue without settings - they'll be fetched on next app launch
                    runOnUiThread {
                        onComplete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception fetching settings after authentication: ${e.message}", e)
                runOnUiThread {
                    onComplete()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        
        // Set this activity as the active one for logging
        Logging.setActiveActivity(TAG)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel settings scope
        settingsScope.cancel()
    }

    companion object {
        private const val TAG = "VehicleAuthActivity"
    }
}