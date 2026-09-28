package com.reelgenerator.content

import com.reelgenerator.data.*
import org.json.JSONArray
import java.util.UUID

data class ImportSummary(val importId: String, val imported: Int, val duplicates: Int, val previouslyUsed: Int, val invalid: Int)

class ContentLibraryRepository(private val dao: ReelDao) {
    suspend fun importCsv(filename: String, csv: String): ImportSummary {
        val result = ContentCsvParser.parse(csv); val importId = UUID.randomUUID().toString()
        var inserted = 0; var duplicateExisting = 0; var previouslyUsed = 0
        val existingIdentities = dao.allContentItems().map { TextIdentity.normalize(it.rawText) }.toMutableSet()
        result.rows.forEach { row ->
            val identity = TextIdentity.normalize(row.text)
            if (dao.textUsed(identity)) { previouslyUsed++; return@forEach }
            if (!existingIdentities.add(identity)) { duplicateExisting++; return@forEach }
            if (dao.addContent(ContentLibraryItem(UUID.randomUUID().toString(), importId, "USER_CSV", row.category, row.subTheme, row.text, JSONArray(row.beats).toString(), row.tags.joinToString("|"), row.style, row.preferredClipCount, row.notes, ContentCsvParser.hash(row.text))) != -1L) inserted++
        }
        val duplicates = result.duplicates + duplicateExisting
        dao.addImport(ContentImport(importId, filename, System.currentTimeMillis(), result.rows.size + result.duplicates + result.invalid.size, inserted, duplicates + previouslyUsed, result.invalid.size))
        return ImportSummary(importId, inserted, duplicates, previouslyUsed, result.invalid.size)
    }
    suspend fun deleteImport(importId: String) { dao.deleteContentImport(importId); dao.deleteImport(importId) }
}
