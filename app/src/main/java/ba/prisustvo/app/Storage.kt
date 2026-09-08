package ba.prisustvo.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AttendanceStore(context: Context) {
    private val prefs = context.getSharedPreferences("prisustvo_local", Context.MODE_PRIVATE)

    fun save(classes: List<Classroom>) {
        val root = JSONArray()
        classes.forEach { c ->
            val obj = JSONObject()
                .put("id", c.id)
                .put("name", c.name)
                .put("subject", c.subject)
                .put("year", c.year)

            val students = JSONArray()
            c.students.forEach {
                students.put(JSONObject().put("id", it.id).put("name", it.name).put("active", it.active))
            }
            obj.put("students", students)

            val sessions = JSONArray()
            c.sessions.forEach {
                sessions.put(
                    JSONObject()
                        .put("id", it.id)
                        .put("week", it.week)
                        .put("lesson", it.lesson)
                        .put("date", it.date)
                )
            }
            obj.put("sessions", sessions)

            val attendance = JSONObject()
            c.attendance.forEach { (key, entry) ->
                attendance.put(
                    key,
                    JSONObject().put("status", normalizeStatus(entry.status)).put("comment", entry.comment)
                )
            }
            obj.put("attendanceV2", attendance)
            root.put(obj)
        }
        prefs.edit().putString("data_v2", root.toString()).apply()
    }

    fun load(): List<Classroom> {
        val v2 = prefs.getString("data_v2", null)
        if (!v2.isNullOrBlank()) {
            val parsed = parseV2(v2)
            save(parsed)
            return parsed
        }
        val migrated = migrateLegacy(prefs.getString("data", "[]") ?: "[]")
        if (migrated.isNotEmpty()) save(migrated)
        return migrated
    }

    fun exportJson(classes: List<Classroom>): String {
        val before = prefs.getString("data_v2", null)
        save(classes)
        val text = prefs.getString("data_v2", "[]") ?: "[]"
        if (before == null && classes.isEmpty()) prefs.edit().remove("data_v2").apply()
        return text
    }

    private fun normalizeStatus(status: String): String = if (status == "R") STATUS_PRESENT else status

    private fun parseV2(text: String): List<Classroom> = try {
        val root = JSONArray(text)
        buildList {
            for (i in 0 until root.length()) {
                val obj = root.getJSONObject(i)
                val studentsJson = obj.optJSONArray("students") ?: JSONArray()
                val students = buildList {
                    for (s in 0 until studentsJson.length()) {
                        val st = studentsJson.getJSONObject(s)
                        add(Student(st.getString("id"), st.getString("name"), st.optBoolean("active", true)))
                    }
                }
                val sessionsJson = obj.optJSONArray("sessions") ?: JSONArray()
                val sessions = buildList {
                    for (s in 0 until sessionsJson.length()) {
                        val session = sessionsJson.getJSONObject(s)
                        add(
                            SessionRecord(
                                id = session.getString("id"),
                                week = session.getInt("week"),
                                lesson = session.optInt("lesson", 1),
                                date = session.optString("date")
                            )
                        )
                    }
                }
                val attendanceJson = obj.optJSONObject("attendanceV2") ?: JSONObject()
                val attendance = mutableMapOf<String, AttendanceEntry>()
                attendanceJson.keys().forEach { key ->
                    val entry = attendanceJson.optJSONObject(key)
                    if (entry != null) {
                        attendance[key] = AttendanceEntry(normalizeStatus(entry.optString("status")), entry.optString("comment"))
                    }
                }
                add(
                    Classroom(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = obj.optString("name"),
                        subject = obj.optString("subject"),
                        year = obj.optString("year"),
                        students = students,
                        sessions = sessions,
                        attendance = attendance
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun migrateLegacy(text: String): List<Classroom> = try {
        val root = JSONArray(text)
        buildList {
            for (i in 0 until root.length()) {
                val obj = root.getJSONObject(i)
                val studentsJson = obj.optJSONArray("students") ?: JSONArray()
                val students = buildList {
                    for (s in 0 until studentsJson.length()) {
                        val st = studentsJson.getJSONObject(s)
                        add(Student(st.getString("id"), st.getString("name"), true))
                    }
                }
                val datesObj = obj.optJSONObject("dates") ?: JSONObject()
                val sessions = mutableListOf<SessionRecord>()
                val weekSessionIds = mutableMapOf<Int, String>()
                datesObj.keys().forEach { key ->
                    val week = key.toIntOrNull() ?: return@forEach
                    val id = "legacy-week-$week"
                    weekSessionIds[week] = id
                    sessions += SessionRecord(id, week, 1, datesObj.optString(key))
                }

                val attendanceObj = obj.optJSONObject("attendance") ?: JSONObject()
                val attendance = mutableMapOf<String, AttendanceEntry>()
                attendanceObj.keys().forEach { oldKey ->
                    val parts = oldKey.split("|")
                    if (parts.size == 2) {
                        val week = parts[0].toIntOrNull() ?: return@forEach
                        val studentId = parts[1]
                        val sessionId = weekSessionIds.getOrPut(week) {
                            val id = "legacy-week-$week"
                            sessions += SessionRecord(id, week, 1, "")
                            id
                        }
                        attendance[sessionKey(sessionId, studentId)] = AttendanceEntry(normalizeStatus(attendanceObj.optString(oldKey)), "")
                    }
                }
                add(
                    Classroom(
                        id = UUID.randomUUID().toString(),
                        name = obj.optString("name"),
                        subject = obj.optString("subject"),
                        year = obj.optString("year"),
                        students = students,
                        sessions = sessions.distinctBy { it.id },
                        attendance = attendance
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}