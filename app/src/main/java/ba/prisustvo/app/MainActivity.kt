package ba.prisustvo.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

private data class Student(val id: String, val name: String)
private data class Classroom(
    val name: String,
    val subject: String,
    val year: String,
    val students: List<Student> = emptyList(),
    val attendance: Map<String, String> = emptyMap(),
    val dates: Map<Int, String> = emptyMap()
)

private class AttendanceStore(context: Context) {
    private val prefs = context.getSharedPreferences("prisustvo_local", Context.MODE_PRIVATE)

    fun save(classes: List<Classroom>) {
        val root = JSONArray()
        classes.forEach { c ->
            val obj = JSONObject()
                .put("name", c.name)
                .put("subject", c.subject)
                .put("year", c.year)
            val students = JSONArray()
            c.students.forEach { students.put(JSONObject().put("id", it.id).put("name", it.name)) }
            obj.put("students", students)
            obj.put("attendance", JSONObject(c.attendance))
            val dates = JSONObject()
            c.dates.forEach { (k, v) -> dates.put(k.toString(), v) }
            obj.put("dates", dates)
            root.put(obj)
        }
        prefs.edit().putString("data", root.toString()).apply()
    }

    fun load(): List<Classroom> = try {
        val text = prefs.getString("data", "[]") ?: "[]"
        val root = JSONArray(text)
        buildList {
            for (i in 0 until root.length()) {
                val obj = root.getJSONObject(i)
                val studentsJson = obj.optJSONArray("students") ?: JSONArray()
                val students = buildList {
                    for (s in 0 until studentsJson.length()) {
                        val st = studentsJson.getJSONObject(s)
                        add(Student(st.getString("id"), st.getString("name")))
                    }
                }
                val attendanceObj = obj.optJSONObject("attendance") ?: JSONObject()
                val attendance = mutableMapOf<String, String>()
                attendanceObj.keys().forEach { key -> attendance[key] = attendanceObj.getString(key) }
                val datesObj = obj.optJSONObject("dates") ?: JSONObject()
                val dates = mutableMapOf<Int, String>()
                datesObj.keys().forEach { key -> dates[key.toInt()] = datesObj.getString(key) }
                add(
                    Classroom(
                        name = obj.getString("name"),
                        subject = obj.optString("subject"),
                        year = obj.optString("year"),
                        students = students,
                        attendance = attendance,
                        dates = dates
                    )
                )
            }
        }
    } catch (_: Exception) { emptyList() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { PrisustvoApp(this) } }
    }
}

@Composable
private fun PrisustvoApp(context: Context) {
    val store = remember { AttendanceStore(context) }
    var classes by remember { mutableStateOf(store.load()) }
    var classIndex by remember { mutableStateOf<Int?>(null) }
    var week by remember { mutableStateOf<Int?>(null) }

    fun update(newClasses: List<Classroom>) {
        classes = newClasses
        store.save(newClasses)
    }

    Surface(Modifier.fillMaxSize()) {
        when {
            classIndex == null -> HomeScreen(
                classes = classes,
                onOpen = { classIndex = it },
                onAdd = { update(classes + it) },
                onDelete = { idx -> update(classes.filterIndexed { i, _ -> i != idx }) }
            )
            week == null -> ClassroomScreen(
                classroom = classes[classIndex!!],
                onBack = { classIndex = null },
                onOpenWeek = { week = it },
                onAddStudent = { name ->
                    val idx = classIndex!!
                    val c = classes[idx]
                    val updated = c.copy(students = c.students + Student(UUID.randomUUID().toString(), name))
                    update(classes.toMutableList().also { it[idx] = updated })
                },
                onDeleteStudent = { studentId ->
                    val idx = classIndex!!
                    val c = classes[idx]
                    val updated = c.copy(
                        students = c.students.filterNot { it.id == studentId },
                        attendance = c.attendance.filterKeys { !it.endsWith("|$studentId") }
                    )
                    update(classes.toMutableList().also { it[idx] = updated })
                }
            )
            else -> WeekScreen(
                classroom = classes[classIndex!!],
                week = week!!,
                onBack = { week = null },
                onDate = { date ->
                    val idx = classIndex!!
                    val c = classes[idx]
                    update(classes.toMutableList().also { it[idx] = c.copy(dates = c.dates + (week!! to date)) })
                },
                onStatus = { studentId, status ->
                    val idx = classIndex!!
                    val c = classes[idx]
                    val key = "${week!!}|$studentId"
                    val map = c.attendance.toMutableMap()
                    if (status.isBlank()) map.remove(key) else map[key] = status
                    update(classes.toMutableList().also { it[idx] = c.copy(attendance = map) })
                },
                onAllPresent = {
                    val idx = classIndex!!
                    val c = classes[idx]
                    val map = c.attendance.toMutableMap()
                    c.students.forEach { map["${week!!}|${it.id}"] = "P" }
                    update(classes.toMutableList().also { it[idx] = c.copy(attendance = map) })
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    classes: List<Classroom>,
    onOpen: (Int) -> Unit,
    onAdd: (Classroom) -> Unit,
    onDelete: (Int) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Evidencija prisustva") }) },
        floatingActionButton = { FloatingActionButton(onClick = { showAdd = true }) { Text("+") } }
    ) { pad ->
        if (classes.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
            Text("Dodaj prvi razred pomoću + dugmeta")
        } else LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp)) {
            itemsIndexed(classes) { index, c ->
                Card(Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onOpen(index) }) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(listOf(c.subject, c.year).filter { it.isNotBlank() }.joinToString(" • "))
                            Text("${c.students.size} učenika", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onDelete(index) }) { Text("Obriši") }
                    }
                }
            }
        }
    }
    if (showAdd) AddClassDialog(onDismiss = { showAdd = false }) { n, s, y ->
        onAdd(Classroom(n, s, y)); showAdd = false
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
    onOpenWeek: (Int) -> Unit,
    onAddStudent: (String) -> Unit,
    onDeleteStudent: (String) -> Unit
) {
    var addStudent by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(
        title = { Column { Text(classroom.name); Text(classroom.subject, style = MaterialTheme.typography.labelMedium) } },
        navigationIcon = { TextButton(onClick = onBack) { Text("‹ Nazad") } }
    ) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Učenici", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Button(onClick = { addStudent = true }) { Text("+ Učenik") }
                }
                Spacer(Modifier.height(8.dp))
            }
            itemsIndexed(classroom.students) { i, s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${s.name}", Modifier.weight(1f))
                    TextButton(onClick = { onDeleteStudent(s.id) }) { Text("Obriši") }
                }
            }
            item {
                Spacer(Modifier.height(16.dp))
                Text("Nastavni tjedni", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                (1..36).chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { w ->
                            OutlinedButton(onClick = { onOpenWeek(w) }, modifier = Modifier.weight(1f)) { Text(w.toString()) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
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
private fun WeekScreen(
    classroom: Classroom,
    week: Int,
    onBack: () -> Unit,
    onDate: (String) -> Unit,
    onStatus: (String, String) -> Unit,
    onAllPresent: () -> Unit
) {
    val formatter = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy") }
    var date by remember(week, classroom.dates[week]) { mutableStateOf(classroom.dates[week] ?: LocalDate.now().format(formatter)) }
    LaunchedEffect(week) { if (classroom.dates[week] == null) onDate(date) }

    Scaffold(topBar = { TopAppBar(
        title = { Text("${classroom.name} • Tjedan $week") },
        navigationIcon = { TextButton(onClick = onBack) { Text("‹ Nazad") } }
    ) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = date,
                onValueChange = { date = it; onDate(it) },
                label = { Text("Datum") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAllPresent, modifier = Modifier.fillMaxWidth()) { Text("Označi sve prisutne (+)") }
            Spacer(Modifier.height(8.dp))
            Text("+ prisutan   − odsutan   O opravdano   N neopravdano", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(classroom.students) { index, student ->
                    val status = classroom.attendance["$week|${student.id}"] ?: ""
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${index + 1}. ${student.name}", fontWeight = FontWeight.Medium)
                            Text(statusLabel(status), style = MaterialTheme.typography.labelSmall)
                        }
                        StatusButton("+", status == "P") { onStatus(student.id, "P") }
                        Spacer(Modifier.width(4.dp))
                        StatusButton("−", status == "A") { onStatus(student.id, "A") }
                        Spacer(Modifier.width(4.dp))
                        StatusButton("O", status == "O") { onStatus(student.id, "O") }
                        Spacer(Modifier.width(4.dp))
                        StatusButton("N", status == "N") { onStatus(student.id, "N") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun StatusButton(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(42.dp)) { Text(text) }
    else OutlinedButton(onClick = onClick, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(42.dp)) { Text(text) }
}

private fun statusLabel(status: String) = when (status) {
    "P" -> "Prisutan"
    "A" -> "Odsutan"
    "O" -> "Opravdano odsutan"
    "N" -> "Neopravdano odsutan"
    else -> "Nije evidentirano"
}
