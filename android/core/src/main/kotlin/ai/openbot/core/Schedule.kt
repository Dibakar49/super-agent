package ai.openbot.core

import java.time.*

const val MINIMUM_INTERVAL_MS = 15L * 60 * 1000

class ScheduleRefusedException(
    message: String,
    val code: String? = null,
) : IllegalArgumentException(message)

private const val UNREADABLE = "That schedule could not be read."
private const val UNKNOWN_ZONE = "That is not a timezone I know."
private const val TOO_FREQUENT = "Routines may run at most every 15 minutes."
private val floorVerdicts = LinkedHashMap<String, String?>()
private val zoneNames by lazy { ZoneId.getAvailableZoneIds().associateBy { it.lowercase() } }

private fun unreadable(): Nothing = throw ScheduleRefusedException(UNREADABLE)

/** Local calendar iteration, preserving wall-clock time across timezone/DST transitions. */
private class CronPlan(
    val cron: String,
    val zone: ZoneId,
) {
    private val fields = cron.trim().split(Regex("\\s+"))
    private val minutes: List<Int>
    private val hours: List<Int>
    private val months: Set<Int>
    private val monthDays: Set<Int>
    private val weekdays: Set<Int>
    private val lastMonthDay: Boolean
    private val lastWeekdays: Set<Int>
    private val nthWeekday: Int?
    private val domWildcard: Boolean
    private val dowWildcard: Boolean

    init {
        if (fields.size != 5)unreadable()
        minutes = numbers(fields[0], 0, 59).sorted()
        hours = numbers(fields[1], 0, 23).sorted()
        months = numbers(aliases(fields[3], monthAliases), 1, 12)
        val dom = fields[2].uppercase()
        lastMonthDay = dom.split(',').contains("L")
        val plainDom = dom.split(',').filterNot { it == "L" }.joinToString(",")
        monthDays = if (plainDom.isEmpty())emptySet() else numbers(plainDom, 1, 31)
        domWildcard = dom in listOf("*", "?")
        val dow = aliases(fields[4].uppercase(), dayAliases)
        val nthParts = dow.split('#')
        if (nthParts.size > 2)unreadable()
        nthWeekday = if (nthParts.size == 2)nthParts[1].toIntOrNull()?.takeIf { it in 1..5 } ?: unreadable() else null
        val pieces = nthParts[0].split(',')
        lastWeekdays =
            pieces
                .filter { it.endsWith("L") }
                .map {
                    it
                        .dropLast(1)
                        .toIntOrNull()
                        ?.takeIf { day -> day in 0..7 }
                        ?.rem(7) ?: unreadable()
                }.toSet()
        val plainDow = pieces.filterNot { it.endsWith("L") }.joinToString(",")
        weekdays = if (plainDow.isEmpty())emptySet() else numbers(plainDow, 0, 7).map { it % 7 }.toSet()
        dowWildcard = dow in listOf("*", "?")
    }

    private fun matches(date: LocalDate): Boolean {
        if (date.monthValue !in months)return false
        val dom = date.dayOfMonth in monthDays || lastMonthDay && date.dayOfMonth == date.lengthOfMonth()
        val day = date.dayOfWeek.value % 7
        val dow =
            (day in weekdays && (nthWeekday == null || (date.dayOfMonth - 1) / 7 + 1 == nthWeekday)) ||
                (day in lastWeekdays && date.plusWeeks(1).month != date.month)
        return when {
            !domWildcard && !dowWildcard -> dom || dow
            !dowWildcard -> dow
            else -> dom
        }
    }

    fun next(after: Instant): Instant {
        var date = after.atZone(zone).toLocalDate()
        val last = date.plusYears(8)
        while (!date.isAfter(last)) {
            if (matches(date)) {
                val candidates = mutableSetOf<Instant>()
                for (hour in hours) {
                    for (minute in minutes) {
                        val local = date.atTime(hour, minute)
                        val offsets = zone.rules.getValidOffsets(local)
                        when (offsets.size) {
                            0 -> {
                                val transition = zone.rules.getTransition(local)
                                if (transition != null && transition.duration.toHours() < 24)candidates.add(local.atZone(zone).toInstant())
                            }
                            1 -> candidates.add(local.atOffset(offsets[0]).toInstant())
                            else -> {
                                candidates.add(local.atOffset(offsets[0]).toInstant())
                                // cron-parser repeats the wall clock only for an all-hours schedule.
                                // A fresh search already in the second occurrence keeps that offset.
                                if (hours.size == 24 || after.atZone(zone).offset == offsets[1])candidates.add(local.atOffset(offsets[1]).toInstant())
                            }
                        }
                    }
                }
                candidates.filter { it > after }.minOrNull()?.let { return it }
            }
            date = date.plusDays(1)
        }
        unreadable()
    }
}

private val monthAliases = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC").mapIndexed { index, name -> name to (index + 1) }.toMap()
private val dayAliases = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT").mapIndexed { index, name -> name to index }.toMap()

private fun aliases(
    field: String,
    values: Map<String, Int>,
): String {
    var value = field.uppercase()
    values.forEach { (name, number) -> value = value.replace(name, number.toString()) }
    return value
}

private fun numbers(
    field: String,
    min: Int,
    max: Int,
): Set<Int> {
    val result = sortedSetOf<Int>()
    if (field.isBlank())unreadable()
    for (piece in field.split(',')) {
        val stepped = piece.split('/')
        if (stepped.size > 2)unreadable()
        val step = if (stepped.size == 2)stepped[1].toIntOrNull()?.takeIf { it > 0 } ?: unreadable() else 1
        val range = stepped[0]
        val bounds =
            if (range in listOf("*", "?")) {
                listOf(min, max)
            } else {
                val split = range.split('-')
                if (split.size > 2)unreadable()
                val start = split[0].toIntOrNull() ?: unreadable()
                val end =
                    if (split.size == 2) {
                        split[1].toIntOrNull() ?: unreadable()
                    } else if (stepped.size == 2) {
                        max
                    } else {
                        start
                    }
                listOf(start, end)
            }
        if (bounds[0] !in min..max || bounds[1] !in min..max || bounds[0] > bounds[1])unreadable()
        var value = bounds[0]
        while (value <= bounds[1]) {
            result.add(value)
            if (step > max - min + 1)break
            value += step
        }
    }
    if (result.isEmpty())unreadable()
    return result
}

/** Same five-field contract, fixed 200-occurrence floor scan and runtime pair check as schedule.ts. */
fun nextOccurrence(
    cron: String,
    timezone: String,
    after: Instant,
): Instant {
    if (cron.trim().split(Regex("\\s+")).size != 5)unreadable()
    val zone =
        try {
            ZoneId.of(zoneNames[timezone.lowercase()] ?: timezone)
        } catch (_: Exception) {
            throw ScheduleRefusedException(UNKNOWN_ZONE, "unknown_timezone")
        }
    val plan =
        try {
            CronPlan(cron, zone)
        } catch (failure: ScheduleRefusedException) {
            throw failure
        } catch (_: Exception) {
            unreadable()
        }
    val cacheKey = "$timezone\u0000$cron"
    synchronized(floorVerdicts) {
        if (!floorVerdicts.containsKey(cacheKey)) {
            var verdict: String? = null
            try {
                var previous = plan.next(Instant.parse("2024-01-01T00:00:00Z"))
                repeat(199) {
                    val current = plan.next(previous)
                    if (Duration.between(previous, current).toMillis() < MINIMUM_INTERVAL_MS) {
                        verdict = TOO_FREQUENT
                        throw ScheduleRefusedException(TOO_FREQUENT)
                    }
                    previous = current
                }
            } catch (_: Exception) {
                // Parse/exhaustion is reported by the actual scheduling operation.
            }
            if (floorVerdicts.size >= 1000)floorVerdicts.clear()
            floorVerdicts[cacheKey] = verdict
        }
        floorVerdicts[cacheKey]?.let { throw ScheduleRefusedException(it) }
    }
    val first = plan.next(after)
    val second = plan.next(first)
    if (Duration.between(first, second).toMillis() < MINIMUM_INTERVAL_MS)throw ScheduleRefusedException(TOO_FREQUENT)
    return first
}

/** The original deliberately narrow prose formatter: unfamiliar cron stays verbatim. */
fun describeCron(cron: String): String {
    val fields = cron.trim().split(Regex("\\s+"))
    if (fields.size != 5)return cron
    val(minuteField, hourField, dayField, monthField, weekdayField) = fields

    fun plain(
        field: String,
        min: Int,
        max: Int,
    ) = field.takeIf { Regex("^\\d{1,2}$").matches(it) }?.toIntOrNull()?.takeIf { it in min..max }

    fun time(
        hour: Int,
        minute: Int,
    ) = "%02d:%02d".format(java.util.Locale.ROOT, hour, minute)

    fun words(items: List<String>) = if (items.size <= 1)items.joinToString("") else items.dropLast(1).joinToString(", ") + " and " + items.last()
    val wide = dayField == "*" && monthField == "*" && weekdayField == "*"
    if (wide && hourField == "*") {
        val step =
            Regex("^\\*/(\\d{1,2})$")
                .find(minuteField)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        if (step != null && step > 1 && 60 % step == 0)return "Every $step minutes"
    }
    val minute = plain(minuteField, 0, 59)
    val hour = plain(hourField, 0, 23)
    if (wide && hourField == "*" && minute != null)return if (minute == 0)"Every hour" else "Every hour at %02d past".format(java.util.Locale.ROOT, minute)
    if (wide && hour != null && Regex("^\\d{1,2}(,\\d{1,2})+$").matches(minuteField)) {
        val list = minuteField.split(',').map { plain(it, 0, 59) }
        if (list.all { it != null }) {
            return "Every day at " +
                words(
                    list
                        .filterNotNull()
                        .distinct()
                        .sorted()
                        .map { time(hour, it) },
                )
        }
    }
    if (minute == null || hour == null)return cron
    val clock = time(hour, minute)
    if (dayField == "*" && monthField == "*") {
        if (weekdayField == "*")return "Every day at $clock"
        if (weekdayField == "1-5")return "Weekdays at $clock"
        if (Regex("^[0-7](,[0-7])*$").matches(weekdayField)) {
            val names = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
            return words(
                weekdayField
                    .split(',')
                    .map { it.toInt() % 7 }
                    .distinct()
                    .map { "${names[it]}s" },
            ) + " at $clock"
        }
    }
    if (monthField == "*" && weekdayField == "*") {
        plain(dayField, 1, 31)?.let { day ->
            val suffix =
                if (day % 100 in 11..13) {
                    "th"
                } else {
                    when (day % 10) {
                        1 -> "st"
                        2 -> "nd"
                        3 -> "rd"
                        else -> "th"
                    }
                }
            return "On the $day$suffix of the month at $clock"
        }
    }
    return cron
}
