package io.github.stanleyll0yd.itaddsup.monetization

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yandex.mobile.ads.banner.BannerAdEventListener
import com.yandex.mobile.ads.banner.BannerAdSize
import com.yandex.mobile.ads.banner.BannerAdView
import com.yandex.mobile.ads.common.*
import io.github.stanleyll0yd.itaddsup.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

internal object AdsDiagnostics {
    val initialized = MutableStateFlow(false)
    val loaded = MutableStateFlow(false)
    val failed = MutableStateFlow(false)
    val impression = MutableStateFlow(false)
}

/** Receives no ledger, pay, employer, date or financial parameters. */
interface BannerProvider {
    suspend fun initialize(context: Context)

    fun create(context: Context, widthDp: Int): BannerHandle?

    fun revoke()
}

interface BannerHandle {
    val view: android.view.View
    val heightPx: Int

    fun destroy()
}

object NoAds : BannerProvider {
    override suspend fun initialize(context: Context) = Unit

    override fun create(context: Context, widthDp: Int): BannerHandle? = null

    override fun revoke() = Unit
}

class TestAds : YandexBannerProvider("demo-banner-yandex")

class ProductionAds(id: String) : YandexBannerProvider(id) {
    init {
        require(
            id.isNotBlank() && !id.startsWith("demo-") && id.matches(Regex("[A-Za-z0-9_-]{1,128}"))
        )
    }
}

open class YandexBannerProvider(private val unitId: String) : BannerProvider {
    override suspend fun initialize(context: Context) {
        withTimeout(5000) {
            suspendCancellableCoroutine { continuation ->
                YandexAds.setUserConsent(true)
                YandexAds.setLocationTracking(false)
                YandexAds.enableLogging(false)
                YandexAds.initialize(context.applicationContext) {
                    AdsDiagnostics.initialized.value = true
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    }

    override fun create(context: Context, widthDp: Int): BannerHandle {
        AdsDiagnostics.loaded.value = false
        AdsDiagnostics.failed.value = false
        AdsDiagnostics.impression.value = false
        val size = BannerAdSize.sticky(context, widthDp)
        val banner = BannerAdView(context)
        banner.setAdSize(size)
        // No request parameters, IDs from the ledger, targeting, or salary analytics.
        banner.setBannerAdEventListener(
            object : BannerAdEventListener {
                override fun onAdLoaded() {
                    AdsDiagnostics.loaded.value = true
                }

                override fun onAdFailedToLoad(error: AdRequestError) {
                    AdsDiagnostics.failed.value = true
                }

                override fun onAdClicked() = Unit

                override fun onImpression(impressionData: ImpressionData?) {
                    AdsDiagnostics.impression.value = true
                }
            }
        )
        banner.loadAd(AdRequest.Builder(unitId).build())
        return object : BannerHandle {
            override val view: android.view.View = banner
            override val heightPx: Int = size.getHeightInPixels(context)

            override fun destroy() = banner.destroy()
        }
    }

    override fun revoke() {
        YandexAds.setUserConsent(false)
    }
}

/** Public GitHub configuration, fail closed. Called only after explicit consent. */
object RemoteAdsSwitch {
    suspend fun enabled(): Boolean =
        withContext(Dispatchers.IO) {
            val connection =
                URL(
                        "https://raw.githubusercontent.com/StanleyLl0yd/it-adds-up/main/config/ads.json"
                    )
                    .openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.instanceFollowRedirects = false
                if (connection.responseCode != 200) return@withContext false
                val bytes =
                    connection.inputStream.use { stream ->
                        val buffer = ByteArray(4097)
                        var size = 0
                        while (size < buffer.size) {
                            val read = stream.read(buffer, size, buffer.size - size)
                            if (read < 0) break
                            size += read
                        }
                        require(size <= 4096)
                        buffer.copyOf(size)
                    }
                JSONObject(bytes.toString(Charsets.UTF_8)).getBoolean("enabled")
            } catch (_: Exception) {
                false
            } finally {
                connection.disconnect()
            }
        }
}

@Composable
fun ConsentBanner(consent: Boolean) {
    if (!consent) return
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(BuildConfig.DEBUG) }
    val provider = remember {
        if (BuildConfig.DEBUG) TestAds()
        else if (BuildConfig.YANDEX_BANNER_ID.isNotBlank())
            runCatching<BannerProvider> { ProductionAds(BuildConfig.YANDEX_BANNER_ID) }
                .getOrDefault(NoAds)
        else NoAds
    }
    LaunchedEffect(Unit) {
        if (!BuildConfig.DEBUG && provider !== NoAds) allowed = RemoteAdsSwitch.enabled()
    }
    if (!allowed || provider === NoAds) return
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(provider) {
        try {
            provider.initialize(context)
            ready = true
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
        } catch (_: Exception) {
            ready = false
        }
    }
    DisposableEffect(provider) { onDispose { provider.revoke() } }
    if (!ready) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth.value.roundToInt().coerceAtLeast(1)
        val handle =
            remember(provider, width) {
                runCatching { provider.create(context, width) }.getOrNull()
            }
        DisposableEffect(handle) { onDispose { handle?.destroy() } }
        handle?.let { banner ->
            val height = with(LocalDensity.current) { banner.heightPx.toDp() }
            Column(
                Modifier.fillMaxWidth().height(height + 32.dp).testTag("yandex-banner-slot"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Реклама",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AndroidView(
                    factory = { banner.view },
                    modifier = Modifier.fillMaxWidth().height(height),
                )
            }
        }
    }
}
