package ba.prisustvo.app

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object ExportUtils {
    private fun safeName(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun createClassPdf(context: Context, classroom: Classroom): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "izvjestaj_${safeName(classroom.name)}.pdf")
        val doc = PdfDocument()
        val paint = Paint().apply { textSize = 10f }
        val bold = Paint().apply { textSize = 12f; isFakeBoldText = true }
        val title = Paint().apply { textSize = 17f; isFakeBoldText = true }
        val pageWidth = 842
        val pageHeight = 595
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo).create())
        var canvas = page.canvas
        var y = 35f

        fun header() {
            canvas.drawText("Izvještaj prisustva – ${classroom.name}", 30f, y, title); y += 22
            canvas.drawText("${classroom.subject}  •  ${classroom.year}", 30f, y, paint); y += 24
            canvas.drawText("Učenik", 30f, y, bold)
            canvas.drawText("Evid.", 330f, y, bold)
            canvas.drawText("Pris.", 385f, y, bold)
            canvas.drawText("Izost.", 440f, y, bold)
            canvas.drawText("Oprav.", 500f, y, bold)
            canvas.drawText("Neopr.", 565f, y, bold)
            canvas.drawText("Kasnio", 630f, y, bold)
            canvas.drawText("%", 700f, y, bold)
            y += 16
        }

        header()
        classroom.students.sortedBy { surnameKey(it.name) }.forEachIndexed { index, student ->
            if (y > pageHeight - 30) {
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo).create())
                canvas = page.canvas
                y = 35f
                header()
            }
            val s = classroom.statsFor(student.id)
            val label = "${index + 1}. ${student.name}${if (!student.active) " (neakt.)" else ""}"
            canvas.drawText(label.take(46), 30f, y, paint)
            canvas.drawText(s.recorded.toString(), 335f, y, paint)
            canvas.drawText(s.attended.toString(), 390f, y, paint)
            canvas.drawText(s.totalAbsences.toString(), 445f, y, paint)
            canvas.drawText(s.excused.toString(), 505f, y, paint)
            canvas.drawText(s.unexcused.toString(), 570f, y, paint)
            canvas.drawText(s.late.toString(), 635f, y, paint)
            canvas.drawText("${s.attendancePercent}%", 700f, y, paint)
            if (s.warning) canvas.drawText("!", 760f, y, bold)
            y += 15
        }
        doc.finishPage(page)
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    fun createStudentPdf(context: Context, classroom: Classroom, student: Student): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "ucenik_${safeName(student.name)}.pdf")
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = page.canvas
        val paint = Paint().apply { textSize = 10f }
        val bold = Paint().apply { textSize = 12f; isFakeBoldText = true }
        val title = Paint().apply { textSize = 17f; isFakeBoldText = true }
        var y = 40f
        val stats = classroom.statsFor(student.id)
        canvas.drawText(student.name, 30f, y, title); y += 22
        canvas.drawText("${classroom.name} • ${classroom.subject} • ${classroom.year}", 30f, y, paint); y += 18
        if (!student.active) { canvas.drawText("Status učenika: NEAKTIVAN", 30f, y, bold); y += 18 }
        canvas.drawText("Prisustvo: ${stats.attendancePercent}%  |  Izostanci: ${stats.totalAbsences}  |  Neopravdani: ${stats.unexcused}", 30f, y, bold); y += 25
        classroom.sessions.sortedWith(compareBy<SessionRecord> { it.week }.thenBy { it.lesson }).forEach { session ->
            val entry = classroom.attendance[sessionKey(session.id, student.id)] ?: AttendanceEntry()
            if (entry.status.isBlank()) return@forEach
            if (y > 800f) return@forEach
            val line = "Tjedan ${session.week}, čas ${session.lesson}  ${session.date}  – ${statusLabel(entry.status)}"
            canvas.drawText(line, 30f, y, paint); y += 15
            if (entry.comment.isNotBlank()) {
                canvas.drawText("Komentar: ${entry.comment.take(85)}", 45f, y, paint); y += 15
            }
        }
        doc.finishPage(page)
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    fun createCsv(context: Context, classroom: Classroom): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "izvjestaj_${safeName(classroom.name)}.csv")
        file.bufferedWriter().use { out ->
            out.appendLine("Ucenik,Aktivan,Evidentirano,Prisutan,Izostanci,Opravdano,Neopravdano,Kasnio,Prisustvo %")
            classroom.students.sortedBy { surnameKey(it.name) }.forEach { student ->
                val s = classroom.statsFor(student.id)
                out.appendLine(listOf(student.name, if (student.active) "DA" else "NE", s.recorded, s.attended, s.totalAbsences, s.excused, s.unexcused, s.late, s.attendancePercent).joinToString(",") { csvEscape(it.toString()) })
            }
        }
        return file
    }

    fun createBackup(context: Context, json: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))
        return File(dir, "prisustvo_backup_$stamp.json").apply { writeText(json) }
    }

    private fun csvEscape(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    fun shareFile(context: Context, file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }
}