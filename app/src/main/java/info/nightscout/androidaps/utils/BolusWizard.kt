package info.nightscout.androidaps.utils

import android.content.Context
import android.content.Intent
import android.support.v7.app.AlertDialog
import android.text.Html
import info.nightscout.androidaps.MainApp
import info.nightscout.androidaps.R
import info.nightscout.androidaps.data.DetailedBolusInfo
import info.nightscout.androidaps.data.Profile
import info.nightscout.androidaps.db.CareportalEvent
import info.nightscout.androidaps.db.Source
import info.nightscout.androidaps.db.TempTarget
import info.nightscout.androidaps.events.EventRefreshOverview
import info.nightscout.androidaps.interfaces.Constraint
import info.nightscout.androidaps.interfaces.PluginType
import info.nightscout.androidaps.interfaces.PumpDescription
import info.nightscout.androidaps.interfaces.PumpInterface
import info.nightscout.androidaps.logging.L
import info.nightscout.androidaps.plugins.aps.loop.LoopPlugin
import info.nightscout.androidaps.plugins.configBuilder.ConfigBuilderPlugin
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions
import info.nightscout.androidaps.plugins.general.overview.dialogs.ErrorHelperActivity
import info.nightscout.androidaps.plugins.hm.mealadvisor.MealAdvisorPlugin
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatus
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin
import info.nightscout.androidaps.queue.Callback
import org.json.JSONException
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.util.*

class BolusWizard @JvmOverloads constructor(val profile: Profile,
                                            val profileName: String,
                                            val tempTarget: TempTarget?,
                                            val carbs: Int,
                                            val cob: Double,
                                            val bg: Double,
                                            val correction: Double,
                                            private val percentageCorrection: Double = 100.0,
                                            private val useBg: Boolean,
                                            private val useCob: Boolean,
                                            private val includeBolusIOB: Boolean,
                                            private val includeBasalIOB: Boolean,
                                            private val useSuperBolus: Boolean,
                                            private val useTT: Boolean,
                                            private val useTrend: Boolean,
                                            val notes: String = "",
                                            private val carbNow: Boolean = false
) {

    private val log = LoggerFactory.getLogger(L.CORE)

    // Intermediate
    var sens = 0.0
        private set

    var ic = 0.0
        private set

    var glucoseStatus: GlucoseStatus? = null
        private set

    var targetBGLow = 0.0
        private set

    var targetBGHigh = 0.0
        private set

    var bgDiff = 0.0
        private set

    var insulinFromBG = 0.0
        private set

    var insulinFromCarbs = 0.0
        private set

    var insulinFromBolusIOB = 0.0
        private set

    var insulinFromBasalsIOB = 0.0
        private set

    var insulinFromCorrection = 0.0
        private set

    var insulinFromSuperBolus = 0.0
        private set

    var insulinFromCOB = 0.0
        private set

    var insulinFromTrend = 0.0
        private set

    var trend = 0.0
        private set

    var accepted = false
        private set

    // Result
    var calculatedTotalInsulin: Double = 0.0
        private set

    var totalBeforePercentageAdjustment: Double = 0.0
        private set

    var carbsEquivalent: Double = 0.0
        private set

    var insulinAfterConstraints: Double = 0.0
        private set

    init {
        doCalc()
    }

    private fun doCalc() {

        // Insulin from BG
        sens = profile.isf
        targetBGLow = profile.targetLow
        targetBGHigh = profile.targetHigh
        if (useTT && tempTarget != null) {
            targetBGLow = Profile.fromMgdlToUnits(tempTarget.low, profile.units)
            targetBGHigh = Profile.fromMgdlToUnits(tempTarget.high, profile.units)
        }
        if (useBg && bg > 0) {
            if (bg >= targetBGLow && bg <= targetBGHigh) {
                bgDiff = 0.0
            } else if (bg <= targetBGLow) {
                bgDiff = bg - targetBGLow
            } else {
                bgDiff = bg - targetBGHigh
            }
            insulinFromBG = bgDiff / sens
        }

        // Insulin from 15 min trend
        glucoseStatus = GlucoseStatus.getGlucoseStatusData()
        glucoseStatus?.let {
            if (useTrend) {
                trend = it.short_avgdelta
                insulinFromTrend = Profile.fromMgdlToUnits(trend, profile.units) * 3 / sens
            }
        }


        // Insuling from carbs
        ic = profile.ic
        insulinFromCarbs = carbs / ic
        insulinFromCOB = if (useCob) (cob / ic) else 0.0

        // Insulin from IOB
        // IOB calculation
        val treatments = TreatmentsPlugin.getPlugin()
        treatments.updateTotalIOBTreatments()
        val bolusIob = treatments.lastCalculationTreatments.round()
        treatments.updateTotalIOBTempBasals()
        val basalIob = treatments.lastCalculationTempBasals.round()

        insulinFromBolusIOB = if (includeBolusIOB) -bolusIob.iob else 0.0
        insulinFromBasalsIOB = if (includeBasalIOB) -basalIob.basaliob else 0.0

        // Insulin from correction
        insulinFromCorrection = correction

        // Insulin from superbolus for 2h. Get basal rate now and after 1h
        if (useSuperBolus) {
            insulinFromSuperBolus = profile.basal
            var timeAfter1h = System.currentTimeMillis()
            timeAfter1h += T.hours(1).msecs()
            insulinFromSuperBolus += profile.getBasal(timeAfter1h)
        }

        // Total
        calculatedTotalInsulin = insulinFromBG + insulinFromTrend + insulinFromCarbs + insulinFromBolusIOB + insulinFromBasalsIOB + insulinFromCorrection + insulinFromSuperBolus + insulinFromCOB

        // Percentage adjustment
        totalBeforePercentageAdjustment = calculatedTotalInsulin
        if (calculatedTotalInsulin > 0) {
            calculatedTotalInsulin = calculatedTotalInsulin * percentageCorrection / 100.0
        }

        if (calculatedTotalInsulin < 0) {
            carbsEquivalent = (-calculatedTotalInsulin) * ic
            calculatedTotalInsulin = 0.0
        }

        val bolusStep = ConfigBuilderPlugin.getPlugin().activePump.pumpDescription.bolusStep
        calculatedTotalInsulin = Round.roundTo(calculatedTotalInsulin, bolusStep)

        insulinAfterConstraints = MainApp.getConstraintChecker().applyBolusConstraints(Constraint(calculatedTotalInsulin)).value()

        log.debug(this.toString())
    }

    fun nsJSON(): JSONObject {
        val boluscalcJSON = JSONObject()
        try {
            boluscalcJSON.put("profile", profileName)
            boluscalcJSON.put("notes", notes)
            boluscalcJSON.put("eventTime", DateUtil.toISOString(Date()))
            boluscalcJSON.put("targetBGLow", targetBGLow)
            boluscalcJSON.put("targetBGHigh", targetBGHigh)
            boluscalcJSON.put("isf", sens)
            boluscalcJSON.put("ic", ic)
            boluscalcJSON.put("iob", -(insulinFromBolusIOB + insulinFromBasalsIOB))
            boluscalcJSON.put("bolusiob", insulinFromBolusIOB)
            boluscalcJSON.put("basaliob", insulinFromBasalsIOB)
            boluscalcJSON.put("bolusiobused", includeBolusIOB)
            boluscalcJSON.put("basaliobused", includeBasalIOB)
            boluscalcJSON.put("bg", bg)
            boluscalcJSON.put("insulinbg", insulinFromBG)
            boluscalcJSON.put("insulinbgused", useBg)
            boluscalcJSON.put("bgdiff", bgDiff)
            boluscalcJSON.put("insulincarbs", insulinFromCarbs)
            boluscalcJSON.put("carbs", carbs)
            boluscalcJSON.put("cob", cob)
            boluscalcJSON.put("cobused", useCob)
            boluscalcJSON.put("insulincob", insulinFromCOB)
            boluscalcJSON.put("othercorrection", correction)
            boluscalcJSON.put("insulinsuperbolus", insulinFromSuperBolus)
            boluscalcJSON.put("insulintrend", insulinFromTrend)
            boluscalcJSON.put("insulin", calculatedTotalInsulin)
            boluscalcJSON.put("superbolusused", useSuperBolus)
            boluscalcJSON.put("insulinsuperbolus", insulinFromSuperBolus)
            boluscalcJSON.put("trendused", useTrend)
            boluscalcJSON.put("insulintrend", insulinFromTrend)
            boluscalcJSON.put("trend", trend)
            boluscalcJSON.put("ttused", useTT)
        } catch (e: JSONException) {
            log.error("Unhandled exception", e)
        }
        return boluscalcJSON
    }

    private fun confirmMessageAfterConstraints(pump: PumpInterface): String {

        var confirmMessage = MainApp.gs(R.string.entertreatmentquestion)
        if (insulinAfterConstraints > 0)
            confirmMessage += "<br/>" + MainApp.gs(R.string.bolus) + ": " + "<font color='" + MainApp.gc(R.color.bolus) + "'>" + DecimalFormatter.toPumpSupportedBolus(insulinAfterConstraints) + "U" + "</font>"
        if (carbs > 0)
            confirmMessage += "<br/>" + MainApp.gs(R.string.carbs) + ": " + "<font color='" + MainApp.gc(R.color.carbs) + "'>" + carbs + "g" + "</font>"

        if (Math.abs(insulinAfterConstraints - calculatedTotalInsulin) > pump.getPumpDescription().pumpType.determineCorrectBolusStepSize(insulinAfterConstraints)) {
            confirmMessage += "<br/><font color='" + MainApp.gc(R.color.warning) + "'>" + MainApp.gs(R.string.bolusconstraintapplied) + "</font>"
        }

        return confirmMessage
    }

    fun confirmAndExecute(context: Context) {
        if (MealAdvisorPlugin.getPlugin().isEnabled(PluginType.GENERAL)) {
            val mealCarbs = MealAdvisorPlugin.getPlugin().getScheduledCarbs();
            val mealNotes = MealAdvisorPlugin.getPlugin().getMealNotes();
            if (!carbNow && carbs > 0.0 && mealCarbs > 0) {
                // Already meal scheduled: overrule?
                val builder = AlertDialog.Builder(context)
                builder.setTitle("Inplannen maaltijd");
                builder.setMessage("Er is al een maaltijd\n'"+mealNotes+"' ingepland,\ndeze zal overschreven worden.\n\nDoorgaan?");
                builder.setPositiveButton("Ja") { dialog, id -> doConfirmAndExecute(context) }
                builder.setNegativeButton("Nee", null)
                builder.show()
                return
            }else
                doConfirmAndExecute(context);
        }else
            doConfirmAndExecute(context);
    }

    fun doConfirmAndExecute(context: Context) {
        val profile = ProfileFunctions.getInstance().profile
        val pump = ConfigBuilderPlugin.getPlugin().activePump

        if (pump != null && profile != null && (calculatedTotalInsulin > 0.0 || carbs > 0.0)) {
            val confirmMessage = confirmMessageAfterConstraints(pump)

            val builder = AlertDialog.Builder(context)
            builder.setTitle(MainApp.gs(R.string.confirmation))
            builder.setMessage(Html.fromHtml(confirmMessage))
            builder.setPositiveButton(MainApp.gs(R.string.ok)) { _, _ ->
                synchronized(builder) {
                    if (accepted) {
                        log.debug("guarding: already accepted")
                        return@setPositiveButton
                    }
                    accepted = true
                    if (insulinAfterConstraints > 0 || carbs > 0) {
                        if (useSuperBolus) {
                            val loopPlugin = LoopPlugin.getPlugin()
                            if (loopPlugin.isEnabled(PluginType.LOOP)) {
                                loopPlugin.superBolusTo(System.currentTimeMillis() + 2 * 60L * 60 * 1000)
                                MainApp.bus().post(EventRefreshOverview("WizardDialog"))
                            }

                            val pump1 = ConfigBuilderPlugin.getPlugin().activePump

                            if (pump1.pumpDescription.tempBasalStyle == PumpDescription.ABSOLUTE) {
                                ConfigBuilderPlugin.getPlugin().commandQueue.tempBasalAbsolute(0.0, 120, true, profile, object : Callback() {
                                    override fun run() {
                                        if (!result.success) {
                                            val i = Intent(MainApp.instance(), ErrorHelperActivity::class.java)
                                            i.putExtra("soundid", R.raw.boluserror)
                                            i.putExtra("status", result.comment)
                                            i.putExtra("title", MainApp.gs(R.string.tempbasaldeliveryerror))
                                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            MainApp.instance().startActivity(i)
                                        }
                                    }
                                })
                            } else {

                                ConfigBuilderPlugin.getPlugin().commandQueue.tempBasalPercent(0, 120, true, profile, object : Callback() {
                                    override fun run() {
                                        if (!result.success) {
                                            val i = Intent(MainApp.instance(), ErrorHelperActivity::class.java)
                                            i.putExtra("soundid", R.raw.boluserror)
                                            i.putExtra("status", result.comment)
                                            i.putExtra("title", MainApp.gs(R.string.tempbasaldeliveryerror))
                                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            MainApp.instance().startActivity(i)
                                        }
                                    }
                                })
                            }
                        }
                        val detailedBolusInfo = DetailedBolusInfo()
                        detailedBolusInfo.eventType = CareportalEvent.BOLUSWIZARD
                        detailedBolusInfo.insulin = insulinAfterConstraints
                        detailedBolusInfo.carbs = carbs.toDouble()
                        detailedBolusInfo.context = context
                        detailedBolusInfo.glucose = bg
                        detailedBolusInfo.glucoseType = "Manual"
                        detailedBolusInfo.carbTime = if(carbNow)0 else 5;
                        detailedBolusInfo.boluscalc = nsJSON()
                        detailedBolusInfo.source = Source.USER
                        detailedBolusInfo.notes = notes
                        if (detailedBolusInfo.insulin > 0 || ConfigBuilderPlugin.getPlugin().activePump.pumpDescription.storesCarbInfo) {
                            ConfigBuilderPlugin.getPlugin().commandQueue.bolus(detailedBolusInfo, object : Callback() {
                                override fun run() {
                                    if (!result.success) {
                                        val i = Intent(MainApp.instance(), ErrorHelperActivity::class.java)
                                        i.putExtra("soundid", R.raw.boluserror)
                                        i.putExtra("status", result.comment)
                                        i.putExtra("title", MainApp.gs(R.string.treatmentdeliveryerror))
                                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        MainApp.instance().startActivity(i)
                                    }
                                }
                            })
                        } else {
                            TreatmentsPlugin.getPlugin().addToHistoryTreatment(detailedBolusInfo, false)
                        }
                    }
                }
            }
            builder.setNegativeButton(MainApp.gs(R.string.cancel), null)
            builder.show()
        }
    }
}
