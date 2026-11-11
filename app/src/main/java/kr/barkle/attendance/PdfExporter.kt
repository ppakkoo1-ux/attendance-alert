package kr.barkle.attendance

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth

/**
 * 나이스 '월별 출결 현황' 형식 PDF (A4 세로)
 * 일자 | 번호 | 성명 | 출결구분 | 결시교시 | 사유
 */
object PdfExporter {
    private const val W = 595
    private const val H = 842
    private const val ML = 42f
    private const val MR = 42f
    private const val TOP = 40f
    private const val BOTTOM = 70f
    private const val LINE = 12f

    private fun dot(d: LocalDate) = "%04d.%02d.%02d".format(d.year, d.monthValue, d.dayOfMonth)

    fun periodLabel(start: LocalDate, end: LocalDate): String {
        val ym = YearMonth.from(start)
        return if (start.dayOfMonth == 1 && YearMonth.from(end) == ym &&
            (end == ym.atEndOfMonth() || end == LocalDate.now())
        ) "%04d.%02d".format(ym.year, ym.monthValue)
        else "${dot(start)} ~ ${dot(end)}"
    }

    /** 칸 너비에 맞춰 줄바꿈 (쉼표 뒤에서 우선 끊음) */
    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            if (paint.measureText(rest) <= width) {
                lines.add(rest)
                break
            }
            var n = paint.breakText(rest, true, width, null).coerceAtLeast(1)
            val comma = rest.lastIndexOf(',', n - 1)
            if (comma in 1 until n) n = comma + 1
            lines.add(rest.substring(0, n))
            rest = rest.substring(n)
        }
        return lines
    }

    fun export(
        c: Context,
        start: LocalDate,
        end: LocalDate,
        recs: List<Record>,
        smap: Map<Long, Student>,
    ): Uri {
        val doc = PdfDocument()
        val black = Color.BLACK
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17f; isFakeBoldText = true; color = black; textAlign = Paint.Align.CENTER }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = black }
        val cellC = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; color = black; textAlign = Paint.Align.CENTER }
        val cellL = Paint(cellC).apply { textAlign = Paint.Align.LEFT }
        val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.6f; color = black }

        val school = Prefs.schoolName(c)
        val grade = Prefs.grade(c)
        val classNo = Prefs.classNo(c)
        val classTitle = listOf(
            if (grade.isNotBlank()) "${grade}학년" else "",
            if (classNo.isNotBlank()) "${classNo}반" else "",
        ).filter { it.isNotEmpty() }.joinToString(" ")
        val today = dot(LocalDate.now()) + "."
        val periodText = "기간 :  " + periodLabel(start, end)

        // 열: 일자, 번호, 성명, 출결구분, 결시교시, 사유
        val tableW = W - ML - MR
        val widths = floatArrayOf(78f, 36f, 62f, 70f, 142f, 0f)
        widths[5] = tableW - widths.take(5).sum()
        val heads = listOf("일자", "번호", "성명", "출결구분", "결시교시", "사유")
        val leftCols = setOf(2, 4, 5)

        // 정렬: 일자, 번호
        val rows = recs.sortedWith(compareBy<Record>({ it.date }, { smap[it.studentId]?.num ?: 0 }))
            .map { r ->
                val s = smap[r.studentId]
                listOf(
                    dot(LocalDate.parse(r.date)),
                    s?.num?.toString() ?: "",
                    s?.name ?: "",
                    r.label,
                    r.periods,
                    r.memo,
                )
            }

        // 줄바꿈을 계산해 쪽 나누기
        data class Laid(val cells: List<List<String>>, val h: Float)
        val laid = rows.map { row ->
            val cells = row.mapIndexed { i, v -> wrap(v, cellL, widths[i] - 8f) }
            val lines = cells.maxOf { it.size }
            Laid(cells, maxOf(30f, lines * LINE + 14f))
        }
        val headH = 24f
        val tableTop = TOP + 78f
        val avail = H - BOTTOM - tableTop - headH
        val pages = mutableListOf<MutableList<Laid>>(mutableListOf())
        var used = 0f
        for (l in laid) {
            if (used + l.h > avail && pages.last().isNotEmpty()) {
                pages.add(mutableListOf())
                used = 0f
            }
            pages.last().add(l)
            used += l.h
        }
        val total = pages.size

        fun drawRow(cv: Canvas, y: Float, h: Float, cells: List<List<String>>, head: Boolean) {
            var x = ML
            for (i in cells.indices) {
                val w = widths[i]
                cv.drawRect(x, y, x + w, y + h, border)
                val lines = cells[i]
                val left = !head && i in leftCols
                val p = if (left) cellL else cellC
                val tx = if (left) x + 4f else x + w / 2
                val blockH = lines.size * LINE
                var ty = y + (h - blockH) / 2 + LINE - 3f
                for (ln in lines) {
                    cv.drawText(ln, tx, ty, p)
                    ty += LINE
                }
                x += w
            }
        }

        pages.forEachIndexed { pi, list ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pi + 1).create())
            val cv = page.canvas
            cv.drawText(today, W - MR - small.measureText(today), TOP, small)
            cv.drawText("월별 출결 현황", W / 2f, TOP + 36f, title)
            cv.drawText("$classTitle 월별 출결 현황".trim(), ML, TOP + 68f, small)
            cv.drawText(periodText, W - MR - small.measureText(periodText), TOP + 68f, small)

            var y = tableTop
            drawRow(cv, y, headH, heads.map { listOf(it) }, true)
            y += headH
            if (list.isEmpty()) {
                cv.drawRect(ML, y, ML + tableW, y + 30f, border)
                cv.drawText("기록 없음", ML + tableW / 2, y + 19f, cellC)
            }
            for (l in list) {
                drawRow(cv, y, l.h, l.cells, false)
                y += l.h
            }

            val pageText = "${pi + 1} / $total"
            cv.drawText(pageText, W / 2f - small.measureText(pageText) / 2, H - 40f, small)
            if (school.isNotBlank()) cv.drawText(school, W - MR - small.measureText(school), H - 40f, small)
            doc.finishPage(page)
        }

        val name = "월별출결현황_${periodLabel(start, end).replace(" ", "")}.pdf"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/출결알림")
        }
        val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("파일을 만들 수 없습니다")
        try {
            val out = c.contentResolver.openOutputStream(uri) ?: throw IOException("파일을 열 수 없습니다")
            out.use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return uri
    }
}
