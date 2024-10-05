package app.aaps.implementation.protection

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.protection.ExportPasswordDataStore
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
// import app.aaps.core.keys.IntKey
import dagger.Reusable
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.inject.Inject

// Internal constant stings
const val datastoreName : String = "app.aaps.plugins.configuration.maintenance.ImportExport.datastore"
const val passwordPreferenceName = "$datastoreName.password_value"

@Reusable
class ExportPasswordDataStoreImpl @Inject constructor(
    private var log: AAPSLogger,
    private val sp: SP
    ) : ExportPasswordDataStore {

    @Inject lateinit var dateUtil: DateUtil

    // TODO: Remove for release (Debug only!)
    @Inject lateinit var fileListProvider: FileListProvider


    // TODO: Review security aspects on temporarily storing password in phone's local data store

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
        name = datastoreName
    )

    // On enabling & password expiry
    private var exportPasswordStoreIsEnabled = false
    private var passwordValidityWindowSeconds: Long = 0 // Read from settings
    private var passwordExpiryGracePeriod: Long = 0     // Set on enable

    /***
     * Data class holding password attributes
     */
    data class ClassPasswordData(
        var password: String,
        var timestamp: Long,
        var isExpired: Boolean,
        var isAboutToExpire: Boolean
    )

    /***
     * Check Export password functionality
     * Returns true when Export password store is enabled.
     */
    override fun exportPasswordStoreEnabled() : Boolean {
        // TODO: Remove for release (Debug only!)
        val debug = File(fileListProvider.ensureExtraDirExists(), "DebugUnattendedExport").exists()

        // Is password storing enabled?
        exportPasswordStoreIsEnabled  = sp.getBoolean(BooleanKey.MaintenanceEnableExportSettingsAutomation.key, false)
        // Set password expiry weeks
        if (exportPasswordStoreIsEnabled) if (!debug) {
            // Password validity window (default should be 5 weeks, minimum 1 week)
            // passwordValidityWindowSeconds = sp.getLong(IntKey.AutoExportPasswordExpiryDays.key, 35) * 24 * 3600 * 1000
            passwordValidityWindowSeconds = 35 * 24 * 3600 * 1000L   // 5 weeks (including grace period)
            passwordExpiryGracePeriod     =  7 * 24 * 3600 * 1000L   // 1 week
        } else {
            /*** Debug mode ***/
            // passwordValidityWindowSeconds = 20 * 60 * 1000L // Valid for 20 min
            // passwordExpiryGracePeriod = 10 * 60 * 1000L // Grace period 10 min
            passwordValidityWindowSeconds = 2 * 24 * 3600 * 1000L // 2 Days (including grace periodee)
            passwordExpiryGracePeriod     = 1 * 24 * 3600 * 1000L // 1 Day
        }

        log.debug(LTag.CORE, "ExportPassword Store Supported: $exportPasswordStoreIsEnabled, expiry days=$passwordValidityWindowSeconds")
        return exportPasswordStoreIsEnabled
    }

    /***
     * Clear password currently stored to "empty"
     */
    override fun clearPasswordDataStore(context: Context): String {
        // TODO: For now always clear - also when general functionality is disabled?
        // if (!exportPasswordStoreEnabled()) return ""

        log.debug(LTag.CORE, "clearPasswordDataStore")
        // Store & update to empty password and return
        return this.clearPassword(context)
    }

    /***
     * Put password to local phone's datastore
     */
    override fun putPasswordToDataStore(context: Context, password: String): String {
        if (!exportPasswordStoreEnabled()) return ""
        log.debug(LTag.CORE, "putPasswordToDataStore")
        return this.storePassword(context, password)
    }

    /***
     * Get password from local phone's data store
     * Return Triple (ok, password string, isExpired, isAboutToExpire)
     */
    override fun getPasswordFromDataStore(context: Context): Triple<String, Boolean, Boolean> {
        if (!exportPasswordStoreEnabled()) return Triple ("", true, true)

        val passwordData = this.retrievePassword(context)
        if (passwordData.password.isNotEmpty()) {  // And not expired
            log.debug(LTag.CORE, "getPasswordFromDataStore")
            // return Triple(true, passwordData.password, passwordData.isAboutToExpire)
            return Triple(passwordData.password, passwordData.isExpired, passwordData.isAboutToExpire)
        }
        return Triple ("", true, true)
    }

    /*************************************************************************
     * Private functions
    *************************************************************************/

    /***
     * Check if timestamp is in validity window T...T+duration
     */
    private fun isInValidityWindow(timestamp: Long, duration: Long?, gracePeriod: Long?):Pair<Boolean, Boolean> {
        val expired = dateUtil.now() !in timestamp..timestamp + (duration ?: 0L)
        val expires = dateUtil.now() !in timestamp..timestamp + (duration ?: 0L) - (gracePeriod ?: 0L)
        return Pair (expired, expires)
    }

    /***
     * Clear password and timestamp
     */
    private fun clearPassword(context: Context): String {

        // Write setting to android datastore and return password
        fun updatePrefString(name: String)  = runBlocking {
            val preferencesKeyPassword = stringPreferencesKey("$name.key")
            val preferencesKeyTimestamp = stringPreferencesKey("$name.ts")
            context.dataStore.edit { settings ->
                // Clear password as string value
                settings[preferencesKeyPassword] = ""
                settings[preferencesKeyTimestamp] = "0"
            }[preferencesKeyPassword].toString()
        }

        // Update & return password string
        return updatePrefString(passwordPreferenceName)
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
                // If current password is empty, update to new timestamp "now" or else leave it
                settings[preferencesKeyTimestamp] = dateUtil.now().toString()
                // Update password as string value
                settings[preferencesKeyPassword] = str
            }[preferencesKeyPassword].toString()
        }

        // Update & return password string
        return updatePrefString(passwordPreferenceName, encrypt(password))
    }

    /***
     * Retrieve password from local phone's data store.
     * Reset password when validity expired
    ***/
    private fun retrievePassword(context: Context): ClassPasswordData {

        // Read string value from phone's local datastore using key name
        var passwordStr = ""
        var timestampStr = ""

        runBlocking {
            val keyName = passwordPreferenceName
            val preferencesKeyVal = stringPreferencesKey("$keyName.key")
            val preferencesKeyTs = stringPreferencesKey("$keyName.ts")
            context.dataStore.edit { settings ->
                passwordStr = settings[preferencesKeyVal] ?:""
                timestampStr = (settings[preferencesKeyTs] ?:"")
            }
        }

        val classPasswordData = ClassPasswordData(
            password = decrypt(passwordStr),
            timestamp = if (timestampStr.isEmpty()) 0L else timestampStr.toLong(),
            isExpired = true,
            isAboutToExpire =  true
        )

        // Get the password value stored
        if (classPasswordData.password.isEmpty())
            return classPasswordData

        // Password is defined: Check for password expiry:
        val (expired, expires) = isInValidityWindow(classPasswordData.timestamp, passwordValidityWindowSeconds, passwordExpiryGracePeriod)
        classPasswordData.isExpired = expired
        classPasswordData.isAboutToExpire = expires

        if (classPasswordData.isExpired)
            // Password validity ended - need to renew:
            // Clear/update password in data store
            classPasswordData.password = this.clearPasswordDataStore(context)

        // Store/update password and return
        return classPasswordData
    }

    /***
     * TODO: Preparing for encryption/decryption (needs additional implementation)
     */
    private fun encrypt(str: String): String {
        return str
    }

    private fun decrypt(str: String): String {
        return str
    }

}
