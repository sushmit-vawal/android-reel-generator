package com.reelgenerator.content

import java.security.MessageDigest

data class ParsedContentRow(
    val category: String,
    val subTheme: String,
    val text: String,
    val beats: List<String>,
    val tags: List<String> = emptyList(),
    val preferredClipCount: Int? = null,
    val style: String? = null,
    val notes: String? = null,
    val rowNumber: Int
)

data class CsvImportResult(val rows: List<ParsedContentRow>, val duplicates: Int, val invalid: List<String>)

object ContentCsvParser {
    fun parse(input: String): CsvImportResult {
        val records = records(input)
        if (records.isEmpty()) return CsvImportResult(emptyList(), 0, emptyList())
        val header = records.first().map { it.trim().lowercase() }
        val textIndex = header.indexOf("text").takeIf { it >= 0 } ?: if (header.size == 1) 0 else -1
        if (textIndex < 0) return CsvImportResult(emptyList(), 0, listOf("Missing text column."))
        val seen = mutableSetOf<String>(); val valid = mutableListOf<ParsedContentRow>(); val invalid = mutableListOf<String>(); var duplicates = 0
        records.drop(1).forEachIndexed { offset, record ->
            val row = offset + 2; val text = record.getOrNull(textIndex)?.trim().orEmpty()
            if (text.isBlank() || text.length > 10_000) { invalid += "Row $row has no usable text or is too large."; return@forEachIndexed }
            val normalized = normalize(text); if (!seen.add(normalized)) { duplicates++; return@forEachIndexed }
            val category = field(record, header, "category").ifBlank { "Uncategorized" }
            val beats = text.split("||").map(String::trim).filter(String::isNotEmpty)
            valid += ParsedContentRow(category, field(record, header, "subtheme"), text, beats,
                field(record, header, "tags").split('|').map(String::trim).filter(String::isNotEmpty),
                field(record, header, "preferred_clip_count").toIntOrNull()?.takeIf { it in 1..5 },
                field(record, header, "style").ifBlank { null }, field(record, header, "notes").ifBlank { null }, row)
        }
        return CsvImportResult(valid, duplicates, invalid)
    }

    fun normalize(text: String) = text.lowercase().replace(Regex("\\s+"), " ").trim()
    fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(normalize(text).toByteArray()).joinToString("") { "%02x".format(it) }

    private fun field(record: List<String>, header: List<String>, name: String) = record.getOrNull(header.indexOf(name).takeIf { it >= 0 } ?: -1)?.trim().orEmpty()
    private fun records(input: String): List<List<String>> {
        val out = mutableListOf<List<String>>(); val row = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        fun finishCell() { row += cell.toString(); cell.setLength(0) }
        fun finishRow() { finishCell(); if (row.any { it.isNotBlank() }) out += row.toList(); row.clear() }
        while (i < input.length) { val c = input[i]; when {
            c == '"' && quoted && i + 1 < input.length && input[i + 1] == '"' -> { cell.append('"'); i++ }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> finishCell()
            (c == '\n' || c == '\r') && !quoted -> { if (c == '\r' && i + 1 < input.length && input[i + 1] == '\n') i++; finishRow() }
            else -> cell.append(c)
        }; i++ }
        if (cell.isNotEmpty() || row.isNotEmpty()) finishRow(); return out
    }
}
