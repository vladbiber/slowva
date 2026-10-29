package com.slowva.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface DiagnosticsDao {

    @Query("SELECT * FROM diagnostic_issues WHERE fingerprint = :fingerprint")
    suspend fun issue(fingerprint: String): DiagnosticIssueEntity?

    @Query("SELECT * FROM diagnostic_issues ORDER BY lastSeen DESC")
    suspend fun issues(): List<DiagnosticIssueEntity>

    @Upsert
    suspend fun upsertIssue(issue: DiagnosticIssueEntity)

    @Insert
    suspend fun insertEvent(event: DiagnosticEventEntity)

    @Query("SELECT * FROM diagnostic_events WHERE fingerprint = :fingerprint ORDER BY at DESC LIMIT :limit")
    suspend fun events(fingerprint: String, limit: Int = 50): List<DiagnosticEventEntity>

    @Query("SELECT COUNT(DISTINCT item) FROM diagnostic_events WHERE fingerprint = :fingerprint AND item IS NOT NULL")
    suspend fun distinctItems(fingerprint: String): Int

    @Query("DELETE FROM diagnostic_events WHERE id NOT IN (SELECT id FROM diagnostic_events ORDER BY at DESC LIMIT :keep)")
    suspend fun trimEvents(keep: Int)

    /** Stores the event and folds it into its issue. */
    @Transaction
    suspend fun record(event: DiagnosticEventEntity, template: DiagnosticIssueEntity, keepEvents: Int) {
        insertEvent(event)
        val old = issue(event.fingerprint)
        upsertIssue(
            (old ?: template).copy(
                count = (old?.count ?: 0) + 1,
                distinctItems = distinctItems(event.fingerprint),
                firstSeen = old?.firstSeen ?: event.at,
                lastSeen = event.at,
                verdict = template.verdict,
                resolved = false,
            )
        )
        trimEvents(keepEvents)
    }
}
