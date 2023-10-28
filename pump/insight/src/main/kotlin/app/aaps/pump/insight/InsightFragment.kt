package app.aaps.pump.insight

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.pump.insight.descriptors.*
import app.aaps.pump.insight.events.EventLocalInsightUpdateGUI
import dagger.android.support.DaggerFragment
import info.nightscout.androidaps.insight.R
import info.nightscout.androidaps.insight.databinding.LocalInsightFragmentBinding
import io.reactivex.rxjava3.disposables.CompositeDisposable
import javax.inject.Inject

class InsightFragment : DaggerFragment(), View.OnClickListener {

    @Inject lateinit var insightPlugin: InsightPlugin
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var decimalFormatter: DecimalFormatter

    private var _binding: LocalInsightFragmentBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private val disposable = CompositeDisposable()
    private var operatingModeCallback: Callback? = null
    private var tbrOverNotificationCallback: Callback? = null
    private var refreshCallback: Callback? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = LocalInsightFragmentBinding.inflate(inflater, container, false)
        binding.tbrOverNotification.setOnClickListener(this)
        binding.operatingMode.setOnClickListener(this)
        binding.refresh.setOnClickListener(this)
        return binding.root
    }

    @Synchronized override fun onResume() {
        super.onResume()
        disposable.add(rxBus
                           .toObservable(EventLocalInsightUpdateGUI::class.java)
                           .observeOn(aapsSchedulers.main)
                           .subscribe({ updateGUI() }) { throwable: Throwable? -> fabricPrivacy.logException(throwable!!) }
        )
        updateGUI()
    }

    @Synchronized override fun onPause() {
        super.onPause()
        disposable.clear()
    }

    @Synchronized override fun onDestroy() {
        super.onDestroy()
        disposable.clear()
    }

    @Synchronized override fun onDestroyView() {
        super.onDestroyView()
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.operating_mode        -> {
                if (insightPlugin.operatingMode != null) {
                    binding.operatingMode.isEnabled = false
                    operatingModeCallback = object : Callback() {
                        override fun run() {
                            Handler(Looper.getMainLooper()).post {
                                operatingModeCallback = null
                                updateGUI()
                            }
                        }
                    }
                    when (insightPlugin.operatingMode) {
                        OperatingMode.PAUSED, OperatingMode.STOPPED -> commandQueue.startPump(operatingModeCallback)
                        OperatingMode.STARTED                       -> commandQueue.stopPump(operatingModeCallback)
                        null                                        -> Unit
                    }
                }
            }

            R.id.tbr_over_notification -> {
                val notificationBlock = insightPlugin.tBROverNotificationBlock
                if (notificationBlock != null) {
                    binding.tbrOverNotification.isEnabled = false
                    tbrOverNotificationCallback = object : Callback() {
                        override fun run() {
                            Handler(Looper.getMainLooper()).post {
                                tbrOverNotificationCallback = null
                                updateGUI()
                            }
                        }
                    }
                    commandQueue.setTBROverNotification(tbrOverNotificationCallback, !notificationBlock.isEnabled)
                }
            }

            R.id.refresh               -> {
                binding.refresh.isEnabled = false
                refreshCallback = object : Callback() {
                    override fun run() {
                        Handler(Looper.getMainLooper()).post {
                            refreshCallback = null
                            updateGUI()
                        }
                    }
                }
                commandQueue.readStatus("InsightRefreshButton", refreshCallback)
            }
        }
    }

    protected fun updateGUI() {
        _binding ?: return
        binding.statusItemContainer.removeAllViews()
        if (!insightPlugin.isInitialized()) {
            binding.operatingMode.visibility = View.GONE
            binding.tbrOverNotification.visibility = View.GONE
            binding.refresh.visibility = View.GONE
            return
        }
        binding.refresh.visibility = View.VISIBLE
        binding.refresh.isEnabled = refreshCallback == null
        val notificationBlock = insightPlugin.tBROverNotificationBlock
        binding.tbrOverNotification.visibility = if (notificationBlock == null) View.GONE else View.VISIBLE
        if (notificationBlock != null) binding.tbrOverNotification.setText(if (notificationBlock.isEnabled) R.string.disable_tbr_over_notification else R.string.enable_tbr_over_notification)
        binding.tbrOverNotification.isEnabled = tbrOverNotificationCallback == null
        val statusItems: MutableList<View> = ArrayList()
        getConnectionStatusItem(statusItems)
        getLastConnectedItem(statusItems)
        getOperatingModeItem(statusItems)
        getBatteryStatusItem(statusItems)
        getCartridgeStatusItem(statusItems)
        getTDDItems(statusItems)
        getBaseBasalRateItem(statusItems)
        getTBRItem(statusItems)
        getLastBolusItem(statusItems)
        getBolusItems(statusItems)
        for (i in statusItems.indices) {
            binding.statusItemContainer.addView(statusItems[i])
            if (i != statusItems.size - 1) layoutInflater.inflate(R.layout.local_insight_status_delimitter, binding.statusItemContainer)
        }
    }

    private fun getStatusItem(label: String, value: String): View {
        @SuppressLint("InflateParams") val statusItem = layoutInflater.inflate(R.layout.local_insight_status_item, null)
        (statusItem.findViewById<View>(R.id.label) as TextView).text = label
        (statusItem.findViewById<View>(R.id.value) as TextView).text = value
        return statusItem
    }

    private fun getConnectionStatusItem(statusItems: MutableList<View>) {
        insightPlugin.connectionService?.let {
            val string = when (it.state) {
                InsightState.NOT_PAIRED                 -> R.string.not_paired
                InsightState.DISCONNECTED               -> app.aaps.core.ui.R.string.disconnected
                InsightState.CONNECTING,
                InsightState.SATL_CONNECTION_REQUEST,
                InsightState.SATL_KEY_REQUEST,
                InsightState.SATL_SYN_REQUEST,
                InsightState.SATL_VERIFY_CONFIRM_REQUEST,
                InsightState.SATL_VERIFY_DISPLAY_REQUEST,
                InsightState.APP_ACTIVATE_PARAMETER_SERVICE,
                InsightState.APP_ACTIVATE_STATUS_SERVICE,
                InsightState.APP_BIND_MESSAGE,
                InsightState.APP_CONNECT_MESSAGE,
                InsightState.APP_FIRMWARE_VERSIONS,
                InsightState.APP_SYSTEM_IDENTIFICATION,
                InsightState.AWAITING_CODE_CONFIRMATION -> app.aaps.core.ui.R.string.connecting

                InsightState.CONNECTED                  -> app.aaps.core.interfaces.R.string.connected
                InsightState.RECOVERING                 -> R.string.recovering
            }
            statusItems.add(getStatusItem(rh.gs(R.string.insight_status), rh.gs(string)))
            if (it.state == InsightState.RECOVERING) {
                statusItems.add(getStatusItem(rh.gs(R.string.recovery_duration), (it.recoveryDuration / 1000).toString() + "s"))
            }
        }
    }

    private fun getLastConnectedItem(statusItems: MutableList<View>) {
        insightPlugin.connectionService?.let {
            when (it.state) {
                InsightState.CONNECTED, InsightState.NOT_PAIRED -> return

                else                                            -> {
                    val lastConnection = it.lastConnected
                    if (lastConnection == 0L) return
                    val agoMsc = System.currentTimeMillis() - lastConnection
                    val lastConnectionMinAgo = agoMsc / 60.0 / 1000.0
                    val ago: String = if (lastConnectionMinAgo < 60) {
                        dateUtil.minAgo(rh, lastConnection)
                    } else {
                        dateUtil.hourAgo(lastConnection, rh)
                    }
                    statusItems.add(getStatusItem(rh.gs(R.string.last_connected), dateUtil.timeString(lastConnection) + " (" + ago + ")")
                    )
                }
            }

        }
    }

    private fun getOperatingModeItem(statusItems: MutableList<View>) {
        if (insightPlugin.operatingMode == null) {
            binding.operatingMode.visibility = View.GONE
            return
        }
        var string = 0
        if (ENABLE_OPERATING_MODE_BUTTON) binding.operatingMode.visibility = View.VISIBLE
        binding.operatingMode.isEnabled = operatingModeCallback == null
        when (insightPlugin.operatingMode) {
            OperatingMode.STARTED -> {
                binding.operatingMode.setText(R.string.stop_pump)
                string = R.string.started
            }

            OperatingMode.STOPPED -> {
                binding.operatingMode.setText(R.string.start_pump)
                string = R.string.stopped
            }

            OperatingMode.PAUSED  -> {
                binding.operatingMode.setText(R.string.start_pump)
                string = app.aaps.core.ui.R.string.paused
            }

            null                  -> Unit
        }
        statusItems.add(getStatusItem(rh.gs(R.string.operating_mode), rh.gs(string)))
    }

    private fun getBatteryStatusItem(statusItems: MutableList<View>) {
        val batteryStatus = insightPlugin.batteryStatus ?: return
        statusItems.add(getStatusItem(rh.gs(app.aaps.core.ui.R.string.battery_label), batteryStatus.batteryAmount.toString() + "%"))
    }

    private fun getCartridgeStatusItem(statusItems: MutableList<View>) {
        val cartridgeStatus = insightPlugin.cartridgeStatus ?: return
        val status: String = if (cartridgeStatus.isInserted) decimalFormatter.to2Decimal(cartridgeStatus.remainingAmount) + "U" else rh.gs(R.string.not_inserted)
        statusItems.add(getStatusItem(rh.gs(app.aaps.core.ui.R.string.reservoir_label), status))
    }

    private fun getTDDItems(statusItems: MutableList<View>) {
        val tdd = insightPlugin.totalDailyDose ?: return
        statusItems.add(getStatusItem(rh.gs(R.string.tdd_bolus), decimalFormatter.to2Decimal(tdd.bolus)))
        statusItems.add(getStatusItem(rh.gs(R.string.tdd_basal), decimalFormatter.to2Decimal(tdd.basal)))
        statusItems.add(getStatusItem(rh.gs(app.aaps.core.ui.R.string.tdd_total), decimalFormatter.to2Decimal(tdd.bolusAndBasal)))
    }

    private fun getBaseBasalRateItem(statusItems: MutableList<View>) {
        val activeBasalRate = insightPlugin.activeBasalRate ?: return
        statusItems.add(getStatusItem(rh.gs(app.aaps.core.ui.R.string.base_basal_rate_label),decimalFormatter.to2Decimal(activeBasalRate.activeBasalRate) + " U/h (" + activeBasalRate.activeBasalProfileName + ")"))
    }

    private fun getTBRItem(statusItems: MutableList<View>) {
        val activeTBR = insightPlugin.activeTBR ?: return
        statusItems.add(getStatusItem(rh.gs(app.aaps.core.ui.R.string.tempbasal_label),rh.gs(R.string.tbr_formatter, activeTBR.percentage, activeTBR.initialDuration - activeTBR.remainingDuration, activeTBR.initialDuration)))
    }

    private fun getLastBolusItem(statusItems: MutableList<View>) {
        if (insightPlugin.lastBolusAmount.equals(0.0) || insightPlugin.lastBolusTimestamp.equals(0L)) return
        val agoMsc = System.currentTimeMillis() - insightPlugin.lastBolusTimestamp
        val bolusMinAgo = agoMsc / 60.0 / 1000.0
        val unit = rh.gs(app.aaps.core.ui.R.string.insulin_unit_shortname)
        val ago: String
        ago = if (bolusMinAgo < 60) {
            dateUtil.minAgo(rh, insightPlugin.lastBolusTimestamp)
        } else {
            dateUtil.hourAgo(insightPlugin.lastBolusTimestamp, rh)
        }
        statusItems.add(
            getStatusItem(
                rh.gs(R.string.insight_last_bolus),
                rh.gs(R.string.insight_last_bolus_formater, insightPlugin.lastBolusAmount, unit, ago)
            )
        )
    }

    private fun getBolusItems(statusItems: MutableList<View>) {
        insightPlugin.activeBoluses?.forEach { activeBolus ->
            val label: String?
            label = when (activeBolus.bolusType) {
                BolusType.MULTIWAVE -> rh.gs(R.string.multiwave_bolus)
                BolusType.EXTENDED  -> rh.gs(app.aaps.core.ui.R.string.extended_bolus)
                else                -> null
            }
            label?.let {
                statusItems.add(getStatusItem(it, rh.gs(R.string.eb_formatter, activeBolus.remainingAmount, activeBolus.initialAmount, activeBolus.remainingDuration)))
            }
        }
    }

    companion object {

        private const val ENABLE_OPERATING_MODE_BUTTON = false
    }
}