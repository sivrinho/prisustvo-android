package ba.prisustvo.app

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

const val STATUS_PRESENT = "P"
const val STATUS_ABSENT = "A"
const val STATUS_EXCUSED = "O"
const val STATUS_UNEXCUSED = "N"
const val STATUS_LATE = "K"
const val STATUS_LEFT_EARLY = "R"

val ALL_STATUSES = listOf(
    STATUS_PRESENT,
    STATUS_ABSENT,
    STATUS_EXCUSED,
    STATUS_UNEXCUSED,
    STATUS_LATE,
    STATUS_LEFT_EARLY
)

data class Student(val id: String, val name: String)

data class SessionRecord(
    val id: String,
    val week: Int,
    val lesson: Int,
    val date: String
)

data class AttendanceEntry(
    val status: String = "",
    val comment: String = ""
)

data class Classroom(
    val id: String,
    val name: String,
    val subject: String,
    val year: String,
    val students: List<Student> = emptyList(),
    val sessions: List<SessionRecord> = emptyList(),
    val attendance: Map<String, AttendanceEntry> = emptyMap()
)

data class StudentStats(
    val recorded: Int = 0,
    val present: Int = 0,
    val absent: Int = 0,
    val excused: Int = 0,
    val unexcused: Int = 0,
    val late: Int = 0,
    val leftEarly: Int = 0
) {
    val attended: Int get() = present + late + leftEarly
    val totalAbsences: Int get() = absent + excused + unexcused
    val attendancePercent: Int
        get() = if (recorded == 0) 0 else ((attended * 100.0) / recorded).toInt()
    val warning: Boolean
        get() = unexcused >= 5 || (recorded >= 5 && totalAbsences * 100.0 / recorded >= 20.0)
}

private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

fun parseAppDate(value: String): LocalDate? = try {
    LocalDate.parse(value.trim(), dateFormatter)
} catch (_: DateTimeParseException) {
    null
}

fun formatAppDate(value: LocalDate): String = value.format(dateFormatter)

fun sessionKey(sessionId: String, studentId: String) = "$sessionId|$studentId"

fun statusLabel(status: String): String = when (status) {
    STATUS_PRESENT -> "Prisutan"
    STATUS_ABSENT -> "Odsutan"
    STATUS_EXCUSED -> "Opravdano odsutan"
    STATUS_UNEXCUSED -> "Neopravdano odsutan"
    STATUS_LATE -> "Kasnio"
    STATUS_LEFT_EARLY -> "Izašao ranije"
    else -> "Nije evidentirano"
}

fun statusShortLabel(status: String): String = when (status) {
    STATUS_PRESENT -> "+"
    STATUS_ABSENT -> "−"
    STATUS_EXCUSED -> "O"
    STATUS_UNEXCUSED -> "N"
    STATUS_LATE -> "K"
    STATUS_LEFT_EARLY -> "R"
    else -> "?"
}

fun Classroom.statsFor(studentId: String): StudentStats {
    var result = StudentStats()
    sessions.forEach { session ->
        val status = attendance[sessionKey(session.id, studentId)]?.status.orEmpty()
        if (status.isBlank()) return@forEach
        result = result.copy(
            recorded = result.recorded + 1,
            present = result.present + if (status == STATUS_PRESENT) 1 else 0,
            absent = result.absent + if (status == STATUS_ABSENT) 1 else 0,
            excused = result.excused + if (status == STATUS_EXCUSED) 1 else 0,
            unexcused = result.unexcused + if (status == STATUS_UNEXCUSED) 1 else 0,
            late = result.late + if (status == STATUS_LATE) 1 else 0,
            leftEarly = result.leftEarly + if (status == STATUS_LEFT_EARLY) 1 else 0
        )
    }
    return result
}

fun Classroom.sessionStats(sessionId: String): StudentStats {
    var result = StudentStats()
    students.forEach { student ->
        val status = attendance[sessionKey(sessionId, student.id)]?.status.orEmpty()
        if (status.isBlank()) return@forEach
        result = result.copy(
            recorded = result.recorded + 1,
            present = result.present + if (status == STATUS_PRESENT) 1 else 0,
            absent = result.absent + if (status == STATUS_ABSENT) 1 else 0,
            excused = result.excused + if (status == STATUS_EXCUSED) 1 else 0,
            unexcused = result.unexcused + if (status == STATUS_UNEXCUSED) 1 else 0,
            late = result.late + if (status == STATUS_LATE) 1 else 0,
            leftEarly = result.leftEarly + if (status == STATUS_LEFT_EARLY) 1 else 0
        )
    }
    return result
}

fun Classroom.anchorSession(): SessionRecord? = sessions
    .filter { parseAppDate(it.date) != null }
    .minByOrNull { it.week }

fun Classroom.weekRange(week: Int): String {
    val anchor = anchorSession() ?: return "Datum nije postavljen"
    val anchorDate = parseAppDate(anchor.date) ?: return "Datum nije postavljen"
    val anchorMonday = anchorDate.with(DayOfWeek.MONDAY)
    val monday = anchorMonday.plusWeeks((week - anchor.week).toLong())
    val friday = monday.plusDays(4)
    return "${monday.format(DateTimeFormatter.ofPattern("dd.MM."))}–${friday.format(dateFormatter)}"
}

fun Classroom.defaultDateForWeek(week: Int): String {
    val existing = sessions.firstOrNull { it.week == week && parseAppDate(it.date) != null }
    if (existing != null) return existing.date
    val anchor = anchorSession() ?: return formatAppDate(LocalDate.now())
    val anchorDate = parseAppDate(anchor.date) ?: return formatAppDate(LocalDate.now())
    val monday = anchorDate.with(DayOfWeek.MONDAY).plusWeeks((week - anchor.week).toLong())
    return formatAppDate(monday)
}

fun Classroom.monthlyStats(): Map<YearMonth, StudentStats> {
    val grouped = linkedMapOf<YearMonth, StudentStats>()
    sessions.sortedBy { parseAppDate(it.date) ?: LocalDate.MAX }.forEach { session ->
        val date = parseAppDate(session.date) ?: return@forEach
        val month = YearMonth.from(date)
        val s = sessionStats(session.id)
        val current = grouped[month] ?: StudentStats()
        grouped[month] = StudentStats(
            recorded = current.recorded + s.recorded,
            present = current.present + s.present,
            absent = current.absent + s.absent,
            excused = current.excused + s.excused,
            unexcused = current.unexcused + s.unexcused,
            late = current.late + s.late,
            leftEarly = current.leftEarly + s.leftEarly
        )
    }
    return grouped
}

fun surnameKey(name: String): String = name.trim().split(Regex("\\s+")).lastOrNull().orEmpty().lowercase()
