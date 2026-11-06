package io.github.stanleyll0yd.itaddsup

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.stanleyll0yd.itaddsup.data.*
import io.github.stanleyll0yd.itaddsup.domain.*
import io.github.stanleyll0yd.itaddsup.monetization.AdsDiagnostics
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

@Ignore("Online test requires owner's explicit approval of Yandex test-emulator data transmission")
class AdsNetworkTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun officialTestBannerLoadsAndConsentCanBeRevoked() {
        val app: TrackerApplication = ApplicationProvider.getApplicationContext()
        runBlocking {
            app.repository.erase()
            val date = LocalDate.now().minusDays(1)
            app.repository.setup(
                ScheduleRow(
                    anchor = date.toString(),
                    cycle = "1100",
                    start = "08:00",
                    duration = 480,
                    zone = "UTC",
                    weekdays = false,
                    breakOffset = 240,
                    breakMinutes = 0,
                ),
                PayRule(date, PayType.HOURLY, 30000),
            )
            app.preferences.save(Preferences(theme = "light", dynamic = false, adConsent = true))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(20000) {
                compose.onAllNodesWithText("Реклама").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Реклама").performScrollTo()
            compose.waitUntil(60000) { AdsDiagnostics.loaded.value || AdsDiagnostics.failed.value }
            assertTrue("Official test banner did not load", AdsDiagnostics.loaded.value)
            compose.onNodeWithText("Настройки").performClick()
            compose
                .onNodeWithContentDescription("Согласие на рекламу")
                .performScrollTo()
                .performClick()
            compose.onNodeWithText("Назад").performScrollTo().performClick()
            compose.onNodeWithText("Реклама").assertDoesNotExist()
        }
    }
}
