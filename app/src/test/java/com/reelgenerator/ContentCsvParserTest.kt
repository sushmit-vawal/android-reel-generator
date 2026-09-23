package com.reelgenerator

import com.reelgenerator.content.ContentCsvParser
import org.junit.Assert.*
import org.junit.Test

class ContentCsvParserTest {
    @Test fun quotedCommasAndMultiBeatRowsParse() {
        val result = ContentCsvParser.parse("category,subtheme,text\nTravel,freedom,\"Work, save, go. || Then breathe.\"")
        assertEquals(1, result.rows.size); assertEquals(2, result.rows.single().beats.size)
        assertEquals("Work, save, go. || Then breathe.", result.rows.single().text)
    }
    @Test fun textOnlyUnicodeUnknownCategoryAndDuplicates() {
        val result = ContentCsvParser.parse("text\n\"Café ☕\"\n\" Café   ☕ \"")
        assertEquals("Uncategorized", result.rows.single().category); assertEquals(1, result.duplicates)
    }
    @Test fun malformedAndBlankRowsAreReported() {
        val result = ContentCsvParser.parse("category,text\nTravel,\nTravel,valid")
        assertEquals(1, result.rows.size); assertEquals(1, result.invalid.size)
    }
}
