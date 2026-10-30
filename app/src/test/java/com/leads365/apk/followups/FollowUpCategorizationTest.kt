package com.leads365.apk.followups

import com.leads365.apk.database.entity.FollowUpEntity
import com.leads365.apk.ui.followups.FollowUpsViewModel
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class FollowUpCategorizationTest {

    private fun createFollowUp(
        id: String,
        scheduledAtEpochMs: Long,
        status: String = FollowUpEntity.STATUS_PENDING
    ): FollowUpEntity {
        return FollowUpEntity(
            id = id,
            leadName = "Test Contact $id",
            leadPhone = "+919876543210",
            title = "Follow-up $id",
            scheduledAtEpochMs = scheduledAtEpochMs,
            status = status
        )
    }

    @Test
    fun testMutuallyExclusiveCategories_earlierToday_isOverdue_notToday() {
        // Set fixed "now" at 2:00 PM today in IST
        val tz = TimeZone.getTimeZone("Asia/Kolkata")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.OCTOBER, 30, 14, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis

        // Scheduled earlier today at 11:29 AM (Issue 1 & 3 scenario)
        val earlierToday = Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 11)
            set(Calendar.MINUTE, 29)
        }.timeInMillis

        val item = createFollowUp("earlier_1", earlierToday)
        val (today, upcoming, overdue) = FollowUpsViewModel.categorize(listOf(item), now, tz)

        assertEquals("Earlier today must NOT appear in today list", 0, today.size)
        assertEquals("Earlier today must NOT appear in upcoming list", 0, upcoming.size)
        assertEquals("Earlier today MUST appear in overdue list", 1, overdue.size)
        assertEquals("earlier_1", overdue[0].id)

        // isOverdue helper must be consistent
        assertTrue(FollowUpsViewModel.isOverdue(item, now))
    }

    @Test
    fun testMutuallyExclusiveCategories_exactlyCurrentTime_isToday_notOverdue() {
        val tz = TimeZone.getTimeZone("Asia/Kolkata")
        val now = 1700000000000L

        val exactItem = createFollowUp("exact_1", now)
        val (today, upcoming, overdue) = FollowUpsViewModel.categorize(listOf(exactItem), now, tz)

        assertEquals("Exactly current time must appear in today list", 1, today.size)
        assertEquals("Exactly current time must NOT appear in overdue list", 0, overdue.size)
        assertEquals("Exactly current time must NOT appear in upcoming list", 0, upcoming.size)

        assertFalse(FollowUpsViewModel.isOverdue(exactItem, now))
    }

    @Test
    fun testMutuallyExclusiveCategories_laterToday_isToday_notOverdue() {
        val tz = TimeZone.getTimeZone("Asia/Kolkata")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.OCTOBER, 30, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis

        // Scheduled for 4:00 PM today
        val laterToday = Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 16)
        }.timeInMillis

        val item = createFollowUp("later_1", laterToday)
        val (today, upcoming, overdue) = FollowUpsViewModel.categorize(listOf(item), now, tz)

        assertEquals("Later today must appear in today list", 1, today.size)
        assertEquals("Later today must NOT appear in overdue list", 0, overdue.size)
        assertEquals("Later today must NOT appear in upcoming list", 0, upcoming.size)

        assertFalse(FollowUpsViewModel.isOverdue(item, now))
    }

    @Test
    fun testMutuallyExclusiveCategories_tomorrow_isUpcoming() {
        val tz = TimeZone.getTimeZone("Asia/Kolkata")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.OCTOBER, 30, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis

        // Scheduled for tomorrow 11:00 AM
        val tomorrow = Calendar.getInstance(tz).apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 11)
        }.timeInMillis

        val item = createFollowUp("tomorrow_1", tomorrow)
        val (today, upcoming, overdue) = FollowUpsViewModel.categorize(listOf(item), now, tz)

        assertEquals("Tomorrow must NOT appear in today list", 0, today.size)
        assertEquals("Tomorrow must NOT appear in overdue list", 0, overdue.size)
        assertEquals("Tomorrow MUST appear in upcoming list", 1, upcoming.size)

        assertFalse(FollowUpsViewModel.isOverdue(item, now))
    }

    @Test
    fun testMutuallyExclusiveCategories_completedAndCancelled_excludedFromAll() {
        val tz = TimeZone.getTimeZone("Asia/Kolkata")
        val now = System.currentTimeMillis()

        val completedEarlier = createFollowUp("comp_1", now - 3600_000L, FollowUpEntity.STATUS_COMPLETED)
        val completedLater = createFollowUp("comp_2", now + 3600_000L, FollowUpEntity.STATUS_COMPLETED)
        val cancelledItem = createFollowUp("canc_1", now - 3600_000L, "CANCELLED")

        val list = listOf(completedEarlier, completedLater, cancelledItem)
        val (today, upcoming, overdue) = FollowUpsViewModel.categorize(list, now, tz)

        assertEquals(0, today.size)
        assertEquals(0, upcoming.size)
        assertEquals(0, overdue.size)

        assertFalse(FollowUpsViewModel.isOverdue(completedEarlier, now))
        assertFalse(FollowUpsViewModel.isOverdue(completedLater, now))
        assertFalse(FollowUpsViewModel.isOverdue(cancelledItem, now))
    }

    @Test
    fun testMutuallyExclusiveCategories_partitionIntegrity_eachItemInExactlyOneTab() {
        val tz = TimeZone.getTimeZone("Asia/Kolkata")
        val cal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.OCTOBER, 30, 13, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis

        val yesterday = now - (24 * 3600_000L)
        val earlierToday = Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 9)
        }.timeInMillis
        val exactlyNow = now
        val laterToday = Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 17)
        }.timeInMillis
        val tomorrow = now + (24 * 3600_000L)

        val items = listOf(
            createFollowUp("yesterday", yesterday),
            createFollowUp("earlier_today", earlierToday),
            createFollowUp("exact_now", exactlyNow),
            createFollowUp("later_today", laterToday),
            createFollowUp("tomorrow", tomorrow)
        )

        val (today, upcoming, overdue) = FollowUpsViewModel.categorize(items, now, tz)

        // Total pending items = 5
        assertEquals(2, overdue.size) // yesterday + earlier_today
        assertEquals(2, today.size)   // exact_now + later_today
        assertEquals(1, upcoming.size) // tomorrow

        // Check mutual exclusivity: no overlap
        val overdueIds = overdue.map { it.id }.toSet()
        val todayIds = today.map { it.id }.toSet()
        val upcomingIds = upcoming.map { it.id }.toSet()

        assertTrue(overdueIds.intersect(todayIds).isEmpty())
        assertTrue(todayIds.intersect(upcomingIds).isEmpty())
        assertTrue(overdueIds.intersect(upcomingIds).isEmpty())

        assertEquals(5, overdueIds.size + todayIds.size + upcomingIds.size)
    }

    @Test
    fun testMidnightAndLocalTimezoneBoundaries() {
        // Test UTC, US/Eastern, Asia/Kolkata
        val timezones = listOf(
            TimeZone.getTimeZone("UTC"),
            TimeZone.getTimeZone("America/New_York"),
            TimeZone.getTimeZone("Asia/Kolkata")
        )

        for (tz in timezones) {
            val cal = Calendar.getInstance(tz).apply {
                set(2026, Calendar.OCTOBER, 30, 23, 59, 50)
                set(Calendar.MILLISECOND, 0)
            }
            val nowJustBeforeMidnight = cal.timeInMillis

            val (startOfToday, endOfToday) = FollowUpsViewModel.getDayBounds(nowJustBeforeMidnight, tz)

            // Start of today must be 00:00:00.000 in this timezone
            val calStart = Calendar.getInstance(tz).apply { timeInMillis = startOfToday }
            assertEquals("Start of today hour must be 0", 0, calStart.get(Calendar.HOUR_OF_DAY))
            assertEquals("Start of today minute must be 0", 0, calStart.get(Calendar.MINUTE))
            assertEquals("Start of today second must be 0", 0, calStart.get(Calendar.SECOND))

            // End of today must be 23:59:59.999 in this timezone
            val calEnd = Calendar.getInstance(tz).apply { timeInMillis = endOfToday }
            assertEquals("End of today hour must be 23", 23, calEnd.get(Calendar.HOUR_OF_DAY))
            assertEquals("End of today minute must be 59", 59, calEnd.get(Calendar.MINUTE))
            assertEquals("End of today second must be 59", 59, calEnd.get(Calendar.SECOND))
            assertEquals("End of today ms must be 999", 999, calEnd.get(Calendar.MILLISECOND))

            // Reminder 5 seconds before midnight
            val beforeMidnight = endOfToday - 5000L
            // Reminder 5 seconds after midnight (next day)
            val afterMidnight = endOfToday + 5001L

            val item1 = createFollowUp("pre_midnight", beforeMidnight)
            val item2 = createFollowUp("post_midnight", afterMidnight)

            val (today, upcoming, overdue) = FollowUpsViewModel.categorize(
                listOf(item1, item2),
                nowJustBeforeMidnight,
                tz
            )

            assertTrue("Pre-midnight reminder must be in today list", today.any { it.id == "pre_midnight" })
            assertTrue("Post-midnight reminder must be in upcoming list", upcoming.any { it.id == "post_midnight" })
            assertEquals(0, overdue.size)
        }
    }
}
