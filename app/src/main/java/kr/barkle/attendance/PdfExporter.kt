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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 통계 PDF (A4 가로): 1) 학생별 구분·사유 집계표  2) 기록 상세 */
object PdfExporter {
    private const val W = 842
    private const val H = 595
    private const val M = 30f

    fun export(
        c: Context,
        start: LocalDate,
        end: LocalDate,
        rows: List<Stats.Row>,
        recs: List<Record>,
        smap: Map<Long, Student>,
    ): Uri {
        val doc = PdfDocument()
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; isFakeBoldText = true; color = Color.BLACK }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = Color.DKGRAY }
        val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = Color.BLACK; textAlign = Paint.Align.CENTER }
        val boldPaint = Paint(cellPaint).apply { isFakeBoldText = true }
        val leftPaint = Paint(cellPaint).apply { textAlign = Paint.Align.LEFT }
        val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.6f; color = Color.GRAY }
        val headFill = Paint().apply { style = Paint.Style.FILL; color = Color.rgb(235, 240, 237) }

        val created = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        var pageNo = 0
        var page: PdfDocument.Page? = null
        lateinit var cv: Canvas
        var y = 0f

        fun newPage(subtitle: String) {
            page?.let { doc.finishPage(it) }
            pageNo++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            page = p
            cv = p.canvas
            cv.drawText("출결 통계  $start ~ $end", M, M + 12f, titlePaint)
            val info = "생성 $created · ${pageNo}쪽"
            cv.drawText(info, W - M - smallPaint.measureText(info), M + 12f, smallPaint)
            cv.drawText(subtitle, M, M + 30f, boldPaint.apply { textAlign = Paint.Align.LEFT })
            boldPaint.textAlign = Paint.Align.CENTER
            y = M + 40f
        }

        fun cell(x: Float, w: Float, top: Float, h: Float, s: String, paint: Paint = cellPaint, fill: Boolean = false) {
            if (fill) cv.drawRect(x, top, x + w, top + h, headFill)
            cv.drawRect(x, top, x + w, top + h, border)
            var t = s
            if (paint.measureText(t) > w - 4) {
                val n = paint.breakText(t, true, w - 10, null)
                t = t.take(n) + "…"
            }
            val tx = if (paint.textAlign == Paint.Align.CENTER) x + w / 2 else x + 3
            cv.drawText(t, tx, top + h / 2 + 3.2f, paint)
        }

        // ---------- 1. 집계표 ----------
        val numW = 30f
        val nameW = 72f
        val colW = 38f
        val totW = 40f
        val rowH = 15f
        val nR = Att.REASONS.size

        fun header() {
            var x = M
            cell(x, numW, y, rowH * 2, "번호", boldPaint, true); x += numW
            cell(x, nameW, y, rowH * 2, "이름", boldPaint, true); x += nameW
            for (cat in Att.CATEGORIES) {
                cell(x, colW * nR, y, rowH, cat, boldPaint, true)
                for ((i, r) in Att.REASONS.withIndex()) {
                    cell(x + colW * i, colW, y + rowH, rowH, r, boldPaint, true)
                }
                x += colW * nR
            }
            cell(x, totW, y, rowH * 2, "합계", boldPaint, true)
            y += rowH * 2
        }

        fun dataRow(num: String, name: String, counts: IntArray, bold: Boolean) {
            val p = if (bold) boldPaint else cellPaint
            var x = M
            cell(x, numW, y, rowH, num, p, bold); x += numW
            cell(x, nameW, y, rowH, name, p, bold); x += nameW
            for (n in counts) {
                cell(x, colW, y, rowH, if (n == 0) "" else n.toString(), p, bold); x += colW
            }
            cell(x, totW, y, rowH, counts.sum().toString(), p, bold)
            y += rowH
        }

        newPage("학생별 출결 집계")
        header()
        val total = IntArray(Att.CATEGORIES.size * nR)
        for (r in rows) {
            if (y + rowH > H - M) { newPage("학생별 출결 집계 (계속)"); header() }
            val tag = if (r.student.active) "" else "*"
            dataRow(r.student.num.toString(), r.student.name + tag, r.counts, false)
            r.counts.forEachIndexed { i, n -> total[i] += n }
        }
        if (y + rowH > H - M) { newPage("학생별 출결 집계 (계속)"); header() }
        dataRow("", "합계", total, true)
        if (rows.any { !it.student.active }) {
            y += 12f
            cv.drawText("* 현재 명단에서 빠진 학생", M, y, smallPaint)
        }

        // ---------- 2. 기록 상세 ----------
        val cols = listOf("날짜" to 80f, "번호" to 35f, "이름" to 72f, "구분" to 72f, "서류" to 50f)
        val memoW = W - 2 * M - cols.sumOf { it.second.toDouble() }.toFloat()

        fun detailHeader() {
            var x = M
            for ((t, w) in cols) { cell(x, w, y, rowH, t, boldPaint, true); x += w }
            cell(x, memoW, y, rowH, "메모", boldPaint, true)
            y += rowH
        }

        newPage("출결 기록 상세 (${recs.size}건)")
        detailHeader()
        if (recs.isEmpty()) {
            cell(M, W - 2 * M, y, rowH, "기록 없음")
            y += rowH
        }
        for (r in recs) {
            if (y + rowH > H - M) { newPage("출결 기록 상세 (계속)"); detailHeader() }
            val s = smap[r.studentId]
            val values = listOf(
                "${r.date.substring(0, 4)}/${Att.short(r.date)}",
                (s?.num ?: 0).toString(),
                s?.name ?: "?",
                r.label,
                if (r.docSubmitted) "제출" else "미제출",
            )
            var x = M
            values.forEachIndexed { i, v -> cell(x, cols[i].second, y, rowH, v); x += cols[i].second }
            cell(x, memoW, y, rowH, r.memo, leftPaint)
            y += rowH
        }

        page?.let { doc.finishPage(it) }

        val name = "출결통계_${start}_${end}.pdf"
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
