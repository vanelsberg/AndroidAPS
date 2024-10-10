package app.aaps.implementation.protection

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.protection.ExportPasswordDataStore
import app.aaps.core.interfaces.protection.SecureEncrypt
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

//@Reusable
@Singleton
class ExportPasswordDataStoreImpl @Inject constructor(
    private var log: AAPSLogger,
    private var sp: SP,
    private var config: Config
    ) : ExportPasswordDataStore {

    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var secureEncrypt: SecureEncrypt

    // TODO: Remove for release (Debug only!)
    @Inject lateinit var fileListProvider: FileListProvider

    companion object {
        // Internal constant stings
        const val MODULE = "ExportPasswordDataStore"
        // KeyStore alias name to use for encrypting
        const val KEYSTORE_ALIAS = "UnattendedExportAlias01"
        // Use local phones data store to keep password/expiry state
        const val DATASTORE_NAME: String = "app.aaps.plugins.configuration.maintenance.ImportExport.datastore"
        const val PASSWORD_PREFERENCE_NAME = "$DATASTORE_NAME.password_value"

        // On enabling & password expiry
        private var exportPasswordStoreIsEnabled    = false                   // Set from prefs, disabled by default
        private var passwordValidityWindow: Long    = 35 * 24 * 3600 * 1000L  // 5 weeks (including grace period)
        private var passwordExpiryGracePeriod: Long =  7 * 24 * 3600 * 1000L  // 1 week
    }

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
        name = DATASTORE_NAME
    )



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
     * Check if ExportPasswordDataStore is enabled
     * Returns true when Export password store is enabled.
     * see also:
     * - var passwordValidityWindow
     * - var passwordExpiryGracePeriod
     */
    override fun exportPasswordStoreEnabled() : Boolean {
        // Is password storing enabled?
        exportPasswordStoreIsEnabled  = sp.getBoolean(BooleanKey.MaintenanceEnableExportSettingsAutomation.key, false)
        if (!exportPasswordStoreIsEnabled) return false // Easy, done!

        // Use fixed defaults for password validity window, optional overrule defaults from prefs:
        // passwordValidityWindowSeconds = sp.getLong(IntKey.AutoExportPasswordExpiryDays.key, 35) * 24 * 3600 * 1000

        if (config.isEngineeringMode() && config.isDev()) {
            // TODO: To be removed for final release?
            // Enable debug mode when file 'DebugUnattendedExport' exists
            val debug = File(fileListProvider.ensureExtraDirExists(), "DebugUnattendedExport").exists()
            if (debug) {
                log.warn(LTag.CORE, "$MODULE: ExportPasswordDataStore running DEBUG mode!")
                /*** Debug/testing mode ***/
                passwordValidityWindow = 20 * 60 * 1000L                // Valid for 20 min
                passwordExpiryGracePeriod = passwordValidityWindow/2    // Grace period 10 min
                // passwordValidityWindowSeconds = 2 * 24 * 3600 * 1000L           // 2 Days (including grace periodee)
                // passwordExpiryGracePeriod     = passwordValidityWindowSeconds/2 // // Grace period 1 days
            }
        }
        // END

        log.info(LTag.CORE, "$MODULE: ExportPasswordDataStore is enabled: $exportPasswordStoreIsEnabled, expiry msecs=$passwordValidityWindow")
        return exportPasswordStoreIsEnabled
    }

    /***
     * Clear password currently stored to "empty"
     */
    override fun clearPasswordDataStore(context: Context): String {
        if (!exportPasswordStoreEnabled()) return "" // Do nothing, return empty

        // Store & update to empty password and return
        log.debug(LTag.CORE, "$MODULE: clearPasswordDataStore")
        return this.clearPassword(context)
    }

    /***
     * Put password to local phone's datastore
     * Return: password
     */
    override fun putPasswordToDataStore(context: Context, password: String): String {
        if (!exportPasswordStoreEnabled()) return password // Just return the password
        log.debug(LTag.CORE, "$MODULE: putPasswordToDataStore")
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
            log.debug(LTag.CORE, "$MODULE: getPasswordFromDataStore")
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

        /** TODO: This makes no sense?
        // Clear empty password
        secureEncrypt.encrypt("", keyStoreAlias)
        */

        // Clear password stored
        return updatePrefString(PASSWORD_PREFERENCE_NAME)
    }


    /***
     * Store password and set timestamp to current
     */
    private fun storePassword(context: Context, password: String): String {

        // Write encrypted password key and timestamp to the local phone's android datastore and return password
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
        return updatePrefString(PASSWORD_PREFERENCE_NAME, secureEncrypt.encrypt(password, KEYSTORE_ALIAS))
    }

    /***
     * Retrieve password from local phone's data store.
     * Reset password when validity expired
    ***/
    private fun retrievePassword(context: Context): ClassPasswordData {

        // Read encrypted password key and timestamp from the local phone's android datastore and return password
        var passwordStr = ""
        var timestampStr = ""

        runBlocking {
            val keyName = PASSWORD_PREFERENCE_NAME
            val preferencesKeyVal = stringPreferencesKey("$keyName.key")
            val preferencesKeyTs = stringPreferencesKey("$keyName.ts")
            context.dataStore.edit { settings ->
                passwordStr = settings[preferencesKeyVal] ?:""
                timestampStr = (settings[preferencesKeyTs] ?:"")
            }
        }

        val classPasswordData = ClassPasswordData(
            password = passwordStr,
            timestamp = if (timestampStr.isEmpty()) 0L else timestampStr.toLong(),
            isExpired = true,
            isAboutToExpire =  true
        )

        // Get the password value stored
        if (classPasswordData.password.isEmpty())
            return classPasswordData

        // Password is defined: Check for password expiry:
        val (expired, expires) = isInValidityWindow(classPasswordData.timestamp, passwordValidityWindow, passwordExpiryGracePeriod)
        classPasswordData.isExpired = expired
        classPasswordData.isAboutToExpire = expires

        if (classPasswordData.isExpired)
            // Password validity ended - need to renew:
            // Clear/update password in data store
            classPasswordData.password = this.clearPasswordDataStore(context)

        // Store/update password and return
        return classPasswordData
    }

}
