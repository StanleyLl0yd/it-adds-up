package io.github.stanleyll0yd.itaddsup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.stanleyll0yd.itaddsup.data.*
import io.github.stanleyll0yd.itaddsup.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class TrackerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TrackerApplication
    private val repo = app.repository
    val ledger =
        repo.ledger
            .map<Ledger, Ledger?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val preferences =
        app.preferences.flow.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            Preferences(),
        )
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    private fun action(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                message.value =
                    "Не удалось выполнить действие. Проверьте данные и доступ к файлу. Исходные данные сохранены, если восстановление не завершилось."
            } finally {
                busy.value = false
            }
        }
    }

    fun setup(row: ScheduleRow, rule: PayRule) = action { repo.setup(row, rule) }

    fun saveRate(rule: PayRule) = action { repo.saveRate(rule) }

    fun confirm(shift: Shift) = action {
        repo.saveShift(shift)
        message.value = "Смена сохранена. Чек доступен в календаре."
    }

    fun addPayment(period: LocalDate, date: LocalDate, amount: Long) = action {
        repo.addPayment(period, date, amount)
    }

    fun deletePayment(id: Long) = action { repo.deletePayment(id) }

    fun saveStatement(row: StatementRow) = action { repo.statement(row) }

    fun preferences(value: Preferences) = action {
        app.preferences.save(value)
        Reminders.configure(app, value.reminders)
    }

    fun erase() = action {
        repo.erase()
        app.preferences.erase()
        Reminders.configure(app, false)
    }

    fun exportCsv(write: (ByteArray) -> Unit) = action {
        write(Archive.csv(requireNotNull(ledger.value)))
        message.value = "CSV сохранён. Файл содержит финансовые данные без шифрования."
    }

    fun backup(password: CharArray, write: (ByteArray) -> Unit) = action {
        try {
            write(EncryptedBackup.encrypt(Archive.encode(requireNotNull(ledger.value)), password))
            message.value = "Зашифрованная копия сохранена. Без пароля восстановление невозможно."
        } finally {
            password.fill('\u0000')
        }
    }

    fun restore(password: CharArray, read: () -> ByteArray) = action {
        try {
            val decoded = Archive.decode(EncryptedBackup.decrypt(read(), password))
            repo.restore(decoded)
            message.value = "Копия восстановлена. Текущие данные заменены."
        } finally {
            password.fill('\u0000')
        }
    }
}
