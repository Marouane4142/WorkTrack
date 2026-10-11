package fr.worktrack.domain

object BackupValidator {
    fun validate(backup: Backup) {
        require(backup.format == "WorkTrack" && backup.version == 2) { "Ce fichier n'est pas une sauvegarde WorkTrack compatible." }
        backup.rules.validate()
        listOf(backup.profile.companyLogo, backup.profile.signature).filter { it.isNotEmpty() }.forEach { image ->
            require(image.length <= 1_000_000 && image.startsWith("iVBORw0KGgo") && image.matches(Regex("[A-Za-z0-9+/]+={0,2}"))) {
                "Une image du profil est invalide ou trop volumineuse."
            }
        }
        require(backup.entries.map { it.id }.distinct().size == backup.entries.size) { "La sauvegarde contient des activités en double." }
        require(backup.cases.map { it.number }.distinct().size == backup.cases.size)
        require(backup.audit.map { it.id }.distinct().size == backup.audit.size)
        backup.entries.forEach { e ->
            require(e.id.isNotBlank() && e.rawMinutes in 1..1440) { "Une activité sauvegardée possède des horaires invalides." }
            e.appliedRules.validate()
            require(listOf(e.effectiveMinutes, e.nonEffectiveMinutes, e.deductedMinutes, e.uncountedMinutes, e.roundingMinutes).all { it >= 0 })
            val classified = e.effectiveMinutes + e.nonEffectiveMinutes + e.deductedMinutes + e.uncountedMinutes + e.roundingMinutes
            require(classified <= e.rawMinutes) { "Les totaux d'une activité dépassent sa durée brute." }
        }
    }
}
