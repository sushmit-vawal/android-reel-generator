package com.reelgenerator.content

import com.reelgenerator.data.*
import org.json.JSONArray
import java.util.UUID

data class ImportSummary(val importId: String, val imported: Int, val duplicates: Int, val invalid: Int)

class ContentLibraryRepository(private val dao: ReelDao) {
    suspend fun importCsv(filename: String, csv: String): ImportSummary {
        val result = ContentCsvParser.parse(csv); val importId = UUID.randomUUID().toString()
        result.rows.forEach { row ->
            dao.addContent(ContentLibraryItem(UUID.randomUUID().toString(), importId, "USER_CSV", row.category, row.subTheme, row.text, JSONArray(row.beats).toString(), row.tags.joinToString("|"), row.style, row.preferredClipCount, row.notes, ContentCsvParser.hash(row.text)))
        }
        dao.addImport(ContentImport(importId, filename, System.currentTimeMillis(), result.rows.size + result.duplicates + result.invalid.size, result.rows.size, result.duplicates, result.invalid.size))
        return ImportSummary(importId, result.rows.size, result.duplicates, result.invalid.size)
    }
    suspend fun deleteImport(importId: String) { dao.deleteContentImport(importId); dao.deleteImport(importId) }
}
