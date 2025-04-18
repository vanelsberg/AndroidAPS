package app.aaps.pump.diaconn.packet

import app.aaps.core.interfaces.logging.LTag
import dagger.android.HasAndroidInjector
import app.aaps.pump.diaconn.DiaconnG8Pump
import javax.inject.Inject

/**
 * DisplayTimeoutSettingPacket
 */
class DisplayTimeoutSettingPacket(
    injector: HasAndroidInjector,
    private var type: Int
) : DiaconnG8Packet(injector) {

    @Inject lateinit var diaconnG8Pump: DiaconnG8Pump

    init {
        msgType = 0x0E
        aapsLogger.debug(LTag.PUMPCOMM, "DisplayTimeoutSettingPacket init")
    }

    override fun encode(msgSeq: Int): ByteArray {
        val buffer = prefixEncode(msgType, msgSeq, MSG_CON_END)
        buffer.put(type.toByte()) // cmd
        return suffixEncode(buffer)
    }

    override val friendlyName = "PUMP_DISPLAY_TIMEOUT_SETTING_PACKET"
}