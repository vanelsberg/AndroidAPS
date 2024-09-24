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
import dagger.Reusable
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@Reusable
class ExportPasswordCheckImpl @Inject constructor(
    private var log: AAPSLogger
) : ExportPasswordCheck {

    // Draft: in final impl move this to local secure storage
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app.aaps.plugins.configuration.maintenance.ImportExport.datastore")
    private val passwordPreferenceKeyName = "app.aaps.plugins.configuration.maintenance.ImportExport.datastore.password_key"
    private var passwordExpired: Boolean = false

    override fun clearPasswordSecureStore(context: Context) {
        this.storePassword(context, "")
    }

    override fun putPasswordToSecureStore(context: Context, password: String): String {
        this.storePassword(context, password)
        log.debug(LTag.CORE, "putPasswordToSecureStore")
        return password
    }

    override fun getPasswordFromSecureStore(context: Context): Pair<Boolean, String> {
        val password = this.retrievePassword(context)

        if (password.isNotEmpty()) {  // And not expired
            log.debug(LTag.CORE, "getPasswordFromSecureStore")
            return Pair(true, password)
        }
        return Pair (false, "")
    }
    // Testing: in final impl move this to local secure storage

    private fun storePassword(context: Context, password: String): String {
        // Write setting to android datastore
        val preferencesKey = stringPreferencesKey(passwordPreferenceKeyName)
        fun updateString()  = runBlocking {
            context.dataStore.edit { settings ->
                settings[preferencesKey] = password
            }[preferencesKey].toString()
        }
        val s1: String = updateString()
        return s1
    }

    private fun retrievePassword(context: Context): String {
        // Check for password is expired flag
        if (this.passwordExpired) {
            // Password expired: return empty and reset flag
            this.passwordExpired = false
            return ""
        }

        // Read setting form android datastore
        val preferencesKey = stringPreferencesKey(passwordPreferenceKeyName)
        fun getString() = runBlocking {
            (context.dataStore.edit { settings ->
                settings[preferencesKey] ?:""
            }[preferencesKey] ?:"").toString()
        }
        val password : String = getString()
        return password
    }


}
