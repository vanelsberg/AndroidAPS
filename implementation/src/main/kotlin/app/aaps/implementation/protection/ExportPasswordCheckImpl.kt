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
const val passwordValidityWindowSeconds: Long = 7 * 24 * 3600 * 1000 // 1 days
// const val passwordValidityWindowSeconds: Long = 10 * 60 * 1000 // 10 minutes

// Internal constant stings
const val datastoreName : String = "app.aaps.plugins.configuration.maintenance.ImportExport.datastore"
const val passwordPreferenceName = "$datastoreName.password_value"

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
    override fun clearPasswordDataStore(context: Context): String {
        if (!ExportPasswordStoreSupported()) return ""

        log.debug(LTag.CORE, "clearPasswordDataStore")
        // Store & update to empty password and return
        return this.storePassword(context, "")
    }

    /***
     * Put password to local phone's datastore
     */
    override fun putPasswordToDataStore(context: Context, password: String): String {
        if (!ExportPasswordStoreSupported()) return ""

        log.debug(LTag.CORE, "putPasswordToDataStore")
        return this.storePassword(context, password)
    }

    /***
     * Get password from local phone's data store
     * Return pair (true,<password>) or (false,"")
     */
    override fun getPasswordFromDataStore(context: Context): Pair<Boolean, String> {
        if (!ExportPasswordStoreSupported()) return Pair (false, "")

        val password = this.retrievePassword(context)
        if (password.isNotEmpty()) {  // And not expired
            log.debug(LTag.CORE, "getPasswordFromDataStore")
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

        // Write setting to android datastore and return password
        fun updatePrefString(name: String, str: String)  = runBlocking {
            val preferencesKeyPassword = stringPreferencesKey("$name.key")
            val preferencesKeyTimestamp = stringPreferencesKey("$name.ts")
            context.dataStore.edit { settings ->
                // Update password and timestamp to "now" as string value
                settings[preferencesKeyPassword] = str
                settings[preferencesKeyTimestamp] = dateUtil.now().toString()
            }[preferencesKeyPassword].toString()
        }

        // Update & return password string
        return updatePrefString(passwordPreferenceName, password)
    }

    /***
     * Retrieve password from local phone's data store.
     * Reset password when validity expired
    ***/
    private fun retrievePassword(context: Context): String {

        // Read string value from phone's local datastore using key name
        var passwordStr = ""
        var timestampStr = ""

        runBlocking {
            val keyname = passwordPreferenceName
            val preferencesKeyVal = stringPreferencesKey("$keyname.key")
            val preferencesKeyTs = stringPreferencesKey("$keyname.ts")
            context.dataStore.edit { settings ->
                passwordStr = settings[preferencesKeyVal] ?:""
                timestampStr = (settings[preferencesKeyTs] ?:"")
            }
        }

        // Get the password value stored
        if (passwordStr.isEmpty())
            return ""

        // Password is defined: Check for password expiry:
        val timestamp = if (timestampStr.isEmpty()) 0L else timestampStr.toLong()
        if (!isInValidityWindow(timestamp, passwordValidityWindowSeconds))
            // Password validity ended - need to renew:
            // Clear/update password in data store
            return this.clearPasswordDataStore(context)

        // Store/update password and return
        return this.storePassword(context, passwordStr)
    }

}
