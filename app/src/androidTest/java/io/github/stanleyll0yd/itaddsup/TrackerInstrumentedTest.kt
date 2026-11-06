package io.github.stanleyll0yd.itaddsup

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stanleyll0yd.itaddsup.data.*
import io.github.stanleyll0yd.itaddsup.domain.*
import java.io.File
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class TrackerInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app: TrackerApplication
        get() = ApplicationProvider.getApplicationContext()

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun clear() = runBlocking {
        app.repository.erase()
        app.preferences.erase()
        Reminders.configure(app, false)
    }

    @After
    fun close() {
        scenario?.close()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun await(text: String) {
        compose.waitUntil(15000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun seed(): Shift = runBlocking {
        val date = LocalDate.now(ZoneId.of("Europe/Moscow")).minusDays(2)
        val row =
            ScheduleRow(
                anchor = date.toString(),
                cycle = "11110000",
                start = "08:00",
                duration = 480,
                zone = "Europe/Moscow",
                weekdays = false,
                breakOffset = 240,
                breakMinutes = 30,
            )
        val rule = PayRule(date, PayType.HOURLY, 30000)
        app.repository.setup(row, rule)
        row.domain().planned(date, rule).also { app.repository.saveShift(it) }
    }

    @Test
    fun onboardingPersistsAndOpensThreeTabs() {
        launch()
        await("Показать мой график")
        compose.onNodeWithText("Ставка · ₽/час").performScrollTo().performTextInput("300")
        compose.onNodeWithText("Показать мой график").performScrollTo().performClick()
        await("Сегодня")
        compose.onNodeWithText("Подтверждено за период").assertExists()
        compose.onNodeWithText("Смены", useUnmergedTree = false).performClick()
        await("Редактировать смену")
        compose.onNodeWithText("Деньги", useUnmergedTree = false).performClick()
        await("Свой расчёт · подтверждено до удержаний")
        runBlocking { assertNotNull(app.repository.ledger.first().schedule) }
        scenario?.recreate()
        await("Сегодня")
        compose.onNodeWithText("Показать мой график").assertDoesNotExist()
    }

    @Test
    fun encryptedBackupRestoresRoomAndAuditAtomically() = runBlocking {
        val original = seed()
        app.repository.addPayment(original.date.withDayOfMonth(1), original.date, 12345)
        app.repository.saveShift(original.copy(adjustment = -100))
        val before = app.repository.ledger.first()
        val encoded =
            EncryptedBackup.encrypt(Archive.encode(before), "safe-test-password".toCharArray())
        app.repository.erase()
        app.repository.restore(
            Archive.decode(EncryptedBackup.decrypt(encoded, "safe-test-password".toCharArray()))
        )
        val after = app.repository.ledger.first()
        assertEquals(before.schedule, after.schedule)
        assertEquals(before.rates, after.rates)
        assertEquals(before.shifts, after.shifts)
        assertEquals(before.payments.map { it.amount }, after.payments.map { it.amount })
        assertEquals(2, after.audits.size)
        assertEquals(-100L, after.shifts.single().domain().adjustment)
        assertThrows(Exception::class.java) {
            EncryptedBackup.decrypt(encoded, "wrong-password".toCharArray())
        }
        assertEquals(after, app.repository.ledger.first())
    }

    @Test
    fun screenshotsOfRealApplication() {
        seed()
        runBlocking { app.preferences.save(Preferences(theme = "light", dynamic = false)) }
        launch()
        await("Подтверждено за период")
        screenshot("today")
        compose.onNodeWithText("Смены", useUnmergedTree = false).performClick()
        await("Редактировать смену")
        screenshot("shifts")
        compose.onNodeWithText("Деньги", useUnmergedTree = false).performClick()
        await("Свой расчёт · подтверждено до удержаний")
        screenshot("money")
        compose.onNodeWithText("Настройки").performClick()
        await("Тёмная")
        compose.onNodeWithText("Тёмная").performClick()
        compose.onNodeWithText("Назад").performScrollTo().performClick()
        await("Сегодня")
        screenshot("dark")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(app.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
