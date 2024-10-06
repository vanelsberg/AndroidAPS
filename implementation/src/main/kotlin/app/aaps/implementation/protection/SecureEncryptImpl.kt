package app.aaps.implementation.protection

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.protection.SecureEncrypt
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.BooleanKey
import dagger.Reusable
import javax.inject.Inject

// Internal constant stings

@Reusable
class SecureEncryptImpl @Inject constructor(
    private var log: AAPSLogger,
    private val sp: SP
    ) : SecureEncrypt {

    // TODO: Implementation
    override fun dummy(context: Context) : Boolean {
        val exportPasswordStoreIsEnabled  = sp.getBoolean(BooleanKey.MaintenanceEnableExportSettingsAutomation.key, false)
        log.debug(LTag.CORE, "TODO: Executing Dummy function...")
        return false
    }

    /***
     * TODO: Preparing for encryption/decryption (needs additional implementation)
     */
    override fun encrypt(str: String): String {
        log.debug(LTag.CORE, "encrypting export password.")
        return str
    }

    override fun decrypt(str: String): String {
        log.debug(LTag.CORE, "decrypting export password.")
        return str
    }


    /*************************************************************************
     * Private functions
    *************************************************************************/

}
