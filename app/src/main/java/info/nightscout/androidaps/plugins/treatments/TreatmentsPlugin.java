package info.nightscout.androidaps.plugins.treatments;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.analytics.FirebaseAnalytics;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.android.HasAndroidInjector;
import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.activities.ErrorHelperActivity;
import info.nightscout.androidaps.data.DetailedBolusInfo;
import info.nightscout.androidaps.data.Intervals;
import info.nightscout.androidaps.data.Iob;
import info.nightscout.androidaps.data.IobTotal;
import info.nightscout.androidaps.data.NonOverlappingIntervals;
import info.nightscout.androidaps.data.OverlappingIntervals;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.data.ProfileIntervals;
import info.nightscout.androidaps.db.ExtendedBolus;
import info.nightscout.androidaps.db.ProfileSwitch;
import info.nightscout.androidaps.db.Source;
import info.nightscout.androidaps.db.TempTarget;
import info.nightscout.androidaps.db.TemporaryBasal;
import info.nightscout.androidaps.db.Treatment;
import info.nightscout.androidaps.events.EventReloadProfileSwitchData;
import info.nightscout.androidaps.events.EventReloadTempBasalData;
import info.nightscout.androidaps.events.EventReloadTreatmentData;
import info.nightscout.androidaps.events.EventTempTargetChange;
import info.nightscout.androidaps.interfaces.ActivePluginProvider;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.interfaces.ProfileFunction;
import info.nightscout.androidaps.interfaces.ProfileStore;
import info.nightscout.androidaps.interfaces.PumpInterface;
import info.nightscout.androidaps.interfaces.TreatmentsInterface;
import info.nightscout.androidaps.logging.AAPSLogger;
import info.nightscout.androidaps.logging.LTag;
import info.nightscout.androidaps.plugins.bus.RxBusWrapper;
import info.nightscout.androidaps.plugins.general.nsclient.NSUpload;
import info.nightscout.androidaps.plugins.general.nsclient.UploadQueue;
import info.nightscout.androidaps.plugins.general.overview.events.EventDismissNotification;
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.AutosensResult;
import info.nightscout.androidaps.plugins.pump.medtronic.MedtronicPumpPlugin;
import info.nightscout.androidaps.plugins.pump.medtronic.data.MedtronicHistoryData;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.FabricPrivacy;
import info.nightscout.androidaps.utils.T;
import info.nightscout.androidaps.utils.resources.ResourceHelper;
import info.nightscout.androidaps.utils.sharedPreferences.SP;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;

@Singleton
public class TreatmentsPlugin extends PluginBase implements TreatmentsInterface {

    private final Context context;
    private final SP sp;
    private final RxBusWrapper rxBus;
    private final ResourceHelper resourceHelper;
    private final ProfileFunction profileFunction;
    private final ActivePluginProvider activePlugin;
    private final NSUpload nsUpload;
    private final UploadQueue uploadQueue;
    private final FabricPrivacy fabricPrivacy;
    private final DateUtil dateUtil;

    private final CompositeDisposable disposable = new CompositeDisposable();

    protected TreatmentService service;

    private IobTotal lastTreatmentCalculation;
    private IobTotal lastTempBasalsCalculation;

    private final ArrayList<Treatment> treatments = new ArrayList<>();
    private final Intervals<TemporaryBasal> tempBasals = new NonOverlappingIntervals<>();
    private final Intervals<ExtendedBolus> extendedBoluses = new NonOverlappingIntervals<>();
    private final Intervals<TempTarget> tempTargets = new OverlappingIntervals<>();
    private final ProfileIntervals<ProfileSwitch> profiles = new ProfileIntervals<>();

    @Inject
    public TreatmentsPlugin(
            HasAndroidInjector injector,
            AAPSLogger aapsLogger,
            RxBusWrapper rxBus,
            ResourceHelper resourceHelper,
            Context context,
            SP sp,
            ProfileFunction profileFunction,
            ActivePluginProvider activePlugin,
            NSUpload nsUpload,
            FabricPrivacy fabricPrivacy,
            DateUtil dateUtil,
            UploadQueue uploadQueue
    ) {
        super(new PluginDescription()
                        .mainType(PluginType.TREATMENT)
                        .fragmentClass(TreatmentsFragment.class.getName())
                        .pluginIcon(R.drawable.ic_treatments)
                        .pluginName(R.string.treatments)
                        .shortName(R.string.treatments_shortname)
                        .alwaysEnabled(true)
                        .description(R.string.description_treatments)
                        .setDefault(),
                aapsLogger, resourceHelper, injector
        );
        this.resourceHelper = resourceHelper;
        this.context = context;
        this.rxBus = rxBus;
        this.sp = sp;
        this.profileFunction = profileFunction;
        this.activePlugin = activePlugin;
        this.fabricPrivacy = fabricPrivacy;
        this.dateUtil = dateUtil;
        this.nsUpload = nsUpload;
        this.uploadQueue = uploadQueue;
    }

    @Override
    protected void onStart() {
        this.service = new TreatmentService(getInjector());
        initializeData(range());
        super.onStart();
        disposable.add(rxBus
                .toObservable(EventReloadTreatmentData.class)
                .observeOn(Schedulers.io())
                .subscribe(event -> {
                            getAapsLogger().debug(LTag.DATATREATMENTS, "EventReloadTreatmentData");
                            initializeTreatmentData(range());
                            initializeExtendedBolusData(range());
                            updateTotalIOBTreatments();
                            rxBus.send(event.getNext());
                        },
                        fabricPrivacy::logException
                ));
        disposable.add(rxBus
                .toObservable(EventReloadProfileSwitchData.class)
                .observeOn(Schedulers.io())
                .subscribe(event -> initializeProfileSwitchData(range()),
                        fabricPrivacy::logException
                ));
        disposable.add(rxBus
                .toObservable(EventTempTargetChange.class)
                .observeOn(Schedulers.io())
                .subscribe(event -> initializeTempTargetData(range()),
                        fabricPrivacy::logException
                ));
        disposable.add(rxBus
                .toObservable(EventReloadTempBasalData.class)
                .observeOn(Schedulers.io())
                .subscribe(event -> {
                            getAapsLogger().debug(LTag.DATATREATMENTS, "EventReloadTempBasalData");
                            initializeTempBasalData(range());
                            updateTotalIOBTempBasals();
                        },
                        fabricPrivacy::logException
                ));
    }

    @Override
    protected void onStop() {
        disposable.clear();
        super.onStop();
    }

    public TreatmentService getService() {
        // Return the service
        return this.service;
    }

    protected long range() {
        // Get the default DIA
        double dia = Constants.defaultDIA;
        // If the profile is not null
        if (profileFunction.getProfile() != null)
            // Get the DIA from the profile
            dia = profileFunction.getProfile().getDia();
        // Return the range
        return (long) (60 * 60 * 1000L * (24 + dia));
    }

    public void initializeData(long range) {
        // Initialize the temporary basal data
        initializeTempBasalData(range);
        // Initialize the treatment data
        initializeTreatmentData(range);
        // Initialize the extended bolus data
        initializeExtendedBolusData(range);
        // Initialize the temporary target data
        initializeTempTargetData(range);
        // Initialize the profile switch data
        initializeProfileSwitchData(range);
    }

    private void initializeTreatmentData(long range) {
        // Get the logger
        getAapsLogger().debug(LTag.DATATREATMENTS, "initializeTreatmentData");
        // Acquire the lock to the treatments
        synchronized (treatments) {
            // Clear the treatments
            treatments.clear();
            // Get the treatments from the service
            treatments.addAll(getService().getTreatmentDataFromTime(DateUtil.now() - range, false));
        }
    }

    private void initializeTempBasalData(long range) {
        // Get the logger
        getAapsLogger().debug(LTag.DATATREATMENTS, "initializeTempBasalData");
        // Acquire the lock to the temp basals
        synchronized (tempBasals) {
            tempBasals.reset().add(MainApp.getDbHelper().getTemporaryBasalsDataFromTime(DateUtil.now() - range, false));
        }

    }

    private void initializeExtendedBolusData(long range) {
        // Get the logger
        getAapsLogger().debug(LTag.DATATREATMENTS, "initializeExtendedBolusData");
        // Acquire the lock to extendedBoluses
        synchronized (extendedBoluses) {
            extendedBoluses.reset().add(MainApp.getDbHelper().getExtendedBolusDataFromTime(DateUtil.now() - range, false));
        }

    }

    private void initializeTempTargetData(long range) {
        // Get the logger
        getAapsLogger().debug(LTag.DATATREATMENTS, "initializeTempTargetData");
        // Acquire the lock to the tempTargets
        synchronized (tempTargets) {
            tempTargets.reset().add(MainApp.getDbHelper().getTemptargetsDataFromTime(DateUtil.now() - range, false));
        }
    }

    private void initializeProfileSwitchData(long range) {
        // Get the logger
        getAapsLogger().debug(LTag.DATATREATMENTS, "initializeProfileSwitchData");
        // Acquire the lock to the profiles
        synchronized (profiles) {
            profiles.reset().add(MainApp.getDbHelper().getProfileSwitchData(DateUtil.now() - range, false));
        }
    }

    @Override
    public IobTotal getLastCalculationTreatments() {
        // Return the lastTreatmentCalculation
        return lastTreatmentCalculation;
    }

    @Override
    public IobTotal getCalculationToTimeTreatments(long time) {
        // Create a new IobTotal
        IobTotal total = new IobTotal(time);

        // Get the profile
        Profile profile = profileFunction.getProfile();
        // If the profile is null, return the IobTotal
        if (profile == null)
            return total;

        // Get the active pump
        PumpInterface pumpInterface = activePlugin.getActivePump();

        // Get the pump's DIA
        double dia = profile.getDia();

        // Acquire the lock to the treatments
        synchronized (treatments) {
            // Iterate over all treatments
            for (int pos = 0; pos < treatments.size(); pos++) {
                // Get the treatment
                Treatment t = treatments.get(pos);
                if (!t.isValid) continue;
                if (t.date > time) continue;
                Iob tIOB = t.iobCalc(time, dia);
                // Add the IOB to the total
                total.iob += tIOB.iobContrib;
                // Add the activity to the total
                total.activity += tIOB.activityContrib;
                // Check if the treatment is a bolus
                if (t.insulin > 0 && t.date > total.lastBolusTime)
                    // If it is, set the last bolus time to the treatment's date
                    total.lastBolusTime = t.date;
                // Check if the treatment is not a SMB
                if (!t.isSMB) {
                    // instead of dividing the DIA that only worked on the bilinear curves,
                    // multiply the time the treatment is seen active.
                    long timeSinceTreatment = time - t.date;
                    // Calculate the snooze time
                    long snoozeTime = t.date + (long) (timeSinceTreatment * sp.getDouble(R.string.key_openapsama_bolussnooze_dia_divisor, 2.0));
                    // Calculate the IOB of the treatment
                    Iob bIOB = t.iobCalc(snoozeTime, dia);
                    // Add the IOB to the total
                    total.bolussnooze += bIOB.iobContrib;
                }
            }
        }

        // Check if the pump is not faking temps by extended boluses
        if (!pumpInterface.isFakingTempsByExtendedBoluses())
            // Acquire the lock to the extended boluses
            synchronized (extendedBoluses) {
                // Iterate over all extended boluses
                for (int pos = 0; pos < extendedBoluses.size(); pos++) {
                    // Get the extended bolus
                    ExtendedBolus e = extendedBoluses.get(pos);
                    if (e.date > time) continue;
                    IobTotal calc = e.iobCalc(time, profile);
                    // Add the IOB to the total
                    total.plus(calc);
                }
            }
        // Return the IobTotal
        return total;
    }

    @Override
    public void updateTotalIOBTreatments() {
        // Get the calculation to time treatments
        lastTreatmentCalculation = getCalculationToTimeTreatments(System.currentTimeMillis());
    }

    @Override
    public List<Treatment> getTreatmentsFromHistory() {
        // Acquire the lock to the treatments
        synchronized (treatments) {
            // Return a new list of treatments
            return new ArrayList<>(treatments);
        }
    }


    /**
     * Returns all Treatments after specified timestamp. Also returns invalid entries (required to
     * map "Fill Canula" entries to history (and not to add double bolus for it)
     *
     * @param fromTimestamp
     * @return
     */
    @Override
    public List<Treatment> getTreatmentsFromHistoryAfterTimestamp(long fromTimestamp) {
        // Create a new list of treatments
        List<Treatment> in5minback = new ArrayList<>();

        // Get the current time
        long time = System.currentTimeMillis();
        // Acquire the lock to the treatments
        synchronized (treatments) {
//            getAapsLogger().debug(MedtronicHistoryData.doubleBolusDebug, LTag.DATATREATMENTS, "DoubleBolusDebug: AllTreatmentsInDb: " + new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create().toJson(treatments));

            for (Treatment t : treatments) {
                // Check if the treatment is in the 5 minute back from history window
                if (t.date <= time && t.date >= fromTimestamp)
                    // If it is, add it to the list of treatments in 5 minutes back
                    in5minback.add(t);
            }
//            getAapsLogger().debug(MedtronicHistoryData.doubleBolusDebug, LTag.DATATREATMENTS, "DoubleBolusDebug: FilteredTreatments: AfterTime={}, Items={} " + fromTimestamp + " " + new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create().toJson(in5minback));
            return in5minback;
        }
    }


    @Override
    public List<Treatment> getCarbTreatments5MinBackFromHistory(long time) {
        // Create a new list of treatments
        List<Treatment> in5minback = new ArrayList<>();
        // Acquire the lock to the treatments
        synchronized (treatments) {
            // Iterate over all treatments
            for (Treatment t : treatments) {
                // Check if the treatment is valid
                if (!t.isValid)
                    // If not, continue to the next treatment
                    continue;
                // Check if the treatment is in the 5 minute back from history window
                if (t.date <= time && t.date > time - 5 * 60 * 1000 && t.carbs > 0)
                    // If it is, add it to the list of treatments in 5 minutes back
                    in5minback.add(t);
            }
            // Return the list of treatments in 5 minutes back
            return in5minback;
        }
    }

    @Override
    public long getLastBolusTime() {
        // Get the last treatment
        Treatment last = getService().getLastBolus(false);
        // If last is null, return 0
        if (last == null) {
            // Log
            getAapsLogger().debug(LTag.DATATREATMENTS, "Last bolus time: NOTHING FOUND");
            // Return 0
            return 0;
        } else {
            // Log
            getAapsLogger().debug(LTag.DATATREATMENTS, "Last bolus time: " + dateUtil.dateAndTimeString(last.date));
            // Return the date
            return last.date;
        }
    }

    public long getLastBolusTime(boolean excludeSMB) {
        // Get the last bolus treatment
        Treatment last = getService().getLastBolus(excludeSMB);
        // If last is null, return 0
        if (last == null) {
            // Log that nothing was found
            getAapsLogger().debug(LTag.DATATREATMENTS, "Last manual bolus time: NOTHING FOUND");
            // Return 0
            return 0;
        } else {
            // Log the last bolus time
            getAapsLogger().debug(LTag.DATATREATMENTS, "Last manual bolus time: " + dateUtil.dateAndTimeString(last.date));
            // Return the last bolus time
            return last.date;
        }
    }

    public long getLastCarbTime() {
        // Get the last treatment from the service
        Treatment last = getService().getLastCarb();
        // If last is null, return 0
        if (last == null) {
            // Log that nothing was found
            getAapsLogger().debug(LTag.DATATREATMENTS, "Last Carb time: NOTHING FOUND");
            // Return 0
            return 0;
        } else {
            // Log the last treatment date
            getAapsLogger().debug(LTag.DATATREATMENTS, "Last Carb time: " + dateUtil.dateAndTimeString(last.date));
            // Return the last treatment date
            return last.date;
        }
    }


    @Override
    public boolean isInHistoryRealTempBasalInProgress() {
        return getRealTempBasalFromHistory(System.currentTimeMillis()) != null;
    }

    @Override
    public TemporaryBasal getRealTempBasalFromHistory(long time) {
        // Acquire the lock to the temp basals
        synchronized (tempBasals) {
            // Return the temp basal that is in the interval of time
            return tempBasals.getValueByInterval(time);
        }
    }

    @Override
    public boolean isTempBasalInProgress() {
        return getTempBasalFromHistory(System.currentTimeMillis()) != null;
    }

    @Override public void removeTempBasal(TemporaryBasal tempBasal) {
        // Get the temporary basal ID
        String tempBasalId = tempBasal._id;
        // If the temporary basal ID is valid
        if (NSUpload.isIdValid(tempBasalId)) {
            // Remove the temporary basal from Nightscout
            nsUpload.removeCareportalEntryFromNS(tempBasalId);
        } else {
            // Otherwise, remove it from the upload queue
            uploadQueue.removeID("dbAdd", tempBasalId);
        }
        // Delete the temporary basal from the database
        MainApp.getDbHelper().delete(tempBasal);
    }

    @Override
    public boolean isInHistoryExtendedBoluslInProgress() {
        return getExtendedBolusFromHistory(System.currentTimeMillis()) != null; //TODO:  crosscheck here
    }

    @Override
    public IobTotal getLastCalculationTempBasals() {
        // Return the lastTempBasalsCalculation
        return lastTempBasalsCalculation;
    }

    @Override
    public IobTotal getCalculationToTimeTempBasals(long time) {
        // Return the calculation to time temp basals
        return getCalculationToTimeTempBasals(time, false, 0);
    }

    public IobTotal getCalculationToTimeTempBasals(long time, boolean truncate, long truncateTime) {
        // Create a new IobTotal
        IobTotal total = new IobTotal(time);

        // Get the active pump
        PumpInterface pumpInterface = activePlugin.getActivePump();

        // Acquire the lock to the tempBasals
        synchronized (tempBasals) {
            // Iterate over all tempBasals
            for (Integer pos = 0; pos < tempBasals.size(); pos++) {
                // Get the current tempBasal
                TemporaryBasal t = tempBasals.get(pos);
                if (t.date > time) continue;
                IobTotal calc;
                // Get the profile at the tempBasal date
                Profile profile = profileFunction.getProfile(t.date);
                if (profile == null) continue;
                if (truncate && t.end() > truncateTime) {
                    // If it is, create a new tempBasal
                    TemporaryBasal dummyTemp = new TemporaryBasal(getInjector());
                    // Copy the tempBasal
                    dummyTemp.copyFrom(t);
                    // Truncate the tempBasal to the truncateTime
                    dummyTemp.cutEndTo(truncateTime);
                    // Calculate the IOB
                    calc = dummyTemp.iobCalc(time, profile);
                } else {
                    // If not, calculate the IOB
                    calc = t.iobCalc(time, profile);
                }
                //log.debug("BasalIOB " + new Date(time) + " >>> " + calc.basaliob);
                total.plus(calc);
            }
        }
        // Check if the pump is faking temp basals by extended boluses
        if (pumpInterface.isFakingTempsByExtendedBoluses()) {
            // Create a new IobTotal
            IobTotal totalExt = new IobTotal(time);
            // Acquire the lock to the extendedBoluses
            synchronized (extendedBoluses) {
                // Iterate over all extendedBoluses
                for (int pos = 0; pos < extendedBoluses.size(); pos++) {
                    // Get the current extendedBolus
                    ExtendedBolus e = extendedBoluses.get(pos);
                    if (e.date > time) continue;
                    IobTotal calc;
                    // Get the profile at the extendedBolus date
                    Profile profile = profileFunction.getProfile(e.date);
                    if (profile == null) continue;
                    if (truncate && e.end() > truncateTime) {
                        // If it is, create a new extendedBolus
                        ExtendedBolus dummyExt = new ExtendedBolus(getInjector());
                        // Copy the extendedBolus
                        dummyExt.copyFrom(e);
                        // Truncate the extendedBolus to the truncateTime
                        dummyExt.cutEndTo(truncateTime);
                        // Calculate the IOB
                        calc = dummyExt.iobCalc(time, profile);
                    } else {
                        // If not, calculate the IOB
                        calc = e.iobCalc(time, profile);
                    }
                    // Add the IOB to the total
                    totalExt.plus(calc);
                }
            }
            // Convert to basal iob
            totalExt.basaliob = totalExt.iob;
            totalExt.iob = 0d;
            totalExt.netbasalinsulin = totalExt.extendedBolusInsulin;
            totalExt.hightempinsulin = totalExt.extendedBolusInsulin;
            total.plus(totalExt);
        }
        return total;
    }

    public IobTotal getAbsoluteIOBTempBasals(long time) {
        // Create a new IobTotal
        IobTotal total = new IobTotal(time);

        // Iterate over all 5 minute chunks from the start of the history to the current time
        for (long i = time - range(); i < time; i += T.mins(5).msecs()) {
            // Get the profile at the current time
            Profile profile = profileFunction.getProfile(i);
            // If the profile is null, continue to the next 5 minute chunk
            if (profile == null) continue;
            // Get the basal rate from the profile at the current time
            double basal = profile.getBasal(i);
            // Get the running temporary basal rate from the history
            TemporaryBasal runningTBR = getTempBasalFromHistory(i);
            // Calculate the running basal rate
            double running = basal;
            // If the running temporary basal rate is not null, calculate the running basal rate
            if (runningTBR != null) {
                running = runningTBR.tempBasalConvertedToAbsolute(i, profile);
            }
            // Create a new treatment
            Treatment treatment = new Treatment(getInjector());
            // Set the date of the treatment to the current time
            treatment.date = i;
            // Set the insulin of the treatment to the running basal rate multiplied by 5 minutes
            treatment.insulin = running * 5.0 / 60.0; // 5 min chunk
            // Calculate the IOB of the treatment
            Iob iob = treatment.iobCalc(time, profile.getDia());
            // Add the IOB to the total basal IOB
            total.basaliob += iob.iobContrib;
            // Add the IOB to the total activity IOB
            total.activity += iob.activityContrib;
        }
        // Return the total IOB
        return total;
    }

    public IobTotal getCalculationToTimeTempBasals(long time, long truncateTime, AutosensResult lastAutosensResult, boolean exercise_mode, int half_basal_exercise_target, boolean isTempTarget) {
        IobTotal total = new IobTotal(time);

        PumpInterface pumpInterface = activePlugin.getActivePump();

        synchronized (tempBasals) {
            for (int pos = 0; pos < tempBasals.size(); pos++) {
                TemporaryBasal t = tempBasals.get(pos);
                if (t.date > time) continue;
                IobTotal calc;
                Profile profile = profileFunction.getProfile(t.date);
                if (profile == null) continue;
                if (t.end() > truncateTime) {
                    TemporaryBasal dummyTemp = new TemporaryBasal(getInjector());
                    dummyTemp.copyFrom(t);
                    dummyTemp.cutEndTo(truncateTime);
                    calc = dummyTemp.iobCalc(time, profile, lastAutosensResult, exercise_mode, half_basal_exercise_target, isTempTarget);
                } else {
                    calc = t.iobCalc(time, profile, lastAutosensResult, exercise_mode, half_basal_exercise_target, isTempTarget);
                }
                //log.debug("BasalIOB " + new Date(time) + " >>> " + calc.basaliob);
                total.plus(calc);
            }
        }
        if (pumpInterface.isFakingTempsByExtendedBoluses()) {
            IobTotal totalExt = new IobTotal(time);
            synchronized (extendedBoluses) {
                for (int pos = 0; pos < extendedBoluses.size(); pos++) {
                    ExtendedBolus e = extendedBoluses.get(pos);
                    if (e.date > time) continue;
                    IobTotal calc;
                    Profile profile = profileFunction.getProfile(e.date);
                    if (profile == null) continue;
                    if (e.end() > truncateTime) {
                        ExtendedBolus dummyExt = new ExtendedBolus(getInjector());
                        dummyExt.copyFrom(e);
                        dummyExt.cutEndTo(truncateTime);
                        calc = dummyExt.iobCalc(time, profile, lastAutosensResult, exercise_mode, half_basal_exercise_target, isTempTarget);
                    } else {
                        calc = e.iobCalc(time, profile, lastAutosensResult, exercise_mode, half_basal_exercise_target, isTempTarget);
                    }
                    totalExt.plus(calc);
                }
            }
            // Convert to basal iob
            totalExt.basaliob = totalExt.iob;
            totalExt.iob = 0d;
            totalExt.netbasalinsulin = totalExt.extendedBolusInsulin;
            totalExt.hightempinsulin = totalExt.extendedBolusInsulin;
            total.plus(totalExt);
        }
        return total;
    }

    @Override
    public void updateTotalIOBTempBasals() {
        // Get the calculation to time temp basals
        lastTempBasalsCalculation = getCalculationToTimeTempBasals(DateUtil.now());
    }

    @Nullable
    @Override
    public TemporaryBasal getTempBasalFromHistory(long time) {
        // Get the real temporary basal from history
        TemporaryBasal tb = getRealTempBasalFromHistory(time);
        // If the temporary basal is not null, return it
        if (tb != null)
            return tb;
        // Get the extended bolus from history
        ExtendedBolus eb = getExtendedBolusFromHistory(time);
        // If the extended bolus is not null and the active pump is faking temps by extended boluses
        if (eb != null && activePlugin.getActivePump().isFakingTempsByExtendedBoluses())
            // Return a temporary basal with the extended bolus
            return new TemporaryBasal(eb);
        // Return null
        return null;
    }

    @Override
    public ExtendedBolus getExtendedBolusFromHistory(long time) {
        // Acquire the lock to the extended boluses
        synchronized (extendedBoluses) {
            // Return the extended bolus by interval
            return extendedBoluses.getValueByInterval(time);
        }
    }

    @Override
    public boolean addToHistoryExtendedBolus(ExtendedBolus extendedBolus) {
        //log.debug("Adding new ExtentedBolus record" + extendedBolus.log());
        boolean newRecordCreated = MainApp.getDbHelper().createOrUpdate(extendedBolus);
        if (newRecordCreated) {
            // If the duration is 0
            if (extendedBolus.durationInMinutes == 0) {
                // If the pump is faking temps by extended boluses
                if (activePlugin.getActivePump().isFakingTempsByExtendedBoluses())
                    // Upload a temp basal end
                    nsUpload.uploadTempBasalEnd(extendedBolus.date, true, extendedBolus.pumpId);
                else
                    // Upload an extended bolus end
                    nsUpload.uploadExtendedBolusEnd(extendedBolus.date, extendedBolus.pumpId);
            } else if (activePlugin.getActivePump().isFakingTempsByExtendedBoluses())
                // Upload a temp basal start
                nsUpload.uploadTempBasalStartAbsolute(new TemporaryBasal(extendedBolus), extendedBolus.insulin);
            else
                // Upload an extended bolus
                nsUpload.uploadExtendedBolus(extendedBolus);
        }
        return newRecordCreated;
    }

    @Override
    @NonNull
    public Intervals<ExtendedBolus> getExtendedBolusesFromHistory() {
        // Acquire the lock to the extended boluses
        synchronized (extendedBoluses) {
            // Return the non overlapping intervals of the extended boluses
            return new NonOverlappingIntervals<>(extendedBoluses);
        }
    }

    @Override
    @NonNull
    public NonOverlappingIntervals<TemporaryBasal> getTemporaryBasalsFromHistory() {
        // Acquire the lock to the temp basals
        synchronized (tempBasals) {
            // Return the non overlapping intervals of the temp basals
            return new NonOverlappingIntervals<>(tempBasals);
        }
    }

    @Override
    public boolean addToHistoryTempBasal(TemporaryBasal tempBasal) {
        //log.debug("Adding new TemporaryBasal record" + tempBasal.toString());
        boolean newRecordCreated = MainApp.getDbHelper().createOrUpdate(tempBasal);
        if (newRecordCreated) {
            if (tempBasal.durationInMinutes == 0)
                // If it is, upload the temp basal end
                nsUpload.uploadTempBasalEnd(tempBasal.date, false, tempBasal.pumpId);
            else if (tempBasal.isAbsolute)
                // If it is not, check if the temp basal is absolute
                nsUpload.uploadTempBasalStartAbsolute(tempBasal, null);
            else
                // If it is not, check if the temp basal is percent
                nsUpload.uploadTempBasalStartPercent(tempBasal, profileFunction.getProfile(tempBasal.date));
        }
        return newRecordCreated;
    }

    public TreatmentUpdateReturn createOrUpdateMedtronic(Treatment treatment, boolean fromNightScout) {
        // Get the service
        TreatmentService.UpdateReturn resultRecord = getService().createOrUpdateMedtronic(treatment, fromNightScout);

        // Return the treatment update return
        return new TreatmentUpdateReturn(resultRecord.success, resultRecord.newRecord);
    }

    // return true if new record is created
    @Override
    public boolean addToHistoryTreatment(DetailedBolusInfo detailedBolusInfo, boolean allowUpdate) {
        boolean medtronicPump = activePlugin.getActivePump() instanceof MedtronicPumpPlugin;

        getAapsLogger().debug(MedtronicHistoryData.doubleBolusDebug, LTag.DATATREATMENTS, "DoubleBolusDebug: addToHistoryTreatment::isMedtronicPump={} " + medtronicPump);

        Treatment treatment = new Treatment();
        treatment.date = detailedBolusInfo.date;
        treatment.source = detailedBolusInfo.source;
        treatment.pumpId = detailedBolusInfo.pumpId;
        treatment.insulin = detailedBolusInfo.insulin;
        treatment.isValid = detailedBolusInfo.isValid;
        treatment.isSMB = detailedBolusInfo.isSMB;
        if (detailedBolusInfo.carbTime == 0)
            treatment.carbs = detailedBolusInfo.carbs;
        treatment.mealBolus = treatment.carbs > 0;
        treatment.boluscalc = detailedBolusInfo.boluscalc != null ? detailedBolusInfo.boluscalc.toString() : null;
        TreatmentService.UpdateReturn creatOrUpdateResult;

        getAapsLogger().debug(medtronicPump && MedtronicHistoryData.doubleBolusDebug, LTag.DATATREATMENTS, "DoubleBolusDebug: addToHistoryTreatment::treatment={} " + treatment);

        if (!medtronicPump)
            creatOrUpdateResult = getService().createOrUpdate(treatment);
        else
            creatOrUpdateResult = getService().createOrUpdateMedtronic(treatment, false);

        boolean newRecordCreated = creatOrUpdateResult.newRecord;
        //log.debug("Adding new Treatment record" + treatment.toString());
        if (detailedBolusInfo.carbTime != 0) {

            Treatment carbsTreatment = new Treatment();
            carbsTreatment.source = detailedBolusInfo.source;
            carbsTreatment.pumpId = detailedBolusInfo.pumpId; // but this should never happen
            carbsTreatment.date = detailedBolusInfo.date + detailedBolusInfo.carbTime * 60 * 1000L + 1000L; // add 1 sec to make them different records
            carbsTreatment.carbs = detailedBolusInfo.carbs;

            getAapsLogger().debug(medtronicPump && MedtronicHistoryData.doubleBolusDebug, LTag.DATATREATMENTS, "DoubleBolusDebug: carbTime!=0, creating second treatment. CarbsTreatment={}" + carbsTreatment);

            if (!medtronicPump)
                getService().createOrUpdate(carbsTreatment);
            else
                getService().createOrUpdateMedtronic(carbsTreatment, false);
            //log.debug("Adding new Treatment record" + carbsTreatment);
        }
        if (newRecordCreated && detailedBolusInfo.isValid)
            nsUpload.uploadTreatmentRecord(detailedBolusInfo);

        if (!allowUpdate && !creatOrUpdateResult.success) {
            getAapsLogger().error("Treatment could not be added to DB", new Exception());

            String status = String.format(resourceHelper.gs(R.string.error_adding_treatment_message), treatment.insulin, (int) treatment.carbs, dateUtil.dateAndTimeString(treatment.date));

            Intent i = new Intent(context, ErrorHelperActivity.class);
            i.putExtra("soundid", R.raw.error);
            i.putExtra("title", resourceHelper.gs(R.string.error_adding_treatment_title));
            i.putExtra("status", status);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);

            Bundle bundle = new Bundle();
            bundle.putString(FirebaseAnalytics.Param.ITEM_LIST_ID, "TreatmentClash");
            bundle.putString(FirebaseAnalytics.Param.ITEM_LIST_NAME, status);
            fabricPrivacy.logCustom(bundle);
        }

        return newRecordCreated;
    }

    @Override
    public long oldestDataAvailable() {
        // Create a variable to store the oldest time available
        long oldestTime = System.currentTimeMillis();
        synchronized (tempBasals) {
            // If the tempBasals list is not empty
            if (tempBasals.size() > 0)
                // Set the oldest time to the minimum of the oldest time available and the date of the first temp basal
                oldestTime = Math.min(oldestTime, tempBasals.get(0).date);
        }
        synchronized (extendedBoluses) {
            // If the extendedBoluses list is not empty
            if (extendedBoluses.size() > 0)
                // Set the oldest time to the minimum of the oldest time available and the date of the first extended bolus
                oldestTime = Math.min(oldestTime, extendedBoluses.get(0).date);
        }
        synchronized (treatments) {
            // If the treatments list is not empty
            if (treatments.size() > 0)
                // Set the oldest time to the minimum of the oldest time available and the date of the last treatment
                oldestTime = Math.min(oldestTime, treatments.get(treatments.size() - 1).date);
        }
        oldestTime -= 15 * 60 * 1000L; // allow 15 min before
        return oldestTime;
    }

    @Nullable
    @Override
    public TempTarget getTempTargetFromHistory() {
        // Acquire the lock to the tempTargets
        synchronized (tempTargets) {
            // Return the tempTarget by interval
            return tempTargets.getValueByInterval(System.currentTimeMillis());
        }
    }

    @Nullable
    @Override
    public TempTarget getTempTargetFromHistory(long time) {
        // Acquire the lock to the tempTargets
        synchronized (tempTargets) {
            // Return the tempTarget by interval
            return tempTargets.getValueByInterval(time);
        }
    }

    @Override
    public Intervals<TempTarget> getTempTargetsFromHistory() {
        // Acquire the lock to the tempTargets
        synchronized (tempTargets) {
            // Return the tempTargets as an Intervals
            return new OverlappingIntervals<>(tempTargets);
        }
    }

    @Override
    public void addToHistoryTempTarget(TempTarget tempTarget) {
        //log.debug("Adding new TemporaryBasal record" + profileSwitch.log());
        MainApp.getDbHelper().createOrUpdate(tempTarget);
        // Upload the TempTarget to Nightscout
        nsUpload.uploadTempTarget(tempTarget, profileFunction);
    }

    @Override
    @Nullable
    public ProfileSwitch getProfileSwitchFromHistory(long time) {
        // Acquire the lock to the profiles
        synchronized (profiles) {
            // Return the profile switch from history
            return (ProfileSwitch) profiles.getValueToTime(time);
        }
    }

    @Override
    public ProfileIntervals<ProfileSwitch> getProfileSwitchesFromHistory() {
        // Acquire the lock to the profiles
        synchronized (profiles) {
            // Return a new ProfileIntervals object
            return new ProfileIntervals<>(profiles);
        }
    }

    @Override
    public void addToHistoryProfileSwitch(ProfileSwitch profileSwitch) {
        //log.debug("Adding new TemporaryBasal record" + profileSwitch.log());
        rxBus.send(new EventDismissNotification(Notification.PROFILE_SWITCH_MISSING));
        // Create or update the profile switch in the database
        MainApp.getDbHelper().createOrUpdate(profileSwitch);
        // Upload the profile switch to Nightscout
        nsUpload.uploadProfileSwitch(profileSwitch);
    }

    @Override
    public void doProfileSwitch(@NotNull final ProfileStore profileStore, @NotNull final String profileName, final int duration, final int percentage, final int timeShift, final long date) {
        // Create a new profile switch
        ProfileSwitch profileSwitch = profileFunction.prepareProfileSwitch(profileStore, profileName, duration, percentage, timeShift, date);
        // Add it to the history
        addToHistoryProfileSwitch(profileSwitch);
        // If the percentage is 90 and the duration is 10
        if (percentage == 90 && duration == 10)
            // Set the objective use profile switch to true
            sp.putBoolean(R.string.key_objectiveuseprofileswitch, true);
    }

    @Override
    public void doProfileSwitch(final int duration, final int percentage, final int timeShift) {
        // Get the profile switch from history
        ProfileSwitch profileSwitch = getProfileSwitchFromHistory(System.currentTimeMillis());
        // If profile switch exists
        if (profileSwitch != null) {
            // Create a new profile switch
            profileSwitch = new ProfileSwitch(getInjector());
            // Set the date
            profileSwitch.date = System.currentTimeMillis();
            // Set the source
            profileSwitch.source = Source.USER;
            // Set the profile name
            profileSwitch.profileName = profileFunction.getProfileName(System.currentTimeMillis(), false, false);
            // Set the profile json
            profileSwitch.profileJson = profileFunction.getProfile().getData().toString();
            // Set the profile plugin
            profileSwitch.profilePlugin = activePlugin.getActiveProfileInterface().getClass().getName();
            // Set the duration in minutes
            profileSwitch.durationInMinutes = duration;
            // Set the is CPP
            profileSwitch.isCPP = percentage != 100 || timeShift != 0;
            // Set the timeshift
            profileSwitch.timeshift = timeShift;
            // Set the percentage
            profileSwitch.percentage = percentage;
            // Add the profile switch to history
            addToHistoryProfileSwitch(profileSwitch);
        } else {
            // If no profile switch exists
            getAapsLogger().error(LTag.PROFILE, "No profile switch exists");
        }
    }

}
