package ba.prisustvo.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PrisustvoRoot(this) }
    }
}

@Composable
private fun PrisustvoRoot(context: Context) {
    val store = remember { AttendanceStore(context) }
    val settings = remember { context.getSharedPreferences("prisustvo_settings", Context.MODE_PRIVATE) }
    var darkMode by remember { mutableStateOf(settings.getBoolean("dark_mode", false)) }
    var classes by remember { mutableStateOf(store.load()) }
    var classIndex by remember { mutableStateOf<Int?>(null) }
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    var showReports by remember { mutableStateOf(false) }
    var profileStudentId by remember { mutableStateOf<String?>(null) }

    fun update(newClasses: List<Classroom>) {
        classes = newClasses
        store.save(newClasses)
    }

    fun updateClass(index: Int, transform: (Classroom) -> Classroom) {
        val next = classes.toMutableList()
        next[index] = transform(next[index])
        update(next)
    }

    MaterialTheme(colorScheme = if (darkMode) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            val idx = classIndex
            when {
                idx == null -> HomeScreen(
                    classes = classes,
                    darkMode = darkMode,
                    onToggleDark = {
                        darkMode = !darkMode
                        settings.edit().putBoolean("dark_mode", darkMode).apply()
                    },
                    onOpen = { classIndex = it },
                    onAdd = { update(classes + it) },
                    onDelete = { deleteIndex -> update(classes.filterIndexed { i, _ -> i != deleteIndex }) },
                    onBackup = {
                        val file = ExportUtils.createBackup(context, store.exportJson(classes))
                        ExportUtils.shareFile(context, file, "application/json", "Sačuvaj / podijeli backup")
                    }
                )
                profileStudentId != null -> {
                    val classroom = classes[idx]
                    val student = classroom.students.firstOrNull { it.id == profileStudentId }
                    if (student == null) profileStudentId = null else StudentProfileScreen(
                        classroom = classroom,
                        student = student,
                        onBack = { profileStudentId = null },
                        onPdf = {
                            val file = ExportUtils.createStudentPdf(context, classroom, student)
                            ExportUtils.shareFile(context, file, "application/pdf", "Podijeli / odštampaj izvještaj učenika")
                        }
                    )
                }
                showReports -> ReportsScreen(
                    classroom = classes[idx],
                    onBack = { showReports = false },
                    onStudent = { profileStudentId = it },
                    onPdf = {
                        val file = ExportUtils.createClassPdf(context, classes[idx])
                        ExportUtils.shareFile(context, file, "application/pdf", "Podijeli / odštampaj PDF izvještaj")
                    },
                    onCsv = {
                        val file = ExportUtils.createCsv(context, classes[idx])
                        ExportUtils.shareFile(context, file, "text/csv", "Podijeli CSV izvještaj")
                    }
                )
                selectedSessionId != null -> {
                    val classroom = classes[idx]
                    val session = classroom.sessions.firstOrNull { it.id == selectedSessionId }
                    if (session == null) selectedSessionId = null else AttendanceScreen(
                        classroom = classroom,
                        session = session,
                        onBack = { selectedSessionId = null },
                        onDate = { date ->
                            updateClass(idx) { c -> c.copy(sessions = c.sessions.map { if (it.id == session.id) it.copy(date = date) else it }) }
                        },
                        onStatus = { studentId, status ->
                            updateClass(idx) { c ->
                                val key = sessionKey(session.id, studentId)
                                val current = c.attendance[key] ?: AttendanceEntry()
                                c.copy(attendance = c.attendance + (key to current.copy(status = status)))
                            }
                        },
                        onComment = { studentId, comment ->
                            updateClass(idx) { c ->
                                val key = sessionKey(session.id, studentId)
                                val current = c.attendance[key] ?: AttendanceEntry()
                                c.copy(attendance = c.attendance + (key to current.copy(comment = comment)))
                            }
                        },
                        onAllPresent = {
                            updateClass(idx) { c ->
                                val map = c.attendance.toMutableMap()
                                c.students.forEach { student ->
                                    val key = sessionKey(session.id, student.id)
                                    val current = map[key] ?: AttendanceEntry()
                                    map[key] = current.copy(status = STATUS_PRESENT)
                                }
                                c.copy(attendance = map)
                            }
                        },
                        onProfile = { profileStudentId = it; selectedSessionId = null }
                    )
                }
                else -> ClassroomScreen(
                    classroom = classes[idx],
                    onBack = { classIndex = null },
                    onReports = { showReports = true },
                    onOpenSession = { selectedSessionId = it },
                    onCreateSession = { week ->
                        val c = classes[idx]
                        val lesson = (c.sessions.filter { it.week == week }.maxOfOrNull { it.lesson } ?: 0) + 1
                        val id = UUID.randomUUID().toString()
                        val session = SessionRecord(id, week, lesson, c.defaultDateForWeek(week))
                        val attendance = c.attendance.toMutableMap()
                        c.students.forEach { attendance[sessionKey(id, it.id)] = AttendanceEntry(STATUS_PRESENT, "") }
                        updateClass(idx) { it.copy(sessions = it.sessions + session, attendance = attendance) }
                        selectedSessionId = id
                    },
                    onAddStudent = { name ->
                        updateClass(idx) { it.copy(students = it.students + Student(UUID.randomUUID().toString(), name)) }
                    },
                    onDeleteStudent = { studentId ->
                        updateClass(idx) { c ->
                            c.copy(
                                students = c.students.filterNot { it.id == studentId },
                                attendance = c.attendance.filterKeys { !it.endsWith("|$studentId") }
                            )
                        }
                    },
                    onStudent = { profileStudentId = it }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    classes: List<Classroom>,
    darkMode: Boolean,
    onToggleDark: () -> Unit,
    onOpen: (Int) -> Unit,
    onAdd: (Classroom) -> Unit,
    onDelete: (Int) -> Unit,
    onBackup: () -> Unit
) {
    var addDialog by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Evidencija prisustva") },
                actions = {
                    TextButton(onClick = onBackup) { Text("Backup") }
                    TextButton(onClick = onToggleDark) { Text(if (darkMode) "☀" else "☾") }
                }
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { addDialog = true }) { Text("+") } }
    ) { pad ->
        if (classes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("Dodaj prvi razred pomoću + dugmeta") }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp)) {
                itemsIndexed(classes) { index, c ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onOpen(index) }) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text(listOf(c.subject, c.year).filter { it.isNotBlank() }.joinToString(" • "))
                                Text("${c.students.size} učenika • ${c.sessions.size} časova", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { onDelete(index) }) { Text("Obriši") }
                        }
                    }
                }
            }
        }
    }
    if (addDialog) AddClassDialog(onDismiss = { addDialog = false }) { n, s, y ->
        onAdd(Classroom(UUID.randomUUID().toString(), n, s, y)); addDialog = false
    }
}

@Composable
private fun AddClassDialog(onDismiss: () -> Unit, onAdd: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("2026/2027") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novi razred") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Razred / odjeljenje") })
            OutlinedTextField(subject, { subject = it }, label = { Text("Predmet") })
            OutlinedTextField(year, { year = it }, label = { Text("Školska godina") })
        } },
        confirmButton = { Button(onClick = { onAdd(name.trim(), subject.trim(), year.trim()) }, enabled = name.isNotBlank()) { Text("Dodaj") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Odustani") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClassroomScreen(
    classroom: Classroom,
    onBack: () -> Unit,
    onReports: () -> Unit,
    onOpenSession: (String) -> Unit,
    onCreateSession: (Int) -> Unit,
    onAddStudent: (String) -> Unit,
    onDeleteStudent: (String) -> Unit,
    onStudent: (String) -> Unit
) {
    var studentsExpanded by remember { mutableStateOf(false) }
    var addStudent by remember { mutableStateOf(false) }
    var studentSearch by remember { mutableStateOf("") }
    var sortSurname by remember { mutableStateOf(false) }
    var weekSearch by remember { mutableStateOf("") }

    val students = classroom.students
        .filter { it.name.contains(studentSearch, ignoreCase = true) }
        .let { list -> if (sortSurname) list.sortedBy { surnameKey(it.name) } else list }
    val weeks = (1..36).filter { w ->
        weekSearch.isBlank() || w.toString().contains(weekSearch) || classroom.weekRange(w).contains(weekSearch, ignoreCase = true) ||
            classroom.sessions.any { it.week == w && it.date.contains(weekSearch, ignoreCase = true) }
    }

    Scaffold(topBar = { TopAppBar(
        title = { Column { Text(classroom.name); Text(classroom.subject, style = MaterialTheme.typography.labelMedium) } },
        navigationIcon = { TextButton(onClick = onBack) { Text("‹ Nazad") } },
        actions = { TextButton(onClick = onReports) { Text("Izvještaji") } }
    ) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Učenici (${classroom.students.size})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { studentsExpanded = !studentsExpanded }) { Text(if (studentsExpanded) "Sakrij" else "Prikaži") }
                            Button(onClick = { addStudent = true }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("+ Učenik") }
                        }
                        if (studentsExpanded) {
                            OutlinedTextField(studentSearch, { studentSearch = it }, label = { Text("Pretraži učenika") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(sortSurname, { sortSurname = it })
                                Text("Sortiraj po prezimenu")
                            }
                            students.forEachIndexed { index, student ->
                                val stats = classroom.statsFor(student.id)
                                Row(Modifier.fillMaxWidth().heightIn(min = 38.dp).clickable { onStudent(student.id) }, verticalAlignment = Alignment.CenterVertically) {
                                    Text("${index + 1}. ${student.name}", Modifier.weight(1f), maxLines = 1)
                                    Text("${stats.totalAbsences} izost.", style = MaterialTheme.typography.labelSmall)
                                    if (stats.warning) Text("  ⚠", color = MaterialTheme.colorScheme.error)
                                    TextButton(onClick = { onDeleteStudent(student.id) }) { Text("×") }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text("Nastavni tjedni", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(weekSearch, { weekSearch = it }, label = { Text("Traži tjedan ili datum") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            items(weeks) { week ->
                val sessions = classroom.sessions.filter { it.week == week }.sortedBy { it.lesson }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Tjedan $week", fontWeight = FontWeight.Bold)
                                Text(classroom.weekRange(week), style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = { onCreateSession(week) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("+ čas") }
                        }
                        if (sessions.isEmpty()) {
                            Text("Nema evidentiranog časa", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                sessions.forEach { session ->
                                    OutlinedButton(onClick = { onOpenSession(session.id) }) {
                                        Text("Čas ${session.lesson} • ${session.date.ifBlank { "datum" }}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (addStudent) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addStudent = false },
            title = { Text("Dodaj učenika") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Ime i prezime") }) },
            confirmButton = { Button(onClick = { onAddStudent(name.trim()); addStudent = false }, enabled = name.isNotBlank()) { Text("Dodaj") } },
            dismissButton = { TextButton(onClick = { addStudent = false }) { Text("Odustani") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttendanceScreen(
    classroom: Classroom,
    session: SessionRecord,
    onBack: () -> Unit,
    onDate: (String) -> Unit,
    onStatus: (String, String) -> Unit,
    onComment: (String, String) -> Unit,
    onAllPresent: () -> Unit,
    onProfile: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var date by remember(session.id, session.date) { mutableStateOf(session.date) }
    var search by remember { mutableStateOf("") }
    var onlyAbsent by remember { mutableStateOf(false) }
    var commentStudentId by remember { mutableStateOf<String?>(null) }
    val summary = classroom.sessionStats(session.id)
    val visibleStudents = classroom.students.filter { student ->
        val entry = classroom.attendance[sessionKey(session.id, student.id)] ?: AttendanceEntry()
        val absentMatch = !onlyAbsent || entry.status in listOf(STATUS_ABSENT, STATUS_EXCUSED, STATUS_UNEXCUSED)
        absentMatch && student.name.contains(search, ignoreCase = true)
    }

    Scaffold(topBar = { TopAppBar(
        title = { Column { Text("${classroom.name} • Tjedan ${session.week}"); Text("Čas ${session.lesson} • ${classroom.weekRange(session.week)}", style = MaterialTheme.typography.labelSmall) } },
        navigationIcon = { TextButton(onClick = onBack) { Text("‹ Nazad") } }
    ) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(date, { date = it; onDate(it) }, label = { Text("Datum") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = onAllPresent) { Text("Svi +") }
            }
            Text(
                "Pris. ${summary.attended} • Izost. ${summary.totalAbsences} • Oprav. ${summary.excused} • Neopr. ${summary.unexcused} • Kasni ${summary.late}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 6.dp)
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(search, { search = it }, label = { Text("Pretraži učenika") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(4.dp))
                FilterChip(selected = onlyAbsent, onClick = { onlyAbsent = !onlyAbsent }, label = { Text("Izostanci") })
            }
            Text("+ prisutan • − odsutan • O opravdano • N neopravdano • K kasnio • R ranije", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 4.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(visibleStudents, key = { _, s -> s.id }) { _, student ->
                    val entry = classroom.attendance[sessionKey(session.id, student.id)] ?: AttendanceEntry()
                    val totalAbs = classroom.statsFor(student.id).totalAbsences
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.widthIn(min = 110.dp, max = 150.dp).clickable { onProfile(student.id) }) {
                                Text(student.name, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text("$totalAbs izost. ${if (entry.comment.isNotBlank()) "• komentar" else ""}", style = MaterialTheme.typography.labelSmall)
                            }
                            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                listOf(
                                    STATUS_PRESENT to "+",
                                    STATUS_ABSENT to "−",
                                    STATUS_EXCUSED to "O",
                                    STATUS_UNEXCUSED to "N",
                                    STATUS_LATE to "K",
                                    STATUS_LEFT_EARLY to "R"
                                ).forEach { (status, label) ->
                                    CompactStatusButton(label, status, entry.status == status) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onStatus(student.id, status)
                                    }
                                }
                                TextButton(onClick = { commentStudentId = student.id }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                    Text(if (entry.comment.isBlank()) "○" else "●")
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    val commentStudent = classroom.students.firstOrNull { it.id == commentStudentId }
    if (commentStudent != null) {
        val current = classroom.attendance[sessionKey(session.id, commentStudent.id)]?.comment.orEmpty()
        var text by remember(commentStudent.id, current) { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { commentStudentId = null },
            title = { Text("Komentar – ${commentStudent.name}") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("Komentar za ovo prisustvo") }, modifier = Modifier.fillMaxWidth(), minLines = 3) },
            confirmButton = { Button(onClick = { onComment(commentStudent.id, text.trim()); commentStudentId = null }) { Text("Sačuvaj") } },
            dismissButton = { TextButton(onClick = { commentStudentId = null }) { Text("Odustani") } }
        )
    }
}

@Composable
private fun CompactStatusButton(label: String, status: String, selected: Boolean, onClick: () -> Unit) {
    val color = when (status) {
        STATUS_PRESENT -> MaterialTheme.colorScheme.primary
        STATUS_ABSENT -> MaterialTheme.colorScheme.error
        STATUS_EXCUSED -> MaterialTheme.colorScheme.tertiary
        STATUS_UNEXCUSED -> MaterialTheme.colorScheme.error
        STATUS_LATE -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.secondary
    }
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.size(38.dp), contentPadding = PaddingValues(0.dp), colors = ButtonDefaults.buttonColors(containerColor = color)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.size(38.dp), contentPadding = PaddingValues(0.dp)) { Text(label) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportsScreen(
    classroom: Classroom,
    onBack: () -> Unit,
    onStudent: (String) -> Unit,
    onPdf: () -> Unit,
    onCsv: () -> Unit
) {
    val months = classroom.monthlyStats()
    Scaffold(topBar = { TopAppBar(
        title = { Text("Izvještaji • ${classroom.name}") },
        navigationIcon = { TextButton(onClick = onBack) { Text("‹ Nazad") } }
    ) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPdf, modifier = Modifier.weight(1f)) { Text("PDF / Print") }
                    OutlinedButton(onClick = onCsv, modifier = Modifier.weight(1f)) { Text("CSV / Excel") }
                }
            }
            item { Text("Mjesečni pregled", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
            if (months.isEmpty()) item { Text("Još nema evidentiranih časova.") }
            items(months.entries.toList()) { (month, stats) ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(monthLabel(month), Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Text("Pris. ${stats.attended}  Izost. ${stats.totalAbsences}  N ${stats.unexcused}")
                    }
                }
            }
            item { Text("Ukupni pregled učenika", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
            items(classroom.students.sortedBy { surnameKey(it.name) }) { student ->
                val s = classroom.statsFor(student.id)
                Card(Modifier.fillMaxWidth().clickable { onStudent(student.id) }) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(student.name, fontWeight = FontWeight.Medium)
                            Text("${s.attendancePercent}% prisustva • ${s.totalAbsences} izostanaka • ${s.unexcused} neopravdanih", style = MaterialTheme.typography.bodySmall)
                        }
                        if (s.warning) Text("⚠", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudentProfileScreen(classroom: Classroom, student: Student, onBack: () -> Unit, onPdf: () -> Unit) {
    val stats = classroom.statsFor(student.id)
    Scaffold(topBar = { TopAppBar(
        title = { Text(student.name) },
        navigationIcon = { TextButton(onClick = onBack) { Text("‹ Nazad") } },
        actions = { TextButton(onClick = onPdf) { Text("PDF") } }
    ) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("Prisustvo ${stats.attendancePercent}%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Evidentirano: ${stats.recorded} • Prisutan: ${stats.present} • Izostanci: ${stats.totalAbsences}")
                        Text("Opravdano: ${stats.excused} • Neopravdano: ${stats.unexcused} • Kasnio: ${stats.late} • Ranije: ${stats.leftEarly}")
                        if (stats.warning) Text("Upozorenje: povećan broj izostanaka", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item { Text("Historija", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
            items(classroom.sessions.sortedWith(compareByDescending<SessionRecord> { it.week }.thenByDescending { it.lesson })) { session ->
                val entry = classroom.attendance[sessionKey(session.id, student.id)] ?: AttendanceEntry()
                if (entry.status.isNotBlank()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Text("Tjedan ${session.week} • Čas ${session.lesson}", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                Text(session.date)
                            }
                            Text(statusLabel(entry.status), color = statusColor(entry.status))
                            if (entry.comment.isNotBlank()) Text("Komentar: ${entry.comment}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusColor(status: String) = when (status) {
    STATUS_PRESENT -> MaterialTheme.colorScheme.primary
    STATUS_ABSENT, STATUS_UNEXCUSED -> MaterialTheme.colorScheme.error
    STATUS_EXCUSED -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.secondary
}

private fun monthLabel(month: YearMonth): String {
    val formatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale("bs", "BA"))
    return month.format(formatter).replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("bs", "BA")) else it.toString() }
}
