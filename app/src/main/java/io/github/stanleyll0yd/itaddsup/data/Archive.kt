package io.github.stanleyll0yd.itaddsup.data

import io.github.stanleyll0yd.itaddsup.domain.*
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

object Archive {
    fun encode(ledger: Ledger): ByteArray =
        JSONObject()
            .apply {
                put("version", 1)
                put(
                    "schedule",
                    ledger.schedule?.let { s ->
                        JSONObject().apply {
                            put("anchor", s.anchor)
                            put("cycle", s.cycle)
                            put("start", s.start)
                            put("duration", s.duration)
                            put("zone", s.zone)
                            put("weekdays", s.weekdays)
                            put("breakOffset", s.breakOffset)
                            put("breakMinutes", s.breakMinutes)
                            put("periodDay", s.periodDay)
                            put("payday", s.payday)
                        }
                    } ?: JSONObject.NULL,
                )
                put(
                    "rates",
                    JSONArray().apply {
                        ledger.rates.forEach { r ->
                            put(
                                JSONObject().apply {
                                    put("effective", r.effective)
                                    put("type", r.type)
                                    put("rate", r.rate)
                                    put("nightBps", r.nightBps)
                                    put("supplement", r.supplement)
                                }
                            )
                        }
                    },
                )
                put(
                    "shifts",
                    JSONArray().apply { ledger.shifts.forEach { put(JSONObject(it.payload)) } },
                )
                put(
                    "payments",
                    JSONArray().apply {
                        ledger.payments.forEach { r ->
                            put(
                                JSONObject().apply {
                                    put("period", r.period)
                                    put("date", r.date)
                                    put("amount", r.amount)
                                }
                            )
                        }
                    },
                )
                put(
                    "statements",
                    JSONArray().apply {
                        ledger.statements.forEach { r ->
                            put(
                                JSONObject().apply {
                                    put("period", r.period)
                                    put("gross", r.gross ?: JSONObject.NULL)
                                    put("deductions", r.deductions ?: JSONObject.NULL)
                                }
                            )
                        }
                    },
                )
                put(
                    "audit",
                    JSONArray().apply {
                        ledger.audits.forEach { r ->
                            put(
                                JSONObject().apply {
                                    put("date", r.date)
                                    put("before", r.before ?: JSONObject.NULL)
                                    put("after", r.after)
                                    put("changedAt", r.changedAt)
                                }
                            )
                        }
                    },
                )
            }
            .toString()
            .toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): Ledger {
        require(bytes.size <= EncryptedBackup.MAX_BYTES)
        val j = JSONObject(bytes.toString(Charsets.UTF_8))
        require(j.getInt("version") == 1) { "Несовместимая версия данных" }
        val schedule =
            if (j.isNull("schedule")) null
            else
                j.getJSONObject("schedule").let { s ->
                    ScheduleRow(
                            anchor = s.getString("anchor"),
                            cycle = s.getString("cycle"),
                            start = s.getString("start"),
                            duration = s.getInt("duration"),
                            zone = s.getString("zone"),
                            weekdays = s.getBoolean("weekdays"),
                            breakOffset = s.getInt("breakOffset"),
                            breakMinutes = s.getInt("breakMinutes"),
                            periodDay = s.getInt("periodDay"),
                            payday = s.getInt("payday"),
                        )
                        .also {
                            it.domain()
                            require(it.periodDay in 1..28 && it.payday in 1..28)
                        }
                }
        val rates =
            j.rows("rates").map { r ->
                RateRow(
                        r.getString("effective"),
                        r.getString("type"),
                        r.getLong("rate"),
                        r.getInt("nightBps"),
                        r.getLong("supplement"),
                    )
                    .also { it.domain() }
            }
        val shifts =
            j.rows("shifts").map { s ->
                ShiftJson.decode(s.toString()).let {
                    ShiftRow(it.date.toString(), ShiftJson.encode(it))
                }
            }
        val payments =
            j.rows("payments").map { r ->
                PaymentRow(
                        period = r.date("period"),
                        date = r.date("date"),
                        amount = r.getLong("amount"),
                    )
                    .also { require(it.amount > 0) }
            }
        val statements =
            j.rows("statements").map { r ->
                StatementRow(
                        r.date("period"),
                        r.nullableLong("gross"),
                        r.nullableLong("deductions"),
                    )
                    .also {
                        require(it.gross == null || it.gross >= 0)
                        require(it.deductions == null || it.deductions >= 0)
                    }
            }
        val audits =
            j.rows("audit").map { r ->
                AuditRow(
                        date = r.date("date"),
                        before = if (r.isNull("before")) null else r.getString("before"),
                        after = r.getString("after"),
                        changedAt = r.getLong("changedAt"),
                    )
                    .also { a ->
                        require(ShiftJson.decode(a.after).date.toString() == a.date)
                        a.before?.let { require(ShiftJson.decode(it).date.toString() == a.date) }
                        require(a.changedAt >= 0)
                    }
            }
        require(rates.map { it.effective }.distinct().size == rates.size)
        require(shifts.map { it.date }.distinct().size == shifts.size)
        require(statements.map { it.period }.distinct().size == statements.size)
        require(
            schedule != null ||
                (rates.isEmpty() &&
                    shifts.isEmpty() &&
                    payments.isEmpty() &&
                    statements.isEmpty() &&
                    audits.isEmpty())
        )
        require(schedule == null || rates.isNotEmpty())
        return Ledger(schedule, rates, shifts, payments, statements, audits)
    }

    private fun JSONObject.rows(key: String): List<JSONObject> {
        val array = getJSONArray(key)
        require(array.length() <= 20000)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun JSONObject.date(key: String): String = getString(key).also { LocalDate.parse(it) }

    private fun JSONObject.nullableLong(key: String): Long? =
        if (isNull(key)) null else getLong(key)

    fun csv(ledger: Ledger): ByteArray =
        buildString {
                append(
                    "\uFEFFтип,дата,статус,оплачено_секунд,ночь_секунд,начислено_копеек,выплачено_копеек,период\r\n"
                )
                ledger.shifts.forEach { row ->
                    val s = row.domain()
                    val r = Earnings.calculate(s)
                    append(
                        "смена,${s.date},${s.status},${r.paidSeconds},${r.nightSeconds},${r.total},,\r\n"
                    )
                }
                ledger.payments.forEach {
                    append("выплата,${it.date},,,,,${it.amount},${it.period}\r\n")
                }
            }
            .toByteArray(Charsets.UTF_8)
}
