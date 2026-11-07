package io.github.stanleyll0yd.itaddsup

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
    fun confirmEditAndReconcileThroughUi() {
        val original = seed()
        runBlocking { app.repository.saveShift(original.copy(status = ShiftStatus.PLANNED)) }
        launch()
        await("Сегодня")
        compose.onNodeWithText("Смены", useUnmergedTree = false).performClick()
        val today = LocalDate.now(original.zone)
        if (java.time.YearMonth.from(today) != java.time.YearMonth.from(original.date)) {
            compose.onNodeWithContentDescription("Предыдущий месяц").performClick()
        }
        val formatted =
            original.date.format(
                java.time.format.DateTimeFormatter.ofPattern(
                    "d MMMM yyyy",
                    java.util.Locale.forLanguageTag("ru-RU"),
                )
            )
        compose
            .onNodeWithContentDescription("$formatted, Запланирована")
            .performScrollTo()
            .performClick()
        compose.onNodeWithText("Подтвердить смену").performScrollTo().performClick()
        compose.waitUntil(15000) {
            runBlocking {
                app.repository.ledger.first().shifts.single().domain().status ==
                    ShiftStatus.CONFIRMED
            }
        }
        compose.onNodeWithText("Редактировать смену").performScrollTo().performClick()
        await("Сохранить смену")
        compose.onNodeWithText("Корректировка · ₽").performScrollTo().performTextReplacement("-100")
        compose.onNodeWithText("Сохранить смену").performScrollTo().performClick()
        compose.waitUntil(15000) {
            runBlocking {
                app.repository.ledger.first().shifts.single().domain().adjustment == -10000L
            }
        }
        compose.onNodeWithText("Деньги", useUnmergedTree = false).performClick()
        if (java.time.YearMonth.from(today) != java.time.YearMonth.from(original.date)) {
            compose.onNodeWithContentDescription("Предыдущий расчётный период").performClick()
        }
        compose.onNodeWithText("Ввести расчётный листок").performScrollTo().performClick()
        compose.onNodeWithText("Начислено до удержаний · ₽").performTextInput("2150")
        compose.onNodeWithText("Все удержания · ₽").performTextInput("130")
        compose.onNodeWithText("Сохранить листок").performScrollTo().performClick()
        compose.waitUntil(15000) {
            runBlocking { app.repository.ledger.first().statements.isNotEmpty() }
        }
        compose.onNodeWithText("Добавить выплату").performScrollTo().performClick()
        compose.onNodeWithText("Получено · ₽").performTextInput("2020")
        compose.onNodeWithText("Сохранить выплату").performScrollTo().performClick()
        compose.waitUntil(15000) {
            runBlocking { app.repository.ledger.first().payments.isNotEmpty() }
        }
        val ledger = runBlocking { app.repository.ledger.first() }
        assertEquals(215000L, Earnings.calculate(ledger.shifts.single().domain()).total)
        assertEquals(215000L, ledger.statements.single().gross)
        assertEquals(202000L, ledger.payments.single().amount)
        assertTrue(compose.onAllNodesWithText("0,00 ₽").fetchSemanticsNodes().size >= 2)
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
        val original = seed()
        runBlocking {
            val period = original.date.withDayOfMonth(1)
            app.repository.statement(StatementRow(period.toString(), 226000, 24000))
            app.repository.addPayment(period, original.date, 180000)
            app.preferences.save(Preferences(theme = "light", dynamic = false))
        }
        launch()
        await("Подтверждено за период")
        screenshot("today")
        compose.onNodeWithText("Смены", useUnmergedTree = false).performClick()
        await("Редактировать смену")
        screenshot("shifts")
        compose.onNodeWithText("Деньги", useUnmergedTree = false).performClick()
        await("Свой расчёт · подтверждено до удержаний")
        screenshot("money")
        compose.onNodeWithText("По листку к выплате · после удержаний").performScrollTo()
        screenshot("reconciliation")
        compose.onNodeWithText("Настройки").performClick()
        await("Тёмная")
        compose.onNodeWithText("Тёмная").performClick()
        compose.onNodeWithText("Назад").performScrollTo().performClick()
        await("Сегодня")
        screenshot("dark")
        try {
            shell("settings put system font_scale 1.5")
            shell("wm size 840x1400")
            scenario?.recreate()
            await("Сегодня")
            compose.waitForIdle()
            compose.onNode(hasText("Деньги") and hasClickAction()).assertIsDisplayed()
            screenshot("large-font")
            scenario?.onActivity {
                it.requestedOrientation =
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(10000) {
                app.resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            compose.waitForIdle()
            screenshot("landscape")
        } finally {
            shell("settings put system font_scale 1.0")
            shell("wm size reset")
        }
    }

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .executeShellCommand(command)
            )
            .bufferedReader()
            .use { it.readText() }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val frame = java.util.concurrent.CountDownLatch(1)
        requireNotNull(scenario).onActivity { activity ->
            val view = activity.window.decorView
            view.postOnAnimation { view.postOnAnimation { frame.countDown() } }
        }
        check(frame.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
            "Application frame did not render"
        }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(app.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
