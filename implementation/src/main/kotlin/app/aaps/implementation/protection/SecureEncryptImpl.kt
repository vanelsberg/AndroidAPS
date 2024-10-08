package app.aaps.implementation.protection

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.protection.SecureEncrypt
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.BooleanKey
import dagger.Reusable
import javax.inject.Inject

/***
 * EXAMPLE for encrypting using Android KeyStore?
 */

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.aaps.core.utils.hexStringToByteArray
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Internal constant stings?

@Reusable
class SecureEncryptImpl @Inject constructor(
    private var log: AAPSLogger,
    private val sp: SP
    ) : SecureEncrypt {

    // TODO: Implementation
    override fun dummy(context: Context) : Boolean {
        val exportPasswordStoreIsEnabled  = sp.getBoolean(BooleanKey.MaintenanceEnableExportSettingsAutomation.key, false)
        log.debug(LTag.CORE, "TODO: Executing Dummy function...")
        return false
    }

    /***
     * TODO: Preparing for encryption/decryption (needs additional implementation)
     */
    override fun encrypt(str: String): String {
        log.debug(LTag.CORE, "encrypting export password.")
        return str
    }

    override fun decrypt(str: String): String {
        //test()
        doTest()
        log.debug(LTag.CORE, "decrypting export password.")
        return str
    }


    /*************************************************************************
     * Private functions
    *************************************************************************/

    /***
     * EXAMPLE for encrypting using Android KeyStore?
     */

    // Function to encrypt a string
    private fun encryptText(keyAlias: String, textToEncrypt: String): String {
        var encryptedData: String = ""
        try {
        }
        catch (e: Exception) {
            encryptedData = ""
            log.error(LTag.CORE, "encryptText: ${e.message}")

        }
        return encryptedData
    }

    // Function to decrypt a string
    private fun decryptText(alias: String, encryptedText: ByteArray): String {
        var decryptedText: String = ""
        try {
        }
        catch (e: Exception) {
            log.error(LTag.CORE, "decryptText: ", e)
        }
        return decryptedText
    }

    private fun hexStringToByteArray(hexString: String): ByteArray {
        val len = hexString.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hexString[i], 16) shl 4) + Character.digit(hexString[i + 1], 16)).toByte()
        }
        return data
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun getIvHex(iv: ByteArray): String
    {
        var ivHex: String = iv.toHexString()
        //val ivHex = "8daf837291125a3a2a7d435db2369dd8"
        return ivHex
    }

    private fun storeSecretKey(keyAlias: String, createNew: Boolean = true): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val keyStoreIsAvailable = keyStore.containsAlias(keyAlias)

        if (!keyStoreIsAvailable || createNew) {
            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                keyAlias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            keyGenerator.init(keyGenParameterSpec)
            return keyGenerator.generateKey()
        }
        else {
            return retrieveSecretKey(keyAlias)
        }
    }

    private fun retrieveSecretKey(keyAlias: String): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val secretKeyEntry = keyStore.getEntry(keyAlias, null) as KeyStore.SecretKeyEntry
        val secretKey = secretKeyEntry.secretKey
        return secretKey
    }


    // Test/Usage example
    @OptIn(ExperimentalStdlibApi::class)
    private fun doTest(): Boolean {
        val keyAlias = "myTestKeyAlias1"

        // Get secret key
        var secretKey = storeSecretKey(keyAlias, createNew = false)

        // Initialize Cipher for encryption & get iv
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        var ivHex = cipher.iv.toHexString()

        // Encrypt Data
        val originalDataString = "My sensitive data"
        val originalData = originalDataString.toByteArray()
        val encryptedData = cipher.doFinal(originalData)
        var encryptedDataHexString = encryptedData.toHexString()

        /*** Result:
         * - ivHex
         * - encryptedDataHexString
         */
        println("Encryption, iv(hex)  : $ivHex")
        println("Encryption, Text(hex): $encryptedDataHexString")
        /* Logcat:
        * Encryption, iv(hex)  : eb0b3795249f0286eb1b1d79
        * Encryption, Text(hex): e2eb6dc55cd09f0a27e2a76944ee14d7b1d6dcb4d6111620237b72633ea62e63b8
        */

        // Now try to decrypt:
        // Get decryption parameters
        val cipher2 = Cipher.getInstance("AES/GCM/NoPadding")
        val iv2: ByteArray = ivHex.hexStringToByteArray()
        val secretKey2: SecretKey = retrieveSecretKey(keyAlias)

        //iv2 = ""
        //encryptedDataHexString = "bab38811c25356e46277d73c8d304034383e76ac52aead190f75ee8cdcb6c8e2"

        // Initialize Cipher instance we already have?
        val ivParameterSpec = GCMParameterSpec(128, iv2) // Use GCMParameterSpec
        cipher2.init(Cipher.DECRYPT_MODE, secretKey2, ivParameterSpec)

        // Decrypt

        val dataToDecrypt = hexStringToByteArray(encryptedDataHexString)
        val decryptedData = cipher2.doFinal(dataToDecrypt)
        val decryptedDataString=String(decryptedData)

        // Check the result:
        println("Encryption, decrypted text: $decryptedDataString")

        if (originalDataString == decryptedDataString) {
            println("Encryption, OK!")
            return true
        } else {
            println("EncryptionERROR!")
            return false
        }
    }

}
