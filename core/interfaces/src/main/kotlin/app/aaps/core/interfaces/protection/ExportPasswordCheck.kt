package app.aaps.core.interfaces.protection

import android.content.Context

interface ExportPasswordCheck {

    /***
     * Returns true when Export password store is enabled.
     */
    fun ExportPasswordStoreSupported() : Boolean

    /***
     * Clear password currently stored.
     */
    fun clearPasswordSecureStore(context: Context): String

    /***
     * Put password to local phone's datastore.
     */
    fun putPasswordToSecureStore(context: Context, password: String): String

    /***
     * Get password from local phone's data store.
     * Return pair (true,<password>) or (false,"")
     */
    fun getPasswordFromSecureStore(context: Context): Pair<Boolean, String>

}