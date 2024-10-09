package app.aaps.core.interfaces.protection

import android.content.Context

interface SecureEncrypt {

    /***
     * TODO: Implementation
     * Dummy function
     * Returns true when Export password store is enabled.
     */
    fun doEncryptionTest(context: Context) : Boolean

    fun encrypt(plaintextSecret: String, keystoreAlias: String): String

    fun decrypt(encryptedSecret: String): String

}