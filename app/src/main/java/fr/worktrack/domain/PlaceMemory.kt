package fr.worktrack.domain

import java.time.LocalDateTime

/** Pauses do not move the technician; an overlapping/future entry cannot be the departure. */
object PlaceMemory {
    fun before(entries: List<WorkEntry>, start: LocalDateTime, excludedId: String? = null): KnownPlace? =
        entries.asSequence().filter { it.id != excludedId && it.endTime <= start }
            .sortedByDescending { it.endTime }.mapNotNull { it.arrival }
            .firstOrNull { it.type != PlaceType.CUSTOMER_SITE || it.name.isNotBlank() }

    /** Only the immediately preceding completed trip can prefill a client intervention. */
    fun interventionSiteBefore(entries: List<WorkEntry>, start: LocalDateTime, excludedId: String? = null): String? =
        entries.asSequence().filter { it.id != excludedId && it.endTime <= start }
            .maxWithOrNull(compareBy<WorkEntry> { it.endTime }.thenBy { it.startTime })
            ?.takeIf { it.type.isTravel }?.arrival
            ?.takeIf { it.type == PlaceType.CUSTOMER_SITE && it.name.isNotBlank() }?.name
}
