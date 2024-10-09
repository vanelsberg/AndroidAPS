package app.aaps.core.interfaces.protection

import android.content.Context

interface SecureEncrypt {

    /***
     * Encrypt plaintext secret
     * - plaintextSecret: Plain text string to be encrypted
     * - keystoreAlias: KeyStore alias name for encryption/decryption
     * Returns: secret
     */
    fun encrypt(plaintextSecret: String, keystoreAlias: String): String

    /***
     * Decrypt plaintext string
     * - encryptedSecret: encrypted text string
     * Returns: decrypted text string
     */
    fun decrypt(encryptedSecret: String): String

}