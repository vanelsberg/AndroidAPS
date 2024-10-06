package app.aaps.core.interfaces.protection

import android.content.Context

interface SecureEncrypt {

    /***
     * TODO: Implementation
     * Dummy function
     * Returns true when Export password store is enabled.
     */
    fun dummy(context: Context) : Boolean

    fun encrypt(str: String): String

    fun decrypt(str: String): String

}