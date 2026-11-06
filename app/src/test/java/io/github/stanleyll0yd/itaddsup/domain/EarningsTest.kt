package io.github.stanleyll0yd.itaddsup.domain

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class EarningsTest {
    private val date = LocalDate.of(2026, 11, 6)
    private val zone = ZoneId.of("Europe/Moscow")
    private val hourly = PayRule(date, PayType.HOURLY, 30000, 2000)

    private fun shift(start: String = "20:00", hours: Int = 12, rule: PayRule = hourly): Shift =
        Schedule(date, "1100", LocalTime.parse(start), hours * 60, zone).planned(date, rule)

    @Test
    fun midnightAndNightPremium() {
        val receipt = Earnings.calculate(shift())
        assertEquals(12 * 3600L, receipt.paidSeconds)
        assertEquals(8 * 3600L, receipt.nightSeconds)
        assertEquals(360000L, receipt.base)
        assertEquals(48000L, receipt.night)
        assertEquals(408000L, receipt.total)
    }

    @Test
    fun nightBreakIsExcludedFromBothTotals() {
        val s = shift()
        val b = TimeSpan(s.time.start.plusSeconds(4 * 3600), s.time.start.plusSeconds(5 * 3600))
        val receipt = Earnings.calculate(s.copy(breaks = listOf(b)))
        assertEquals(11 * 3600L, receipt.paidSeconds)
        assertEquals(7 * 3600L, receipt.nightSeconds)
        assertEquals(372000L, receipt.total)
    }

    @Test
    fun historicalEditKeepsSnapshotAfterRateChanges() {
        val original = shift(rule = hourly.copy(nightBasisPoints = 0))
        val newRule = hourly.copy(effectiveFrom = date.plusDays(1), rate = 50000)
        assertEquals(hourly, Earnings.ruleAt(date, listOf(hourly, newRule)))
        assertEquals(newRule, Earnings.ruleAt(date.plusDays(1), listOf(hourly, newRule)))
        val edited =
            original.copy(
                time = TimeSpan(original.time.start, original.time.start.plusSeconds(3600))
            )
        assertEquals(30000L, Earnings.calculate(edited).total)
        assertEquals(360000L, Earnings.calculate(original).total)
    }

    @Test
    fun fixedRateAndNegativeAdjustment() {
        val s =
            shift(rule = hourly.copy(type = PayType.PER_SHIFT, rate = 250001, nightBasisPoints = 0))
        assertEquals(240001L, Earnings.calculate(s.copy(adjustment = -10000)).total)
    }

    @Test
    fun roundsEachLineHalfUpToMinorUnit() {
        val s = shift(rule = hourly.copy(rate = 1, nightBasisPoints = 0))
        assertEquals(
            1L,
            Earnings.calculate(
                    s.copy(time = TimeSpan(s.time.start, s.time.start.plusSeconds(1800)))
                )
                .base,
        )
    }

    @Test
    fun scheduleShiftAndWeekdays() {
        val schedule = Schedule(date, "1100", LocalTime.NOON, 480, zone)
        assertTrue(schedule.works(date))
        assertFalse(schedule.works(date.plusDays(2)))
        assertTrue(schedule.copy(anchor = date.plusDays(2)).works(date.plusDays(2)))
        assertFalse(schedule.works(date.minusDays(1)))
        assertFalse(schedule.copy(weekdays = true).works(LocalDate.of(2026, 11, 7)))
    }

    @Test
    fun cancelledAndAbsenceDoNotAccrue() {
        for (status in listOf(ShiftStatus.ABSENT, ShiftStatus.CANCELLED)) assertEquals(
            0L,
            Earnings.calculate(shift().copy(status = status)).total,
        )
    }

    @Test
    fun multiplePaymentsCompareOnlyNetToNet() {
        val r =
            Reconciliation(
                100000,
                110000,
                10000,
                listOf(Payment(date, 40000), Payment(date, 50000)),
            )
        assertEquals(10000L, r.grossDifference)
        assertEquals(100000L, r.employerNet)
        assertEquals(-10000L, r.paymentDifference)
        assertNull(r.copy(deductions = null).paymentDifference)
        assertNull(r.copy(employerGross = null).employerNet)
    }

    @Test
    fun periodBoundaries() {
        val p = Period.containing(date, 10)
        assertEquals(LocalDate.of(2026, 10, 10), p.start)
        assertTrue(date in p)
        assertFalse(p.endExclusive in p)
        assertEquals(p.endExclusive, Period.containing(p.endExclusive, 10).start)
    }

    @Test
    fun dstUsesElapsedTimeAndLocalNightWindows() {
        val berlin = ZoneId.of("Europe/Berlin")
        fun night(start: String, end: String): Receipt {
            val a = ZonedDateTime.parse(start).withZoneSameInstant(berlin)
            val b = ZonedDateTime.parse(end).withZoneSameInstant(berlin)
            return Earnings.calculate(
                Shift(
                    a.toLocalDate(),
                    TimeSpan(a.toInstant(), b.toInstant()),
                    berlin,
                    emptyList(),
                    hourly,
                )
            )
        }
        val spring = night("2026-03-28T22:00+01:00", "2026-03-29T06:00+02:00")
        assertEquals(7 * 3600L, spring.nightSeconds)
        val autumn = night("2026-10-24T22:00+02:00", "2026-10-25T06:00+01:00")
        assertEquals(9 * 3600L, autumn.nightSeconds)
    }

    @Test
    fun incompleteRulesAndInvalidInputs() {
        assertNull(Earnings.ruleAt(date.minusDays(1), listOf(hourly)))
        assertThrows(IllegalArgumentException::class.java) { Money.parse("1.005") }
        assertThrows(IllegalArgumentException::class.java) { Money.parse("-1") }
        assertThrows(IllegalArgumentException::class.java) {
            Schedule(date, "000", LocalTime.NOON, 60, zone)
        }
        assertEquals(-125L, Money.parse("-1,25", true))
    }

    @Test
    fun overlappingBreaksRejected() {
        val s = shift()
        val b = TimeSpan(s.time.start.plusSeconds(60), s.time.start.plusSeconds(120))
        assertThrows(IllegalArgumentException::class.java) { s.copy(breaks = listOf(b, b)) }
    }
}
