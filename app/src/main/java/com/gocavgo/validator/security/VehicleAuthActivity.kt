package com.gocavgo.validator.security

import android.os.Bundle
import com.gocavgo.validator.R
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import com.gocavgo.validator.dataclass.VehicleResponseDto
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import android.view.View
import com.google.android.material.tabs.TabLayout
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

        securityManager = VehicleSecurityManager(this)

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
                        Toast.makeText(
                            this@VehicleAuthActivity,
                            "Login failed: ${e.message}",
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

                                loadSavedVehicleData()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing login response", e)
                                Toast.makeText(
                                    this@VehicleAuthActivity,
                                    "Login successful but error saving data",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            Toast.makeText(
                                this@VehicleAuthActivity,
                                "Login failed: ${response.code} - $responseBody",
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
                        Toast.makeText(
                            this@VehicleAuthActivity,
                            "Registration failed: ${e.message}",
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

                                loadSavedVehicleData()

                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing response", e)
                                Toast.makeText(
                                    this@VehicleAuthActivity,
                                    "Registration successful but error saving data",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            Toast.makeText(
                                this@VehicleAuthActivity,
                                "Registration failed: ${response.code} - $responseBody",
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

    companion object {
        private const val TAG = "VehicleAuthActivity"
    }
}