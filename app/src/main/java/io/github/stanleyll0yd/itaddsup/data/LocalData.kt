package io.github.stanleyll0yd.itaddsup.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import io.github.stanleyll0yd.itaddsup.domain.*
import java.time.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "schedule")
data class ScheduleRow(
    @PrimaryKey val id: Int = 1,
    val anchor: String,
    val cycle: String,
    val start: String,
    val duration: Int,
    val zone: String,
    val weekdays: Boolean,
    val breakOffset: Int,
    val breakMinutes: Int,
    val periodDay: Int = 1,
    val payday: Int = 10,
) {
    fun domain() =
        Schedule(
            LocalDate.parse(anchor),
            cycle,
            LocalTime.parse(start),
            duration,
            ZoneId.of(zone),
            weekdays,
            breakOffset,
            breakMinutes,
        )
}

@Entity(tableName = "rates")
data class RateRow(
    @PrimaryKey val effective: String,
    val type: String,
    val rate: Long,
    val nightBps: Int,
    val supplement: Long,
) {
    fun domain() =
        PayRule(LocalDate.parse(effective), PayType.valueOf(type), rate, nightBps, supplement)

    companion object {
        fun of(rule: PayRule) =
            RateRow(
                rule.effectiveFrom.toString(),
                rule.type.name,
                rule.rate,
                rule.nightBasisPoints,
                rule.supplement,
            )
    }
}

@Entity(tableName = "shifts")
data class ShiftRow(@PrimaryKey val date: String, val payload: String) {
    fun domain() = ShiftJson.decode(payload).also { require(it.date.toString() == date) }
}

@Entity(tableName = "audit")
data class AuditRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val before: String?,
    val after: String,
    val changedAt: Long,
)

@Entity(tableName = "payments")
data class PaymentRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val period: String,
    val date: String,
    val amount: Long,
)

@Entity(tableName = "statements")
data class StatementRow(@PrimaryKey val period: String, val gross: Long?, val deductions: Long?)

@Dao
interface TrackerDao {
    @Query("SELECT * FROM schedule LIMIT 1") fun schedule(): Flow<ScheduleRow?>

    @Query("SELECT * FROM rates ORDER BY effective") fun rates(): Flow<List<RateRow>>

    @Query("SELECT * FROM shifts ORDER BY date") fun shifts(): Flow<List<ShiftRow>>

    @Query("SELECT * FROM payments ORDER BY date, id") fun payments(): Flow<List<PaymentRow>>

    @Query("SELECT * FROM statements ORDER BY period") fun statements(): Flow<List<StatementRow>>

    @Query("SELECT * FROM audit ORDER BY changedAt, id") fun audits(): Flow<List<AuditRow>>

    @Query("SELECT * FROM shifts WHERE date = :date") suspend fun shift(date: String): ShiftRow?

    @Upsert suspend fun save(row: ScheduleRow)

    @Upsert suspend fun save(row: RateRow)

    @Upsert suspend fun save(row: ShiftRow)

    @Upsert suspend fun save(row: StatementRow)

    @Insert suspend fun add(row: PaymentRow)

    @Insert suspend fun add(row: AuditRow)

    @Query("DELETE FROM payments WHERE id = :id") suspend fun deletePayment(id: Long)

    @Query("DELETE FROM schedule") suspend fun clearSchedule()

    @Query("DELETE FROM rates") suspend fun clearRates()

    @Query("DELETE FROM shifts") suspend fun clearShifts()

    @Query("DELETE FROM payments") suspend fun clearPayments()

    @Query("DELETE FROM statements") suspend fun clearStatements()

    @Query("DELETE FROM audit") suspend fun clearAudit()
}

@Database(
    entities =
        [
            ScheduleRow::class,
            RateRow::class,
            ShiftRow::class,
            AuditRow::class,
            PaymentRow::class,
            StatementRow::class,
        ],
    version = 1,
    exportSchema = true,
)
abstract class TrackerDatabase : RoomDatabase() {
    abstract fun dao(): TrackerDao
}

object ShiftJson {
    fun encode(s: Shift): String =
        JSONObject()
            .apply {
                put("date", s.date.toString())
                put("start", s.time.start.toString())
                put("end", s.time.end.toString())
                put("zone", s.zone.id)
                put(
                    "breaks",
                    JSONArray().apply {
                        s.breaks.forEach {
                            put(JSONArray(listOf(it.start.toString(), it.end.toString())))
                        }
                    },
                )
                put("effective", s.rule.effectiveFrom.toString())
                put("type", s.rule.type.name)
                put("rate", s.rule.rate)
                put("nightBps", s.rule.nightBasisPoints)
                put("supplement", s.rule.supplement)
                put("bonus", s.bonus)
                put("adjustment", s.adjustment)
                put("status", s.status.name)
            }
            .toString()

    fun decode(text: String): Shift =
        JSONObject(text).let { j ->
            val breaks = j.getJSONArray("breaks")
            require(breaks.length() <= 32)
            Shift(
                    LocalDate.parse(j.getString("date")),
                    TimeSpan(
                        Instant.parse(j.getString("start")),
                        Instant.parse(j.getString("end")),
                    ),
                    ZoneId.of(j.getString("zone")),
                    (0 until breaks.length()).map { i ->
                        breaks.getJSONArray(i).let {
                            TimeSpan(Instant.parse(it.getString(0)), Instant.parse(it.getString(1)))
                        }
                    },
                    PayRule(
                        LocalDate.parse(j.getString("effective")),
                        PayType.valueOf(j.getString("type")),
                        j.getLong("rate"),
                        j.getInt("nightBps"),
                        j.getLong("supplement"),
                    ),
                    j.getLong("bonus"),
                    j.getLong("adjustment"),
                    ShiftStatus.valueOf(j.getString("status")),
                )
                .also { Earnings.calculate(it) }
        }
}

data class Ledger(
    val schedule: ScheduleRow? = null,
    val rates: List<RateRow> = emptyList(),
    val shifts: List<ShiftRow> = emptyList(),
    val payments: List<PaymentRow> = emptyList(),
    val statements: List<StatementRow> = emptyList(),
    val audits: List<AuditRow> = emptyList(),
)

class TrackerRepository(val db: TrackerDatabase) {
    private val dao = db.dao()
    val ledger: Flow<Ledger> =
        combine(dao.schedule(), dao.rates(), dao.shifts(), dao.payments(), dao.statements()) {
                schedule,
                rates,
                shifts,
                payments,
                statements ->
                Ledger(schedule, rates, shifts, payments, statements)
            }
            .combine(dao.audits()) { ledger, audits -> ledger.copy(audits = audits) }

    suspend fun setup(schedule: ScheduleRow, rate: PayRule) {
        require(schedule.periodDay in 1..28 && schedule.payday in 1..28)
        schedule.domain()
        db.withTransaction {
            dao.save(schedule)
            dao.save(RateRow.of(rate))
        }
    }

    suspend fun saveRate(rate: PayRule) = dao.save(RateRow.of(rate))

    suspend fun saveShift(shift: Shift) {
        Earnings.calculate(shift)
        val row = ShiftRow(shift.date.toString(), ShiftJson.encode(shift))
        db.withTransaction {
            val old = dao.shift(row.date)
            dao.add(
                AuditRow(
                    date = row.date,
                    before = old?.payload,
                    after = row.payload,
                    changedAt = System.currentTimeMillis(),
                )
            )
            dao.save(row)
        }
    }

    suspend fun addPayment(period: LocalDate, date: LocalDate, amount: Long) {
        require(amount > 0)
        dao.add(PaymentRow(period = period.toString(), date = date.toString(), amount = amount))
    }

    suspend fun deletePayment(id: Long) = dao.deletePayment(id)

    suspend fun statement(row: StatementRow) {
        LocalDate.parse(row.period)
        require(row.gross == null || row.gross >= 0)
        require(row.deductions == null || row.deductions >= 0)
        dao.save(row)
    }

    suspend fun erase() =
        db.withTransaction {
            dao.clearAudit()
            dao.clearShifts()
            dao.clearPayments()
            dao.clearStatements()
            dao.clearRates()
            dao.clearSchedule()
        }

    suspend fun restore(snapshot: Ledger) =
        db.withTransaction {
            erase()
            snapshot.schedule?.let { dao.save(it) }
            snapshot.rates.forEach { dao.save(it) }
            snapshot.shifts.forEach { dao.save(it) }
            snapshot.payments.forEach { dao.add(it.copy(id = 0)) }
            snapshot.statements.forEach { dao.save(it) }
            snapshot.audits.forEach { dao.add(it.copy(id = 0)) }
        }
}

private val Context.preferences by preferencesDataStore("preferences")

data class Preferences(
    val theme: String = "system",
    val dynamic: Boolean = true,
    val reminders: Boolean = false,
    val adConsent: Boolean = false,
)

class PreferencesRepository(private val context: Context) {
    private val theme = stringPreferencesKey("theme")
    private val dynamic = booleanPreferencesKey("dynamic")
    private val reminders = booleanPreferencesKey("reminders")
    private val adConsent = booleanPreferencesKey("ad_consent_v1")
    private val notified = stringSetPreferencesKey("notified")
    val flow: Flow<Preferences> =
        context.preferences.data.map {
            Preferences(
                it[theme] ?: "system",
                it[dynamic] ?: true,
                it[reminders] ?: false,
                it[adConsent] ?: false,
            )
        }

    suspend fun save(value: Preferences) {
        context.preferences.edit {
            it[theme] = value.theme
            it[dynamic] = value.dynamic
            it[reminders] = value.reminders
            it[adConsent] = value.adConsent
        }
    }

    suspend fun wasNotified(date: String): Boolean =
        context.preferences.data.first()[notified]?.contains(date) == true

    suspend fun markNotified(date: String) {
        context.preferences.edit {
            it[notified] = ((it[notified] ?: emptySet()) + date).sorted().takeLast(32).toSet()
        }
    }

    suspend fun erase() {
        context.preferences.edit { it.clear() }
    }
}
