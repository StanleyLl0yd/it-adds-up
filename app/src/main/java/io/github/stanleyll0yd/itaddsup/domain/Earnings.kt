package io.github.stanleyll0yd.itaddsup.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*
import java.time.temporal.ChronoUnit

/** All money is in minor units. No legal rates or tax assumptions are built in. */
enum class PayType {
    HOURLY,
    PER_SHIFT,
}

enum class ShiftStatus {
    PLANNED,
    CONFIRMED,
    ABSENT,
    CANCELLED,
}

data class PayRule(
    val effectiveFrom: LocalDate,
    val type: PayType,
    val rate: Long,
    val nightBasisPoints: Int = 0,
    val supplement: Long = 0,
) {
    init {
        require(rate >= 0 && supplement >= 0)
        require(nightBasisPoints in 0..100000)
    }
}

data class TimeSpan(val start: Instant, val end: Instant) {
    init {
        require(end > start)
    }

    val seconds: Long
        get() = Duration.between(start, end).seconds

    fun overlap(other: TimeSpan): Long =
        Duration.between(maxOf(start, other.start), minOf(end, other.end)).seconds.coerceAtLeast(0)
}

data class Shift(
    val date: LocalDate,
    val time: TimeSpan,
    val zone: ZoneId,
    val breaks: List<TimeSpan>,
    val rule: PayRule,
    val bonus: Long = 0,
    val adjustment: Long = 0,
    val status: ShiftStatus = ShiftStatus.CONFIRMED,
) {
    init {
        require(time.seconds <= 48 * 3600)
        require(time.start.atZone(zone).toLocalDate() == date)
        val sorted = breaks.sortedBy { it.start }
        require(sorted.all { it.start >= time.start && it.end <= time.end })
        require(sorted.zipWithNext().all { (a, b) -> a.end <= b.start })
        require(sorted.sumOf { it.seconds } < time.seconds)
        require(bonus >= 0)
    }
}

data class Receipt(
    val paidSeconds: Long,
    val nightSeconds: Long,
    val base: Long,
    val night: Long,
    val supplement: Long,
    val bonus: Long,
    val adjustment: Long,
) {
    val total: Long
        get() = listOf(base, night, supplement, bonus, adjustment).fold(0L, Math::addExact)
}

object Earnings {
    fun ruleAt(date: LocalDate, rules: List<PayRule>): PayRule? =
        rules.filter { it.effectiveFrom <= date }.maxByOrNull { it.effectiveFrom }

    fun calculate(shift: Shift): Receipt {
        if (shift.status != ShiftStatus.CONFIRMED) return Receipt(0, 0, 0, 0, 0, 0, 0)
        val paid = shift.time.seconds - shift.breaks.sumOf { it.seconds }
        var night = 0L
        var day = shift.time.start.atZone(shift.zone).toLocalDate().minusDays(1)
        val lastDay = shift.time.end.atZone(shift.zone).toLocalDate()
        while (day <= lastDay) {
            val window =
                TimeSpan(
                    day.atTime(22, 0).atZone(shift.zone).toInstant(),
                    day.plusDays(1).atTime(6, 0).atZone(shift.zone).toInstant(),
                )
            night += shift.time.overlap(window) - shift.breaks.sumOf { it.overlap(window) }
            day = day.plusDays(1)
        }
        val base =
            if (shift.rule.type == PayType.PER_SHIFT) shift.rule.rate
            else rounded(shift.rule.rate.toBigDecimal() * paid.toBigDecimal(), 3600.toBigDecimal())
        // For fixed shifts the configured premium uses the equivalent base hourly rate.
        val nightAmount =
            if (shift.rule.type == PayType.HOURLY) {
                rounded(
                    shift.rule.rate.toBigDecimal() *
                        night.toBigDecimal() *
                        shift.rule.nightBasisPoints.toBigDecimal(),
                    36000000.toBigDecimal(),
                )
            } else {
                rounded(
                    shift.rule.rate.toBigDecimal() *
                        night.toBigDecimal() *
                        shift.rule.nightBasisPoints.toBigDecimal(),
                    paid.toBigDecimal() * 10000.toBigDecimal(),
                )
            }
        return Receipt(
                paid,
                night,
                base,
                nightAmount,
                shift.rule.supplement,
                shift.bonus,
                shift.adjustment,
            )
            .also { it.total }
    }

    private fun rounded(numerator: BigDecimal, denominator: BigDecimal): Long =
        numerator.divide(denominator, 0, RoundingMode.HALF_UP).longValueExact()
}

data class Schedule(
    val anchor: LocalDate,
    val cycle: String,
    val start: LocalTime,
    val durationMinutes: Int,
    val zone: ZoneId,
    val weekdays: Boolean = false,
    val breakOffsetMinutes: Int = 240,
    val breakMinutes: Int = 0,
) {
    init {
        require(
            cycle.length in 1..60 && cycle.all { it == '1' || it == '0' } && cycle.contains('1')
        )
        require(durationMinutes in 1..1440)
        require(breakMinutes >= 0 && breakOffsetMinutes >= 0)
        require(breakMinutes == 0 || breakOffsetMinutes + breakMinutes < durationMinutes)
    }

    fun works(date: LocalDate): Boolean {
        if (date < anchor) return false
        if (weekdays) return date.dayOfWeek.value <= 5
        return cycle[(ChronoUnit.DAYS.between(anchor, date) % cycle.length).toInt()] == '1'
    }

    fun planned(date: LocalDate, rule: PayRule): Shift {
        val begin = date.atTime(start).atZone(zone).toInstant()
        // Duration is elapsed time, including on DST transitions.
        val finish = begin.plusSeconds(durationMinutes * 60L)
        val breaks =
            if (breakMinutes == 0) emptyList()
            else
                listOf(
                    TimeSpan(
                        begin.plusSeconds(breakOffsetMinutes * 60L),
                        begin.plusSeconds((breakOffsetMinutes + breakMinutes) * 60L),
                    )
                )
        return Shift(date, TimeSpan(begin, finish), zone, breaks, rule)
    }
}

data class Period(val start: LocalDate, val endExclusive: LocalDate) {
    init {
        require(endExclusive > start)
    }

    operator fun contains(date: LocalDate): Boolean = date >= start && date < endExclusive

    fun dates(): List<LocalDate> =
        (0 until ChronoUnit.DAYS.between(start, endExclusive)).map { start.plusDays(it) }

    companion object {
        fun containing(date: LocalDate, startDay: Int = 1): Period {
            require(startDay in 1..28)
            val candidate = date.withDayOfMonth(startDay)
            val start = if (date < candidate) candidate.minusMonths(1) else candidate
            return Period(start, start.plusMonths(1))
        }
    }
}

data class Payment(val date: LocalDate, val amount: Long) {
    init {
        require(amount >= 0)
    }
}

data class Reconciliation(
    val ownGross: Long,
    val employerGross: Long?,
    val deductions: Long?,
    val payments: List<Payment>,
) {
    init {
        require(employerGross == null || employerGross >= 0)
        require(deductions == null || deductions >= 0)
    }

    val paid: Long
        get() = payments.fold(0L) { total, payment -> Math.addExact(total, payment.amount) }

    val grossDifference: Long?
        get() = employerGross?.let { Math.subtractExact(it, ownGross) }

    val employerNet: Long?
        get() = employerGross?.let { gross -> deductions?.let { Math.subtractExact(gross, it) } }

    val paymentDifference: Long?
        get() = employerNet?.let { Math.subtractExact(paid, it) }

    fun explanations(): List<String> = buildList {
        if (employerGross == null)
            add("Добавьте начисления из расчётного листка для сравнения начислений.")
        if (deductions == null)
            add("Удержания неизвестны. Сравнивать начисления с поступлениями пока нельзя.")
        if (grossDifference != null && grossDifference != 0L)
            add("Проверьте часы, ставку, доплаты и границы расчётного периода.")
        if (paymentDifference != null && paymentDifference != 0L)
            add("Проверьте даты, аванс и остальные выплаты за этот период.")
        add("Разница сама по себе не доказывает нарушение или задолженность работодателя.")
    }
}

object Money {
    fun parse(text: String, allowNegative: Boolean = false): Long {
        val normalized = text.trim().replace(',', '.')
        require(
            Regex(if (allowNegative) "-?\\d{1,12}(\\.\\d{1,2})?" else "\\d{1,12}(\\.\\d{1,2})?")
                .matches(normalized)
        ) {
            "Введите сумму с точностью до копеек"
        }
        return normalized.toBigDecimal().movePointRight(2).longValueExact()
    }

    fun text(minor: Long): String =
        BigDecimal.valueOf(minor, 2).toPlainString().replace('.', ',') + " ₽"
}
