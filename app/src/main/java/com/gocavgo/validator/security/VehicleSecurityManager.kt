package com.gocavgo.validator.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import javax.crypto.BadPaddingException

class VehicleSecurityManager(private val context: Context) {

    private val vehiclePrefs: VehiclePreferences = VehiclePreferences(context)

    /**
     * Generates a new key pair and stores it in Android Keystore
     * @return Boolean indicating success
     */
    fun generateNewKeyPair(): Boolean {
        return try {
            Log.d(TAG, "Generating new key pair...")

            // Delete existing key if present
            deleteExistingKey()

            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                "AndroidKeyStore"
            )

            val parameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()

            keyPairGenerator.initialize(parameterSpec)
            val keyPair = keyPairGenerator.generateKeyPair()

            Log.d(TAG, "Key pair generated successfully")
            Log.d(TAG, "Public Key Algorithm: ${keyPair.public.algorithm}")
            Log.d(TAG, "Public Key Format: ${keyPair.public.format}")

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error generating key pair", e)
            false
        }
    }

    /**
     * Gets the stored private key from Android Keystore
     * @return PrivateKey or null if not found
     */
    fun getPrivateKey(): PrivateKey? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            if (!keyStore.containsAlias(KEY_ALIAS)) {
                Log.w(TAG, "Private key not found in keystore")
                return null
            }

            keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving private key", e)
            null
        }
    }

    /**
     * Gets the stored public key from Android Keystore
     * @return PublicKey or null if not found
     */
    fun getPublicKey(): PublicKey? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            if (!keyStore.containsAlias(KEY_ALIAS)) {
                Log.w(TAG, "Public key not found in keystore")
                return null
            }

            keyStore.getCertificate(KEY_ALIAS).publicKey
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving public key", e)
            null
        }
    }

    /**
     * Gets the public key as Base64 encoded string
     * @return Base64 encoded public key or null if not found
     */
    fun getPublicKeyBase64(): String? {
        return try {
            val publicKey = getPublicKey()
            publicKey?.let {
                Base64.encodeToString(it.encoded, Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding public key to Base64", e)
            null
        }
    }

    /**
     * Signs data using the stored private key
     * @param data The data to sign
     * @return Base64 encoded signature or null if signing failed
     */
    fun signData(data: String): String? {
        return try {
            val privateKey = getPrivateKey() ?: return null

            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(privateKey)
            signature.update(data.toByteArray())

            val signatureBytes = signature.sign()
            Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error signing data", e)
            null
        }
    }

    /**
     * Verifies a signature using the stored public key
     * @param data The original data
     * @param signatureBase64 The Base64 encoded signature
     * @return Boolean indicating if signature is valid
     */
    fun verifySignature(data: String, signatureBase64: String): Boolean {
        return try {
            val publicKey = getPublicKey() ?: return false

            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initVerify(publicKey)
            signature.update(data.toByteArray())

            val signatureBytes = Base64.decode(signatureBase64, Base64.NO_WRAP)
            signature.verify(signatureBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying signature", e)
            false
        }
    }

    /**
     * Checks if a key pair exists in the keystore
     * @return Boolean indicating if key pair exists
     */
    fun hasKeyPair(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.containsAlias(KEY_ALIAS)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking key pair existence", e)
            false
        }
    }

    /**
     * Deletes the existing key pair from keystore
     * @return Boolean indicating success
     */
    fun deleteKeyPair(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
                Log.d(TAG, "Key pair deleted successfully")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting key pair", e)
            false
        }
    }

    private fun deleteExistingKey() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
                Log.d(TAG, "Existing key deleted")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting existing key", e)
        }
    }

    // Vehicle Preferences Methods

    /**
     * Saves vehicle registration data
     */
    fun saveVehicleData(
        vehicleId: Long,
        companyId: Long,
        companyName: String,
        licensePlate: String
    ) {
        vehiclePrefs.saveVehicleData(vehicleId, companyId, companyName, licensePlate)
    }

    /**
     * Checks if vehicle is registered
     */
    fun isVehicleRegistered(): Boolean = vehiclePrefs.isVehicleRegistered()

    /**
     * Gets the stored vehicle ID
     */
    fun getVehicleId(): Long = vehiclePrefs.getVehicleId()

    /**
     * Gets the stored company ID
     */
    fun getCompanyId(): Long = vehiclePrefs.getCompanyId()

    /**
     * Gets the stored company name
     */
    fun getCompanyName(): String? = vehiclePrefs.getCompanyName()

    /**
     * Gets the stored license plate
     */
    fun getLicensePlate(): String? = vehiclePrefs.getLicensePlate()

    /**
     * Gets the registration date/time as formatted string
     */
    fun getRegistrationDateTime(): String? = vehiclePrefs.getRegistrationDateTime()

    /**
     * Clears all vehicle data from preferences
     */
    fun clearVehicleData() {
        vehiclePrefs.clearVehicleData()
    }

    /**
     * Gets complete vehicle info as a data class
     */
    fun getVehicleInfo(): VehicleInfo? {
        return if (isVehicleRegistered()) {
            VehicleInfo(
                vehicleId = getVehicleId(),
                companyId = getCompanyId(),
                companyName = getCompanyName(),
                licensePlate = getLicensePlate(),
                registrationDateTime = getRegistrationDateTime(),
                hasValidKeyPair = hasKeyPair()
            )
        } else null
    }

    /**
     * Complete setup check - ensures both vehicle is registered and key pair exists
     */
    fun isCompletelySetup(): Boolean {
        return isVehicleRegistered() && hasKeyPair()
    }

    companion object {
        private const val TAG = "VehicleSecurityManager"
        private const val KEY_ALIAS = "vehicle_auth_key"
    }

    /**
     * Data class to hold complete vehicle information
     */
    data class VehicleInfo(
        val vehicleId: Long,
        val companyId: Long,
        val companyName: String?,
        val licensePlate: String?,
        val registrationDateTime: String?,
        val hasValidKeyPair: Boolean
    )
}