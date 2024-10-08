package app.aaps.implementation.protection

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.protection.SecureEncrypt
import app.aaps.core.interfaces.sharedPreferences.SP
import dagger.Reusable
import javax.inject.Inject

/***
 * Encrypting secrets using Android KeyStore?
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
private const val keyAlias = "doTestKeyAlias01"
private const val prefEncryptionSecret = "export_secret"
private const val hexStringSeparator = ":"

@Reusable
class SecureEncryptImpl @Inject constructor(
    private var log: AAPSLogger,
    private val sp: SP
    ) : SecureEncrypt {

    // TODO: Implementation
    override fun dummy(context: Context) : Boolean {
        log.debug(LTag.CORE, "TODO: Executing Dummy function...")
        return false
    }

    /***
     * TODO: encryption/decryption (needs additional implementation and error/exception handling)
     */
    override fun encrypt(str: String): String {
        var secret = ""
        if (!str.isNullOrEmpty()) {
            // Encrypt original data string
            val classEncryptedData = keyStoreEncrypt(keyAlias, str)
            secret = getDataStringFromClassEncryptedData(classEncryptedData)
            log.debug(LTag.CORE, "Stored encryption secret!?.")
        }
        else {
            log.debug(LTag.CORE, "not encrypting empty secret!?.")
        }
        sp.putString(prefEncryptionSecret, secret)

        return secret
    }

    override fun decrypt(str: String): String {
        // doTest() // TODO: testing only

        // Retrieve encryption secret from preferences
        var decryptedString = ""
        val prefSecrets: String = sp.getString(prefEncryptionSecret, "")
        if (!prefSecrets.isNullOrEmpty()) {
            // Prepare for decryption
            val classEncryptedData = putDataStringToClassEncryptedData(prefSecrets)
            // Decrypt string
            decryptedString = keyStoreDecrypt(keyAlias, classEncryptedData)
            log.debug(LTag.CORE, "secret decrypted.")
        }
        else
            log.debug(LTag.CORE, "empty secret not decrypted.")

        return decryptedString
    }


    /*************************************************************************
     * Private functions
    *************************************************************************/

    private fun getKeyFromKeyStore(keyAlias: String, forceNew: Boolean = false): SecretKey {
        // TODO: Handle exceptions!?

        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val keyStoreIsAvailable = keyStore.containsAlias(keyAlias)

        if (!keyStoreIsAvailable || forceNew) {
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
            return retrieveSecretKeyFromKeyStore(keyAlias)
        }
    }

    private fun retrieveSecretKeyFromKeyStore(keyAlias: String): SecretKey {
        // TODO: Handle exceptions!?

        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val secretKeyEntry = keyStore.getEntry(keyAlias, null) as KeyStore.SecretKeyEntry
        val secretKey = secretKeyEntry.secretKey
        return secretKey
    }

    /***
     * Convert hex string to ByteArray
     * Returns ByteArray
     */
    private fun hexStringToByteArray(hexString: String): ByteArray {
        val len = hexString.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hexString[i], 16) shl 4) + Character.digit(hexString[i + 1], 16)).toByte()
        }
        return data
    }

    /***
     * Data class holding encryption attributes
     */
    data class ClassEncryptedData (
        var ivHexString: String,
        var encryptedDataHexString: String
    )

    /**
     * Get classEncryptedData into one single Sting formatted "<hexdata1><separator><hexdata2>"
     */
    private fun getDataStringFromClassEncryptedData(classEncryptedData: ClassEncryptedData): String
    {
        return buildString {
        append(classEncryptedData.ivHexString)
        append(hexStringSeparator)
        append(classEncryptedData.encryptedDataHexString)
        }
    }

    /**
     * Input single Sting formatted "<hexdata1><separator><hexdata2>"
     * Returns initialized ClassEncryptedData object
     */
    private fun putDataStringToClassEncryptedData(dataString: String): ClassEncryptedData
    {
        val data = dataString.split(hexStringSeparator)
        return ClassEncryptedData (
            ivHexString = data[0],
            encryptedDataHexString = data[1]
        )
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun keyStoreEncrypt(keyAlias: String, originalDataString: String): ClassEncryptedData {
        // TODO: Handle exceptions!

        // Get secret key
        val secretKey = getKeyFromKeyStore(keyAlias)

        // Initialize Cipher for encryption & get iv
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val ivHex = cipher.iv.toHexString()

        // Encrypt Data
        val originalData = originalDataString.toByteArray()
        val encryptedData = cipher.doFinal(originalData)

        val classEncryptedData = ClassEncryptedData (
            ivHexString = ivHex,
            encryptedDataHexString = encryptedData.toHexString()
        )
        println("Encryption, iv(hex)  : $classEncryptedData.ivHex")
        println("Encryption, Text(hex): $classEncryptedData.encryptedDataHexString")
        return classEncryptedData
    }

    private fun keyStoreDecrypt(keyAlias: String, classEncryptedData: ClassEncryptedData): String {
        // TODO: Handle exceptions!

        // Get decryption parameters
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv: ByteArray = classEncryptedData.ivHexString.hexStringToByteArray()
        val secretKey: SecretKey = retrieveSecretKeyFromKeyStore(keyAlias)

        // Initialize Cipher instance we already have?
        val ivParameterSpec = GCMParameterSpec(128, iv) // Use GCMParameterSpec
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec)

        // Decrypt
        val dataToDecrypt = hexStringToByteArray(classEncryptedData.encryptedDataHexString)
        val decryptedData = cipher.doFinal(dataToDecrypt)
        val decryptedDataString=String(decryptedData)

        // Check the result:
        println("Encryption, decrypted text: $decryptedDataString")
        return decryptedDataString
    }

    /***
    * TESTING...
    */

    // Test/Usage example
    private fun doTest(): Boolean {
        val originalDataString = "My sensitive data"

        // Encrypt original data string
        var classEncryptedData = keyStoreEncrypt(keyAlias, originalDataString)
        /**
        Data class classEncryptedData object now holds keystore parameter iv (bytes) and encrypted data (bytes) as Hex String
        Function keyStoreDecrypt uses the key alias to retrieve the secret key and decipher the encrypted data using
        the secret key and iv
        **/
        // Make it one string for easy storing
        var testdata:String = getDataStringFromClassEncryptedData(classEncryptedData)

        // Try to get some encrypted data from elsewhere
        val decryptDebug = false
        if (decryptDebug) {
            // Debug: using fixed iv and encrypted string from previous encryption run (see logcat?)
            val testDataClass = ClassEncryptedData (
                ivHexString = "833fb80226e34b7289b3cad5",
                encryptedDataHexString = "f81583199a30642ec9c32ca2fd1d6e23ed15c874c42f2acde9b2b6774b1175fad6"
            )
            testdata = getDataStringFromClassEncryptedData(testDataClass)
        }

        // Now decrypt encryption result in decrypted data to original data string
        classEncryptedData = putDataStringToClassEncryptedData(testdata)
        val decryptedDataString = keyStoreDecrypt(keyAlias, classEncryptedData)

        if (originalDataString == decryptedDataString) {
            println("Encryption, OK!")
            return true
        } else {
            println("EncryptionERROR!")
            return false
        }
    }

}
