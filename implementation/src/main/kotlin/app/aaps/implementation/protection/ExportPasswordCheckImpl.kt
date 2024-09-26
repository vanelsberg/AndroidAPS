package app.aaps.implementation.protection

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.protection.ExportPasswordCheck
import app.aaps.core.interfaces.utils.DateUtil
import dagger.Reusable
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

// Password validity window
// TODO: This should be made configurable?
const val passwordValidityWindowSeconds: Long = 5 * 60 * 1000 // 5 minutes
// Internal constant stings
const val datastoreName : String = "app.aaps.plugins.configuration.maintenance.ImportExport.datastore"
const val passwordPreferenceKeyName = "$datastoreName.password_value_key"
const val passwordTimestampPreferenceKeyName = "$datastoreName.password_timestamp_key"

@Reusable
class ExportPasswordCheckImpl @Inject constructor(
    private var log: AAPSLogger
) : ExportPasswordCheck {

    @Inject lateinit var dateUtil: DateUtil

    // TODO: (Draft) Review security on storing password in phone's local data store

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
        name = datastoreName
    )

    /***
     * Returns true when Export password store is enabled.
     * TODO: <make this configurable?
     */
    override fun ExportPasswordStoreSupported() : Boolean {
        val exportPasswordStoreSupported = true
        log.debug(LTag.CORE, "ExportPassword Store Supported: $exportPasswordStoreSupported")
        return exportPasswordStoreSupported
    }

    /***
     * Clear password currently stored to "empty"
     */
    override fun clearPasswordSecureStore(context: Context): String {
        if (!ExportPasswordStoreSupported()) return ""

        log.debug(LTag.CORE, "clearPasswordSecureStore")
        return this.storePassword(context, "")
    }

    /***
     * Put password to local phone's datastore
     */
    override fun putPasswordToSecureStore(context: Context, password: String): String {
        if (!ExportPasswordStoreSupported()) return ""

        log.debug(LTag.CORE, "putPasswordToSecureStore")
        return this.storePassword(context, password)
    }

    /***
     * Get password from local phone's data store
     * Return pair (true,<password>) or (false,"")
     */
    override fun getPasswordFromSecureStore(context: Context): Pair<Boolean, String> {
        if (!ExportPasswordStoreSupported()) return Pair (false, "")

        val password = this.retrievePassword(context)
        if (password.isNotEmpty()) {  // And not expired
            log.debug(LTag.CORE, "getPasswordFromSecureStore")
            return Pair(true, password)
        }
        return Pair (false, "")
    }

    /*************************************************************************
     * Private functions
    *************************************************************************/

    /***
     * Check if timestamp is in validity window T...T+duration
     */
    private fun isInValidityWindow(timestamp: Long, @Suppress("SameParameterValue") duration: Long?): Boolean {
        return dateUtil.now() in timestamp..timestamp + (duration ?: 0L)
    }

    /***
     * Store password and set timestamp to current
     */
    private fun storePassword(context: Context, password: String): String {

        // Write setting to android datastore
        fun updatePrefString(key: String, str: String)  = runBlocking {
            val preferencesKey = stringPreferencesKey(key)
            context.dataStore.edit { settings ->
                settings[preferencesKey] = str
            }[preferencesKey].toString()
        }

        // Update password timestamp to "now" as string value
        updatePrefString(passwordTimestampPreferenceKeyName, dateUtil.now().toString())
        // Update password value & return it
        return updatePrefString(passwordPreferenceKeyName, password)
    }

    /***
     * Retrieve password from local phone's data store.
     * Reset password when validity expired
    ***/
    private fun retrievePassword(context: Context): String {

        // Read string value from phone's local datastore using key name
        fun getPrefString(key: String) = runBlocking {
            val preferencesKey = stringPreferencesKey(key)
            (context.dataStore.edit { settings ->
                settings[preferencesKey] ?:""
            }[preferencesKey] ?:"").toString()
        }
        // Get the password value stored
        val password = getPrefString(passwordPreferenceKeyName)
        if (password.isEmpty())
            return ""

        // Password is defined: Check for password expiry:
        val timestampStr = getPrefString(passwordTimestampPreferenceKeyName) // Note: timestamp stored as string value
        val timestamp = if (timestampStr.isEmpty()) 0L else timestampStr.toLong()

        // Is password valid?
        if (!isInValidityWindow(timestamp, passwordValidityWindowSeconds))
            // Password validity ended - need to renew:
            // Clear password in data store
            return this.clearPasswordSecureStore(context)

        // Store/update password and return
        return this.storePassword(context, password)
    }

}
