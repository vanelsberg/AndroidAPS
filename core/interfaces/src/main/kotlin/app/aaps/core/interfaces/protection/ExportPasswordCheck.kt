package app.aaps.core.interfaces.protection

import android.content.Context

interface ExportPasswordCheck {

    fun clearPasswordSecureStore(context: Context)

    fun putPasswordToSecureStore(context: Context, password: String): String

    fun getPasswordFromSecureStore(context: Context): Pair<Boolean, String>

}