package com.awd.teledrive.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.awd.teledrive.domain.model.DriveItem
import java.util.Calendar
import java.util.TimeZone

// Batch 7b smart search: filter state for the Home search UI. The enums
// live here (data layer) so DriveRepository.smartSearch can take them
// directly; HomeViewModel holds the StateFlows and HomeScreen renders
// the chips.

enum class SearchTypeFilter {
    ALL, IMAGES, VIDEOS, AUDIO, DOCUMENTS, OTHER
}

enum class SearchSizeFilter {
    ANY, UNDER_1_MB, FROM_1_TO_100_MB, OVER_100_MB
}

enum class SearchDateFilter {
    ANY, TODAY, THIS_WEEK, THIS_MONTH
}

enum class SearchScope {
    EVERYWHERE, CURRENT_FOLDER
}

data class SmartSearchFilters(
    val type: SearchTypeFilter = SearchTypeFilter.ALL,
    val size: SearchSizeFilter = SearchSizeFilter.ANY,
    val date: SearchDateFilter = SearchDateFilter.ANY,
    val scope: SearchScope = SearchScope.EVERYWHERE,
    val folderVirtualId: String = "0"
) {
    val isDefault: Boolean
        get() = type == SearchTypeFilter.ALL &&
            size == SearchSizeFilter.ANY &&
            date == SearchDateFilter.ANY &&
            scope == SearchScope.EVERYWHERE
}

/**
 * One smart-search hit. `matchedInContent` is true when the hit came from
 * file_content_fts (file text) rather than the file name — the Home list
 * shows a small "in file text" label for those rows.
 */
data class SmartSearchResult(
    val item: DriveItem,
    val matchedInContent: Boolean
)

// Type conditions reuse the mimeType CASE categories from
// DriveDao.getCloudFileTypeStats exactly, so the search filter counts
// match the storage-analysis categories.
private const val TYPE_IMAGE = "d.mimeType LIKE 'image/%'"
private const val TYPE_VIDEO = "d.mimeType LIKE 'video/%'"
private const val TYPE_AUDIO = "d.mimeType LIKE 'audio/%'"
private const val TYPE_DOCUMENT =
    "(d.mimeType LIKE 'application/pdf' " +
        "OR d.mimeType LIKE 'text/%' " +
        "OR d.mimeType LIKE '%msword%' " +
        "OR d.mimeType LIKE '%document%' " +
        "OR d.mimeType LIKE '%sheet%' " +
        "OR d.mimeType LIKE '%presentation%' " +
        "OR d.mimeType LIKE '%zip%' " +
        "OR d.mimeType LIKE '%rar%' " +
        "OR d.mimeType LIKE '%archive%' " +
        "OR d.mimeType LIKE '%7z%')"

private const val MB = 1024L * 1024L

/**
 * Sanitizes raw user input into a safe FTS4 MATCH query: split on
 * anything that is not a letter or digit, drop empty tokens, keep at
 * most 10 tokens, and make each a prefix term (`token*`) joined with
 * AND. Stripping every non-alphanumeric character means no FTS operator
 * (`"`, `*`, `OR`, `NEAR`, `(`, `)` ...) can ever reach SQLite, so the
 * result is safe to bind as a MATCH argument. Returns "" when the input
 * has no usable token (punctuation-only query).
 */
fun sanitizeFtsQuery(raw: String): String {
    return raw.split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotEmpty() }
        .take(10)
        .joinToString(" AND ") { "$it*" }
}

/** Start of the day `daysAgo` days ago, in the device timezone. */
private fun startOfDayMillis(daysAgo: Int): Long {
    val cal = Calendar.getInstance(TimeZone.getDefault())
    cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/**
 * Builds the single combined smart-search query for DriveDao.smartSearchRaw.
 * User input is NEVER concatenated into the SQL — every variable value is
 * a bound `?` arg (the sanitized FTS string, folder id, size/date bounds,
 * LIKE fallback text). The LIKE patterns in the type conditions are fixed
 * literals from the code, not user input.
 *
 * Shape: name hits come from drive_items_fts (trigger-synced from
 * drive_items), content hits from file_content_fts; matchedInContent is
 * 1 exactly for content hits. Empty query = no MATCH clause, plain
 * filtered list. A punctuation-only query (no FTS tokens) falls back to
 * the old `name LIKE` behaviour instead of erroring in MATCH.
 * Folders are included only while no file-only filter (type/size/date)
 * is active; scope applies to files and folders alike. Always excludes
 * split parts and the safe space, like searchGlobal. Folders first,
 * then newest first, capped at 200 rows.
 */
fun buildSmartSearchQuery(query: String, filters: SmartSearchFilters): SupportSQLiteQuery {
    val fts = sanitizeFtsQuery(query)

    // Args must be added in the exact order the `?` placeholders appear
    // in the final SQL: SELECT-list EXISTS first, then WHERE filters in
    // clause order, then the WHERE text-match placeholders last.
    val selectArgs = ArrayList<Any?>()
    val filterArgs = ArrayList<Any?>()
    val textArgs = ArrayList<Any?>()

    val matchedExpr: String
    val textWhere: String?
    if (fts.isNotEmpty()) {
        matchedExpr =
            "EXISTS(SELECT 1 FROM file_content_fts c " +
                "WHERE c.remoteUniqueId = d.remoteUniqueId AND c.contentText MATCH ?)"
        selectArgs += fts
        textWhere =
            "(d.rowid IN (SELECT docid FROM drive_items_fts " +
                "WHERE drive_items_fts MATCH ?) " +
                "OR d.remoteUniqueId IN (SELECT remoteUniqueId FROM file_content_fts " +
                "WHERE file_content_fts MATCH ?))"
        textArgs += fts
        textArgs += fts
    } else if (query.isNotBlank()) {
        matchedExpr = "0"
        textWhere = "d.name LIKE '%' || ? || '%'"
        textArgs += query.trim()
    } else {
        matchedExpr = "0"
        textWhere = null
    }

    val where = ArrayList<String>()
    where += "(d.splitGroupId IS NULL OR d.partIndex = 0)"
    where += "d.virtualParentId != 'vf_safe_space'"
    if (filters.scope == SearchScope.CURRENT_FOLDER) {
        where += "d.virtualParentId = ?"
        filterArgs += filters.folderVirtualId
    }
    val fileOnly = filters.type != SearchTypeFilter.ALL ||
        filters.size != SearchSizeFilter.ANY ||
        filters.date != SearchDateFilter.ANY
    if (fileOnly) {
        where += "d.isFolder = 0"
    }
    when (filters.type) {
        SearchTypeFilter.IMAGES -> where += TYPE_IMAGE
        SearchTypeFilter.VIDEOS -> where += TYPE_VIDEO
        SearchTypeFilter.AUDIO -> where += TYPE_AUDIO
        SearchTypeFilter.DOCUMENTS -> where += TYPE_DOCUMENT
        SearchTypeFilter.OTHER -> where +=
            "NOT ($TYPE_IMAGE OR $TYPE_VIDEO OR $TYPE_AUDIO OR $TYPE_DOCUMENT)"
        SearchTypeFilter.ALL -> Unit
    }
    when (filters.size) {
        SearchSizeFilter.UNDER_1_MB -> {
            where += "d.size < ?"
            filterArgs += MB
        }
        SearchSizeFilter.FROM_1_TO_100_MB -> {
            where += "d.size >= ? AND d.size <= ?"
            filterArgs += MB
            filterArgs += 100L * MB
        }
        SearchSizeFilter.OVER_100_MB -> {
            where += "d.size > ?"
            filterArgs += 100L * MB
        }
        SearchSizeFilter.ANY -> Unit
    }
    when (filters.date) {
        SearchDateFilter.TODAY -> {
            where += "d.createdAt >= ?"
            filterArgs += startOfDayMillis(0)
        }
        SearchDateFilter.THIS_WEEK -> {
            where += "d.createdAt >= ?"
            filterArgs += startOfDayMillis(6)
        }
        SearchDateFilter.THIS_MONTH -> {
            where += "d.createdAt >= ?"
            filterArgs += startOfDayMillis(29)
        }
        SearchDateFilter.ANY -> Unit
    }
    if (textWhere != null) {
        where += textWhere
    }

    val sql = StringBuilder()
        .append("SELECT d.*, ").append(matchedExpr).append(" AS matchedInContent ")
        .append("FROM drive_items d ")
        .append("WHERE ").append(where.joinToString(" AND "))
        .append(" ORDER BY d.isFolder DESC, d.createdAt DESC LIMIT 200")
        .toString()
    val args = ArrayList<Any?>(selectArgs.size + filterArgs.size + textArgs.size)
    args.addAll(selectArgs)
    args.addAll(filterArgs)
    args.addAll(textArgs)
    return SimpleSQLiteQuery(sql, args.toTypedArray())
}
