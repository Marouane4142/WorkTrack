package fr.worktrack.domain

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Serializable
enum class ActivityType(val label: String) {
    CUSTOMER_INTERVENTION("Intervention client"), OFFICE_WORK("Travail au bureau"),
    HOME_TO_CUSTOMER("Maison → Site client"), CUSTOMER_TO_HOME("Site client → Maison"),
    HOME_TO_OFFICE("Maison → Bureau"), OFFICE_TO_HOME("Bureau → Maison"),
    OFFICE_TO_CUSTOMER("Bureau → Site client"), CUSTOMER_TO_OFFICE("Site client → Bureau"),
    CUSTOMER_TO_CUSTOMER("Site client → Site client"), LUNCH_BREAK("Pause déjeuner"),
    MORNING_BREAK("Pause matin"), AFTERNOON_BREAK("Pause après-midi"),
    OTHER_EFFECTIVE("Autre activité professionnelle"), OTHER_UNCOUNTED("Autre activité non comptabilisée"),
    TRAVEL("Trajet");
    val isBreak get() = this in listOf(LUNCH_BREAK, MORNING_BREAK, AFTERNOON_BREAK)
    val isTravel get() = this == TRAVEL || ordinal in 2..8
    val displayLabel get() = if (isTravel) "Trajet" else if (this in listOf(MORNING_BREAK, AFTERNOON_BREAK)) "Pause" else label
    companion object {
        val selectable = listOf(CUSTOMER_INTERVENTION, OFFICE_WORK, TRAVEL, LUNCH_BREAK, OTHER_EFFECTIVE, OTHER_UNCOUNTED)
    }
}

@Serializable
enum class PlaceType(val label: String) { HOME("Domicile"), OFFICE("Bureau"), CUSTOMER_SITE("Site client") }

data class KnownPlace(val type: PlaceType, val name: String) {
    val label get() = when (type) { PlaceType.HOME -> "Domicile"; PlaceType.OFFICE -> "Bureau"; else -> name.ifBlank { "Site client" } }
}

@Serializable
enum class TimeClassification { EFFECTIVE, NON_EFFECTIVE, UNCOUNTED }

@Serializable
data class WorkRules(
    val returnHomeDeductionMinutes: Int = 45,
    val mealThresholdMinutes: Int = 240,
    val maxAutomaticMealsPerDay: Int = 1,
    val roundDownStepMinutes: Int = 0,
    val nightStartMinute: Int = 1320,
    val nightEndMinute: Int = 360,
    val otherProfessionalClassification: TimeClassification = TimeClassification.EFFECTIVE
) {
    fun validate() {
        require(returnHomeDeductionMinutes in 0..1440) { "La franchise doit être comprise entre 0 et 1 440 minutes." }
        require(mealThresholdMinutes in 1..1440) { "Le seuil repas doit être compris entre 1 et 1 440 minutes." }
        require(maxAutomaticMealsPerDay in 0..10) { "Le maximum de repas doit être compris entre 0 et 10." }
        require(roundDownStepMinutes in listOf(0, 5, 15, 30)) { "Arrondi invalide." }
        require(nightStartMinute in 0..1439 && nightEndMinute in 0..1439 && nightStartMinute != nightEndMinute) { "Les horaires de nuit doivent être distincts." }
    }
}

@Serializable
data class WorkEntry(
    val id: String = UUID.randomUUID().toString(),
    val start: String,
    val end: String,
    val type: ActivityType = ActivityType.CUSTOMER_INTERVENTION,
    val origin: String = "", val destination: String = "", val caseName: String = "",
    val caseNumber: String = "", val client: String = "", val location: String = "", val notes: String = "",
    val effectiveMinutes: Int = 0, val nonEffectiveMinutes: Int = 0,
    val deductedMinutes: Int = 0, val uncountedMinutes: Int = 0, val roundingMinutes: Int = 0,
    val appliedRules: WorkRules = WorkRules(), val ruleVersion: Int = 2,
    val classificationOverride: TimeClassification? = null, val overrideReason: String = "",
    val createdAt: String = java.time.Instant.now().toString(),
    val updatedAt: String = java.time.Instant.now().toString(),
    val originType: PlaceType? = null, val destinationType: PlaceType? = null
) {
    val startTime get() = LocalDateTime.parse(start)
    val endTime get() = LocalDateTime.parse(end)
    val rawMinutes get() = Duration.between(startTime, endTime).toMinutes().toInt()
    val countedMinutes get() = effectiveMinutes + nonEffectiveMinutes
    val departure: KnownPlace? get() {
        val kind = originType ?: when (type) {
            ActivityType.HOME_TO_CUSTOMER, ActivityType.HOME_TO_OFFICE -> PlaceType.HOME
            ActivityType.OFFICE_TO_HOME, ActivityType.OFFICE_TO_CUSTOMER -> PlaceType.OFFICE
            ActivityType.CUSTOMER_TO_HOME, ActivityType.CUSTOMER_TO_OFFICE, ActivityType.CUSTOMER_TO_CUSTOMER -> PlaceType.CUSTOMER_SITE
            else -> null
        }
        return kind?.let { KnownPlace(it, origin.ifBlank { location.substringBefore('→').trim() }) }
    }
    val arrival: KnownPlace? get() = when {
        type == ActivityType.OFFICE_WORK -> KnownPlace(PlaceType.OFFICE, "Bureau")
        type == ActivityType.CUSTOMER_INTERVENTION -> location.takeIf { it.isNotBlank() }?.let { KnownPlace(PlaceType.CUSTOMER_SITE, it) }
        type.isTravel -> (destinationType ?: when (type) {
            ActivityType.HOME_TO_CUSTOMER, ActivityType.OFFICE_TO_CUSTOMER, ActivityType.CUSTOMER_TO_CUSTOMER -> PlaceType.CUSTOMER_SITE
            ActivityType.CUSTOMER_TO_HOME, ActivityType.OFFICE_TO_HOME -> PlaceType.HOME
            ActivityType.HOME_TO_OFFICE, ActivityType.CUSTOMER_TO_OFFICE -> PlaceType.OFFICE
            else -> null
        })?.let { KnownPlace(it, destination.ifBlank { location.substringAfter('→', location).trim() }) }
        else -> null
    }
    val title get() = when {
        type.isTravel && departure != null && arrival != null -> "${departure!!.label} → ${arrival!!.label}"
        type == ActivityType.OFFICE_WORK -> "Bureau"
        location.isNotBlank() -> location
        caseName.isNotBlank() -> caseName
        client.isNotBlank() -> client
        else -> type.displayLabel
    }
}

data class DaySummary(
    val date: LocalDate, val effectiveMinutes: Int = 0, val nonEffectiveMinutes: Int = 0,
    val breakMinutes: Int = 0, val uncountedMinutes: Int = 0, val deductedMinutes: Int = 0,
    val roundingMinutes: Int = 0, val nightEffectiveMinutes: Int = 0, val nightNonEffectiveMinutes: Int = 0,
    val mealCount: Int = 0, val entryCount: Int = 0
) { val countedMinutes get() = effectiveMinutes + nonEffectiveMinutes }

@Serializable enum class Appearance { SYSTEM, LIGHT, DARK }
@Serializable enum class DurationFormat { HOURS_MINUTES, DECIMAL }
@Serializable
data class Preferences(val appearance: Appearance = Appearance.SYSTEM,
    val durationFormat: DurationFormat = DurationFormat.HOURS_MINUTES, val weekStartsOnMonday: Boolean = true)

@Serializable
data class Profile(val firstName: String = "", val lastName: String = "", val company: String = "",
    val companyLogo: String = "", val signature: String = "") {
    val fullName get() = "$firstName $lastName".trim()
    val home get() = "Domicile"
    val office get() = "Bureau"
}

@Serializable
data class BusinessCase(val number: String, val name: String = "", val client: String = "", val location: String = "")

@Serializable
data class AuditEvent(val id: String = UUID.randomUUID().toString(), val at: String = java.time.Instant.now().toString(),
    val action: String, val description: String, val before: WorkEntry? = null, val after: WorkEntry? = null)

@Serializable
data class Backup(val format: String = "WorkTrack", val version: Int = 2,
    val createdAt: String = java.time.Instant.now().toString(), val entries: List<WorkEntry>,
    val rules: WorkRules, val preferences: Preferences, val profile: Profile,
    val cases: List<BusinessCase> = emptyList(), val audit: List<AuditEvent> = emptyList())
