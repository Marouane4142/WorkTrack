package fr.worktrack.data

import fr.worktrack.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

object EntryCodec {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(entry: WorkEntry) = json.encodeToString(entry)

    /** C# payloads are read without recalculating the original saved totals. */
    fun decode(payload: String): WorkEntry {
        val obj = json.parseToJsonElement(payload).jsonObject
        if ("Start" !in obj) return json.decodeFromString(payload)
        fun text(key: String) = obj[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun number(key: String, default: Int = 0) = obj[key]?.jsonPrimitive?.intOrNull ?: default
        val rule = WorkRules(returnHomeDeductionMinutes = number("AppliedReturnDeductionMinutes", 45),
            mealThresholdMinutes = number("AppliedMealThresholdMinutes", 240).takeIf { it > 0 } ?: 240,
            maxAutomaticMealsPerDay = if (number("AppliedMealThresholdMinutes") > 0) number("AppliedMaxAutomaticMealsPerDay", 1) else 1)
        return WorkEntry(id = text("Id"), start = text("Start"), end = text("End"),
            type = ActivityType.entries.getOrNull(number("Type")) ?: error("Type d'activité C# inconnu."),
            origin = text("Origin"), destination = text("Destination"), caseName = text("CaseName"),
            caseNumber = text("CaseNumber"), client = text("Client"), location = text("Location"), notes = text("Notes"),
            effectiveMinutes = number("EffectiveMinutes"), nonEffectiveMinutes = number("NonEffectiveMinutes"),
            deductedMinutes = number("DeductedMinutes"), uncountedMinutes = number("UncountedMinutes"),
            appliedRules = rule, ruleVersion = number("RuleVersion", 1))
    }

    fun decodeRules(payload: String): WorkRules {
        val obj = json.parseToJsonElement(payload).jsonObject
        if ("ReturnHomeDeductionMinutes" !in obj) return json.decodeFromString(payload)
        return WorkRules(obj["ReturnHomeDeductionMinutes"]?.jsonPrimitive?.int ?: 45,
            obj["MealThresholdMinutes"]?.jsonPrimitive?.int ?: 240,
            obj["MaxAutomaticMealsPerDay"]?.jsonPrimitive?.int ?: 1)
    }
}
