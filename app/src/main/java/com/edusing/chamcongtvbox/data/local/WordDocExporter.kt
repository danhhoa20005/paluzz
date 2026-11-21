package com.edusing.chamcongtvbox.data.local

import android.content.Context
import android.os.Environment
import com.edusing.chamcongtvbox.domain.model.ClassGroup
import com.edusing.chamcongtvbox.domain.model.Member
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Xuất bảng danh sách dạng Word (`.doc` HTML mở thẳng trên MS Word / WPS)
 * chia theo từng Lớp/Phòng ban, kèm cột trống để tick gọi chích vân tay / khuôn mặt.
 */
class WordDocExporter(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun export(
        title: String,
        orgName: String,
        groupLabel: String,
        personLabel: String,
        classes: List<ClassGroup>,
        members: List<Member>,
        abbreviatedNames: Map<Int, String>,
    ): Result<String> = withContext(ioDispatcher) {
        runCatching {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir,
                "exports",
            ).apply { mkdirs() }
            val outFile = File(dir, "DanhSach_ChichVanTay_$timestamp.doc")

            val grouped = members.groupBy { it.classId }
            val orderedGroups = classes.mapNotNull { c ->
                grouped[c.id]?.takeIf { it.isNotEmpty() }?.let { c.name to it }
            } + grouped.filterKeys { cid -> classes.none { it.id == cid } }
                .values.flatten().takeIf { it.isNotEmpty() }
                ?.let { listOf("Chưa xếp ${groupLabel.lowercase()}" to it) }
                .orEmpty()

            val sb = StringBuilder(4096)
            sb.append(
                """
                <!DOCTYPE html>
                <html xmlns:o='urn:schemas-microsoft-com:office:office' xmlns:w='urn:schemas-microsoft-com:office:word'>
                <head><meta charset='UTF-8'><style>
                  body{font-family:'Times New Roman',serif;font-size:12pt;}
                  h1{text-align:center;font-size:15pt;margin:0;}
                  p.sub{text-align:center;font-weight:bold;margin:4px 0 14px;}
                  h2{font-size:13pt;margin:14px 0 4px;color:#1D4ED8;}
                  table{border-collapse:collapse;width:100%;margin-bottom:14px;}
                  th,td{border:1px solid #000;padding:5px 7px;text-align:left;}
                  th{background:#E5E7EB;font-weight:bold;}
                </style></head><body>
                """.trimIndent()
            )
            sb.append("<h1>").append(escapeHtml(orgName.ifBlank { title })).append("</h1>")
            sb.append("<p class='sub'>").append(escapeHtml(title)).append("</p>")

            for ((groupName, groupMembers) in orderedGroups) {
                sb.append("<h2>").append(escapeHtml("$groupName (${groupMembers.size} ${personLabel.lowercase()})")).append("</h2>")
                sb.append("<table><thead><tr>")
                sb.append("<th style='width:42px'>STT</th>")
                sb.append("<th style='width:110px'>Mã thẻ</th>")
                sb.append("<th>Họ và tên</th>")
                sb.append("<th style='width:95px'>Tên trên máy</th>")
                sb.append("<th style='width:155px'>Đã chích vân tay / mặt</th>")
                sb.append("</tr></thead><tbody>")

                groupMembers.forEachIndexed { idx, m ->
                    val shortName = m.cardIdInt?.let { abbreviatedNames[it] }.orEmpty()
                    sb.append("<tr>")
                    sb.append("<td>").append(idx + 1).append("</td>")
                    sb.append("<td>").append(escapeHtml(m.cardId.ifEmpty { m.rawCard })).append("</td>")
                    sb.append("<td>").append(escapeHtml(m.name)).append("</td>")
                    sb.append("<td><b>").append(escapeHtml(shortName)).append("</b></td>")
                    sb.append("<td></td></tr>")
                }
                sb.append("</tbody></table>")
            }

            sb.append("</body></html>")
            // Ghi kèm UTF-8 BOM để MS Word mở tiếng Việt không bị lỗi font
            outFile.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sb.toString().toByteArray(Charsets.UTF_8))
            outFile.absolutePath
        }
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
