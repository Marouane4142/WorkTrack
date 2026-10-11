package fr.worktrack.export

import fr.worktrack.domain.*
import java.time.LocalDate

object ReportNames {
    fun base(profile: Profile, date: LocalDate, monday: Boolean = true): String {
        val name = "${profile.lastName} ${profile.firstName}".trim().uppercase(French)
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").replace(Regex("\\s+"), " ").ifBlank { "UTILISATEUR" }
        return "Feuilles_heures_hebdomadaire_${name}_${weekStart(date, monday)}"
    }
}
