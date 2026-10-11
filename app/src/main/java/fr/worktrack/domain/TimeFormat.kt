package fr.worktrack.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

val French: Locale = Locale.FRANCE
val LongDate: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", French)
val ShortDate: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", French)
val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", French)
fun duration(minutes: Int, format: DurationFormat = DurationFormat.HOURS_MINUTES): String =
    if (format == DurationFormat.DECIMAL) String.format(French, "%.2f h", minutes / 60.0)
    else if (minutes % 60 == 0) "${minutes / 60} h" else "${minutes / 60} h ${"%02d".format(minutes % 60)}"
fun decimalHours(minutes: Int): String = String.format(French, "%.2f", minutes / 60.0)
fun weekStart(date: LocalDate, monday: Boolean = true): LocalDate =
    date.minusDays((if (monday) date.dayOfWeek.value - 1 else date.dayOfWeek.value % 7).toLong())
