package app.aaps.plugins.automation.actions

import android.content.Context
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.notifications.NotificationUserMessage
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNewNotification
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.objects.extensions.asAnnouncement
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.automation.R
import app.aaps.plugins.automation.elements.InputString
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import app.aaps.plugins.automation.ui.TimerUtil
import dagger.android.HasAndroidInjector
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import org.json.JSONObject
import javax.inject.Inject

class ActionSettingsExport(injector: HasAndroidInjector) : Action(injector) {

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var context: Context
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var timerUtil: TimerUtil
    @Inject lateinit var config: Config
    @Inject lateinit var persistenceLayer: PersistenceLayer // From ActionNotification

    private val disposable = CompositeDisposable()

    var text = InputString()

    override fun friendlyName(): Int = app.aaps.core.ui.R.string.exportsettings
    override fun shortDescription(): String = rh.gs(R.string.exportsettings_message, text.value)
    @DrawableRes override fun icon(): Int = app.aaps.core.objects.R.drawable.ic_access_alarm_24dp

    //override fun isValid(): Boolean = true // empty will show app name
    override fun isValid(): Boolean = text.value.isNotEmpty()

    // From ActionNotification
    override fun doAction(callback: Callback) {
        val notification = NotificationUserMessage(text.value)
        rxBus.send(EventNewNotification(notification))
        disposable += persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = TE.asAnnouncement(text.value),
            timestamp = dateUtil.now(),
            action = app.aaps.core.data.ue.Action.EXPORT_SETTINGS,
            source = Sources.Automation,
            note = "Export_test" + text.value,
            listValues = listOf()
        ).subscribe()
        rxBus.send(EventRefreshOverview("ActionSettingsExport"))
        callback.result(instantiator.providePumpEnactResult().success(true).comment(app.aaps.core.ui.R.string.ok)).run()
    }


    override fun toJSON(): String {
        val data = JSONObject().put("text", text.value)
        return JSONObject()
            .put("type", this.javaClass.simpleName)
            .put("data", data)
            .toString()
    }

    override fun fromJSON(data: String): Action {
        val o = JSONObject(data)
        text.value = JsonHelper.safeGetString(o, "text", "")
        return this
    }

    override fun hasDialog(): Boolean = true

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(LabelWithElement(rh, rh.gs(R.string.export_settings_short), "", text))
            .build(root)
    }
}