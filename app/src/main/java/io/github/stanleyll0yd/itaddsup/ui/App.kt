package io.github.stanleyll0yd.itaddsup.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.stanleyll0yd.itaddsup.TrackerViewModel
import io.github.stanleyll0yd.itaddsup.data.*
import io.github.stanleyll0yd.itaddsup.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ru = Locale.forLanguageTag("ru-RU")

private fun LocalDate.display(): String = format(DateTimeFormatter.ofPattern("d MMMM yyyy", ru))

private fun Shift.hours(): String =
    "${time.start.atZone(zone).toLocalTime()}–${time.end.atZone(zone).toLocalTime()}" +
        if (time.end.atZone(zone).toLocalDate() > date) " · следующий день" else ""

private fun minorInput(amount: Long) = amount.toBigDecimal().movePointLeft(2).toPlainString()

private fun parseMinutes(hours: String) =
    hours.replace(',', '.').toBigDecimal().multiply(60.toBigDecimal()).intValueExact()

private fun total(shifts: List<Shift>): Long =
    shifts.fold(0L) { sum, shift -> Math.addExact(sum, Earnings.calculate(shift).total) }

private fun planned(ledger: Ledger, date: LocalDate): Shift? {
    ledger.shifts
        .firstOrNull { it.date == date.toString() }
        ?.let {
            return it.domain()
        }
    val schedule = ledger.schedule?.domain() ?: return null
    if (!schedule.works(date)) return null
    val rule = Earnings.ruleAt(date, ledger.rates.map { it.domain() }) ?: return null
    return schedule.planned(date, rule).copy(status = ShiftStatus.PLANNED)
}

@Composable
fun TrackerApp(vm: TrackerViewModel) {
    val ledger by vm.ledger.collectAsStateWithLifecycle()
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settings by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.message.value = null
        }
    }
    BackHandler(settings) { settings = false }
    AppTheme(preferences) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (ledger?.schedule != null && !settings)
                    NavigationBar {
                        listOf("Сегодня", "Смены", "Деньги").forEachIndexed { index, label ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = { NavGlyph(index) },
                                label = { Text(label) },
                            )
                        }
                    }
            },
        ) { insets ->
            Column(Modifier.fillMaxSize().padding(insets)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when {
                    ledger == null ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    ledger?.schedule == null -> SetupForm(null, null, busy, vm::setup)
                    settings ->
                        SettingsScreen(
                            requireNotNull(ledger),
                            preferences,
                            vm,
                            onBack = { settings = false },
                        )
                    else -> {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Сошлось?",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { settings = true }) { Text("Настройки") }
                        }
                        when (tab) {
                            0 -> TodayScreen(requireNotNull(ledger), busy, vm::confirm)
                            1 -> CalendarScreen(requireNotNull(ledger), busy, vm::confirm)
                            else -> MoneyScreen(requireNotNull(ledger), vm)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavGlyph(index: Int) {
    val names = listOf("День", "Календарь", "Расчёты")
    Text(
        listOf("◷", "▦", "₽")[index],
        modifier = Modifier.clearAndSetSemantics { contentDescription = names[index] },
        style = MaterialTheme.typography.titleLarge,
    )
}

@Composable
fun AppTheme(preferences: Preferences = Preferences(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark =
        when (preferences.theme) {
            "dark" -> true
            "light" -> false
            else -> isSystemInDarkTheme()
        }
    val colors =
        if (preferences.dynamic && Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (dark) darkColorScheme(primary = Color(0xFF8BD5BB), secondary = Color(0xFFB5CCC2))
        else
            lightColorScheme(
                primary = Color(0xFF1D6653),
                secondary = Color(0xFF4C635A),
                background = Color(0xFFF7FAF6),
            )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    change: (String) -> Unit,
    numeric: Boolean = false,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = change,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions =
            KeyboardOptions(
                keyboardType =
                    if (password) KeyboardType.Password
                    else if (numeric) KeyboardType.Decimal else KeyboardType.Text
            ),
        visualTransformation =
            if (password) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
private fun Choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, name ->
            FilterChip(selected = selected == i, onClick = { onSelect(i) }, label = { Text(name) })
        }
    }
}

@Composable
private fun Value(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun SetupForm(
    existing: ScheduleRow?,
    oldRate: PayRule?,
    busy: Boolean,
    save: (ScheduleRow, PayRule) -> Unit,
) {
    val today = LocalDate.now()
    var preset by rememberSaveable {
        mutableIntStateOf(
            when {
                existing?.weekdays == true -> 3
                existing?.cycle == "111000" -> 1
                existing?.cycle == "1000" -> 2
                existing?.cycle != null && existing.cycle != "1100" -> 4
                else -> 0
            }
        )
    }
    var cycle by rememberSaveable { mutableStateOf(existing?.cycle ?: "1100") }
    var anchor by rememberSaveable { mutableStateOf(existing?.anchor ?: today.toString()) }
    var start by rememberSaveable { mutableStateOf(existing?.start ?: "08:00") }
    var hours by rememberSaveable {
        mutableStateOf(
            existing
                ?.duration
                ?.toBigDecimal()
                ?.divide(60.toBigDecimal(), 2, java.math.RoundingMode.HALF_UP)
                ?.stripTrailingZeros()
                ?.toPlainString() ?: "12"
        )
    }
    var payType by rememberSaveable {
        mutableIntStateOf(if (oldRate?.type == PayType.PER_SHIFT) 1 else 0)
    }
    var rate by rememberSaveable { mutableStateOf(oldRate?.rate?.let(::minorInput) ?: "") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var night by rememberSaveable {
        mutableStateOf(
            oldRate?.nightBasisPoints?.toBigDecimal()?.movePointLeft(2)?.toPlainString() ?: "0"
        )
    }
    var supplement by rememberSaveable {
        mutableStateOf(oldRate?.supplement?.let(::minorInput) ?: "0")
    }
    var breakMins by rememberSaveable { mutableStateOf(existing?.breakMinutes?.toString() ?: "0") }
    var breakOffset by rememberSaveable {
        mutableStateOf(existing?.breakOffset?.toString() ?: "240")
    }
    var periodDay by rememberSaveable { mutableStateOf(existing?.periodDay?.toString() ?: "1") }
    var payday by rememberSaveable { mutableStateOf(existing?.payday?.toString() ?: "10") }
    var zone by rememberSaveable { mutableStateOf(existing?.zone ?: ZoneId.systemDefault().id) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Page {
        Text(
            if (existing == null) "Смены понятны.\nДеньги — тоже." else "График и условия",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            "Один раз задайте график и ставку. Подтверждайте только фактически отработанные смены."
        )
        Choice(listOf("2/2", "3/3", "Сутки / трое", "5/2", "Свой цикл"), preset) {
            preset = it
            if (it == 2) hours = "24"
        }
        if (preset == 4) {
            Field("Цикл: 1 — смена, 0 — выходной", cycle, { cycle = it })
            Text("Например, 1100 — две смены и два выходных. До 60 дней.")
        }
        Field("Первый рабочий день · ГГГГ-ММ-ДД", anchor, { anchor = it })
        Field("Начало · ЧЧ:ММ", start, { start = it })
        Field("Часов в смене", hours, { hours = it }, true)
        Choice(listOf("Почасовая", "За смену"), payType) { payType = it }
        Field(if (payType == 0) "Ставка · ₽/час" else "Ставка · ₽/смена", rate, { rate = it }, true)
        TextButton(onClick = { advanced = !advanced }) {
            Text(if (advanced) "Скрыть дополнительные условия" else "Перерывы, доплаты и выплаты")
        }
        if (advanced) {
            Field("Неоплачиваемый перерыв · минут", breakMins, { breakMins = it }, true)
            if (breakMins != "0")
                Field("Перерыв через минут после начала", breakOffset, { breakOffset = it }, true)
            Field("Доплата за ночные часы · %", night, { night = it }, true)
            Text(
                "Ночь: 22:00–06:00. Процент задаёте вы. Для оплаты за смену используется эквивалентная часовая ставка."
            )
            Field("Постоянная доплата за смену · ₽", supplement, { supplement = it }, true)
            Field("Начало расчётного периода · день 1–28", periodDay, { periodDay = it }, true)
            Field("Ожидаемая выплата · день 1–28", payday, { payday = it }, true)
            Field("Часовой пояс", zone, { zone = it })
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                runCatching {
                        val day = LocalDate.parse(anchor)
                        val row =
                            ScheduleRow(
                                anchor = day.toString(),
                                cycle =
                                    when (preset) {
                                        0 -> "1100"
                                        1 -> "111000"
                                        2 -> "1000"
                                        3 -> "1111100"
                                        else -> cycle
                                    },
                                start = LocalTime.parse(start).toString(),
                                duration = parseMinutes(hours),
                                zone = ZoneId.of(zone).id,
                                weekdays = preset == 3,
                                breakOffset = breakOffset.toInt(),
                                breakMinutes = breakMins.toInt(),
                                periodDay = periodDay.toInt(),
                                payday = payday.toInt(),
                            )
                        row.domain()
                        require(row.periodDay in 1..28 && row.payday in 1..28)
                        val rule =
                            PayRule(
                                day,
                                if (payType == 0) PayType.HOURLY else PayType.PER_SHIFT,
                                Money.parse(rate),
                                night
                                    .replace(',', '.')
                                    .toBigDecimal()
                                    .movePointRight(2)
                                    .intValueExact(),
                                Money.parse(supplement),
                            )
                        save(row, rule)
                    }
                    .onFailure {
                        error =
                            "Проверьте дату, время, ставку и дополнительные условия. Перерыв должен помещаться внутри смены."
                    }
            },
        ) {
            Text(if (existing == null) "Показать мой график" else "Сохранить график")
        }
        Text(
            "Без аккаунта. Данные сохраняются на устройстве.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TodayScreen(ledger: Ledger, busy: Boolean, save: (Shift) -> Unit) {
    val schedule = requireNotNull(ledger.schedule)
    val today = LocalDate.now(ZoneId.of(schedule.zone))
    val period = Period.containing(today, schedule.periodDay)
    val shifts = ledger.shifts.map { it.domain() }.filter { it.date in period }
    val confirmed = total(shifts)
    val future =
        period
            .dates()
            .filter { it >= today }
            .mapNotNull { planned(ledger, it) }
            .filter { it.status == ShiftStatus.PLANNED }
    val forecast =
        Math.addExact(confirmed, total(future.map { it.copy(status = ShiftStatus.CONFIRMED) }))
    val shift = planned(ledger, today)
    var edit by remember { mutableStateOf<Shift?>(null) }
    val next =
        (0L..366L)
            .map { today.plusDays(it) }
            .firstOrNull { planned(ledger, it)?.status == ShiftStatus.PLANNED }
    val thisPayday = today.withDayOfMonth(schedule.payday)
    val payday = if (thisPayday < today) thisPayday.plusMonths(1) else thisPayday
    Page {
        Text(today.display(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Подтверждено за период", style = MaterialTheme.typography.labelLarge)
                Text(
                    Money.text(confirmed),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${period.start.display()} — ${period.endExclusive.minusDays(1).display()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Value("Прогноз с будущими сменами · до удержаний", Money.text(forecast))
        Text(
            "Предварительный расчёт. Будущие смены и доплаты не гарантированы. Прошедшие неподтверждённые смены в прогноз не входят.",
            style = MaterialTheme.typography.bodySmall,
        )
        Section("Сегодня") {
            when {
                shift == null -> Text("По графику — выходной.")
                shift.status == ShiftStatus.PLANNED -> {
                    Text(shift.hours(), style = MaterialTheme.typography.titleLarge)
                    Text(
                        "По плану: ${Money.text(Earnings.calculate(shift.copy(status = ShiftStatus.CONFIRMED)).total)} до удержаний"
                    )
                    Button(
                        enabled = !busy && shift.time.end <= Instant.now(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { save(shift.copy(status = ShiftStatus.CONFIRMED)) },
                    ) {
                        Text("Подтвердить смену")
                    }
                    if (shift.time.end > Instant.now())
                        Text(
                            "Подтверждение будет доступно после окончания. Фактическое время можно изменить.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    OutlinedButton(onClick = { edit = shift }) {
                        Text("Изменить фактическое время")
                    }
                }
                else -> {
                    Text(statusText(shift.status))
                    ReceiptView(shift)
                    TextButton(onClick = { edit = shift }) { Text("Изменить смену") }
                }
            }
            if (shift == null)
                TextButton(
                    onClick = {
                        Earnings.ruleAt(today, ledger.rates.map { it.domain() })?.let {
                            edit =
                                schedule
                                    .domain()
                                    .planned(today, it)
                                    .copy(status = ShiftStatus.PLANNED)
                        }
                    }
                ) {
                    Text("Добавить вне графика")
                }
        }
        HorizontalDivider()
        Value("Ближайшая смена", next?.display() ?: "Нет в ближайшем году")
        Value("Ожидаемая дата выплаты", payday.display())
        Text(
            "Сумма к выплате зависит от удержаний и расчётного листка. Добавьте их в разделе «Деньги»."
        )
    }
    edit?.let { s ->
        ShiftEditor(s, busy, { edit = null }) {
            save(it)
            edit = null
        }
    }
}

private fun statusText(status: ShiftStatus): String =
    when (status) {
        ShiftStatus.PLANNED -> "Запланирована"
        ShiftStatus.CONFIRMED -> "Подтверждена"
        ShiftStatus.ABSENT -> "Неявка"
        ShiftStatus.CANCELLED -> "Отменена"
    }

@Composable
private fun CalendarScreen(ledger: Ledger, busy: Boolean, save: (Shift) -> Unit) {
    val schedule = requireNotNull(ledger.schedule)
    val today = LocalDate.now(ZoneId.of(schedule.zone))
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var selectedText by rememberSaveable { mutableStateOf(today.toString()) }
    val month = YearMonth.parse(monthText)
    val selected = LocalDate.parse(selectedText)
    var edit by remember { mutableStateOf<Shift?>(null) }
    val shift = planned(ledger, selected)
    Page {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { monthText = month.minusMonths(1).toString() },
                modifier = Modifier.semantics { contentDescription = "Предыдущий месяц" },
            ) {
                Text("‹")
            }
            Text(
                month.format(DateTimeFormatter.ofPattern("LLLL yyyy", ru)),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            OutlinedButton(
                onClick = { monthText = month.plusMonths(1).toString() },
                modifier = Modifier.semantics { contentDescription = "Следующий месяц" },
            ) {
                Text("›")
            }
        }
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Column(Modifier.widthIn(min = 336.dp)) {
                Row {
                    listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach {
                        Text(
                            it,
                            Modifier.width(48.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                val offset = month.atDay(1).dayOfWeek.value - 1
                repeat((offset + month.lengthOfMonth() + 6) / 7) { week ->
                    Row {
                        repeat(7) { day ->
                            val number = week * 7 + day - offset + 1
                            if (number !in 1..month.lengthOfMonth()) Spacer(Modifier.size(48.dp))
                            else {
                                val date = month.atDay(number)
                                val s = planned(ledger, date)
                                Surface(
                                    color =
                                        when {
                                            date == selected ->
                                                MaterialTheme.colorScheme.primaryContainer
                                            s?.status == ShiftStatus.CONFIRMED ->
                                                MaterialTheme.colorScheme.secondaryContainer
                                            else -> MaterialTheme.colorScheme.surface
                                        },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier =
                                        Modifier.size(48.dp)
                                            .clickable { selectedText = date.toString() }
                                            .semantics {
                                                contentDescription =
                                                    "${date.display()}, ${s?.let { statusText(it.status) } ?: "выходной"}"
                                            },
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Text(
                                            number.toString(),
                                            fontWeight =
                                                if (date == today) FontWeight.Bold
                                                else FontWeight.Normal,
                                        )
                                        if (s != null)
                                            Text(
                                                if (s.status == ShiftStatus.CONFIRMED) "✓"
                                                else if (s.status == ShiftStatus.PLANNED) "•"
                                                else "–",
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(
            "• по плану    ✓ подтверждена    – отмена / неявка",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(selected.display(), style = MaterialTheme.typography.titleLarge)
        if (shift != null) {
            Text(statusText(shift.status))
            Text(shift.hours())
            ReceiptView(shift)
            if (shift.status == ShiftStatus.PLANNED && shift.time.end <= Instant.now())
                Button(
                    enabled = !busy,
                    onClick = { save(shift.copy(status = ShiftStatus.CONFIRMED)) },
                ) {
                    Text("Подтвердить смену")
                }
            OutlinedButton(onClick = { edit = shift }) { Text("Редактировать смену") }
        } else {
            Text("Выходной. Можно добавить смену вне графика.")
            OutlinedButton(
                onClick = {
                    Earnings.ruleAt(selected, ledger.rates.map { it.domain() })?.let {
                        edit =
                            schedule
                                .domain()
                                .planned(selected, it)
                                .copy(status = ShiftStatus.PLANNED)
                    }
                },
                enabled = Earnings.ruleAt(selected, ledger.rates.map { it.domain() }) != null,
            ) {
                Text("Добавить смену")
            }
        }
        val audits = ledger.audits.filter { it.date == selectedText }.takeLast(10).reversed()
        if (audits.isNotEmpty())
            Section("История изменений · ${ledger.audits.count { it.date == selectedText }}") {
                audits.forEach { a ->
                    Text(
                        "${Instant.ofEpochMilli(a.changedAt).atZone(ZoneId.of(schedule.zone)).format(DateTimeFormatter.ofPattern("d MMM HH:mm", ru))} · ${if (a.before == null) "Добавлена" else "Изменена"} · ${Money.text(Earnings.calculate(ShiftJson.decode(a.after)).total)}"
                    )
                }
            }
    }
    edit?.let { s ->
        ShiftEditor(s, busy, { edit = null }) {
            save(it)
            edit = null
        }
    }
}

@Composable
private fun ReceiptView(shift: Shift) {
    val preview = shift.status == ShiftStatus.PLANNED
    val r = Earnings.calculate(if (preview) shift.copy(status = ShiftStatus.CONFIRMED) else shift)
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Value(
            if (preview) "Предварительный чек · до удержаний" else "Чек смены · до удержаний",
            Money.text(r.total),
        )
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Скрыть расчёт" else "Как посчитано")
        }
        if (expanded) {
            Text(
                "Оплачено: ${r.paidSeconds / 3600} ч ${(r.paidSeconds % 3600) / 60} мин; из них ночью: ${r.nightSeconds / 3600} ч ${(r.nightSeconds % 3600) / 60} мин"
            )
            Text(
                "Основная оплата: ${Money.text(r.base)}\nНочные: ${Money.text(r.night)}\nДоплата: ${Money.text(r.supplement)}\nПремия: ${Money.text(r.bonus)}\nКорректировка: ${Money.text(r.adjustment)}"
            )
            Text(
                "Снимок условий: ${Money.text(shift.rule.rate)} ${if (shift.rule.type == PayType.HOURLY) "в час" else "за смену"}, ночная доплата ${shift.rule.nightBasisPoints.toBigDecimal().movePointLeft(2)}%. Каждая строка округляется до копейки (HALF_UP).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ShiftEditor(original: Shift, busy: Boolean, close: () -> Unit, save: (Shift) -> Unit) {
    var begin by rememberSaveable {
        mutableStateOf(original.time.start.atZone(original.zone).toLocalTime().toString())
    }
    var endDate by rememberSaveable {
        mutableStateOf(original.time.end.atZone(original.zone).toLocalDate().toString())
    }
    var endTime by rememberSaveable {
        mutableStateOf(original.time.end.atZone(original.zone).toLocalTime().toString())
    }
    var breakOffset by rememberSaveable {
        mutableStateOf(
            original.breaks.firstOrNull()?.let {
                Duration.between(original.time.start, it.start).toMinutes().toString()
            } ?: "240"
        )
    }
    var breakMinutes by rememberSaveable {
        mutableStateOf(original.breaks.sumOf { it.seconds / 60 }.toString())
    }
    var bonus by rememberSaveable { mutableStateOf(minorInput(original.bonus)) }
    var adjustment by rememberSaveable { mutableStateOf(minorInput(original.adjustment)) }
    var status by rememberSaveable { mutableIntStateOf(original.status.ordinal) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = close) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp).imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(original.date.display(), style = MaterialTheme.typography.titleLarge)
                Text("Фактическое время · ${original.zone.id}")
                Field("Начало · ЧЧ:ММ", begin, { begin = it })
                Field("Дата окончания · ГГГГ-ММ-ДД", endDate, { endDate = it })
                Field("Окончание · ЧЧ:ММ", endTime, { endTime = it })
                Field("Неоплачиваемый перерыв · минут", breakMinutes, { breakMinutes = it }, true)
                if (breakMinutes != "0")
                    Field(
                        "Перерыв через минут после начала",
                        breakOffset,
                        { breakOffset = it },
                        true,
                    )
                Field("Премия · ₽", bonus, { bonus = it }, true)
                Field("Корректировка · ₽, можно с минусом", adjustment, { adjustment = it }, true)
                Choice(ShiftStatus.entries.map(::statusText), status) { status = it }
                Text(
                    "Условия оплаты сохраняются из исходной смены. Изменение ставки в настройках их не переписывает.",
                    style = MaterialTheme.typography.bodySmall,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    enabled = !busy,
                    onClick = {
                        runCatching {
                                val start =
                                    original.date
                                        .atTime(LocalTime.parse(begin))
                                        .atZone(original.zone)
                                        .toInstant()
                                val end =
                                    LocalDate.parse(endDate)
                                        .atTime(LocalTime.parse(endTime))
                                        .atZone(original.zone)
                                        .toInstant()
                                val minutes = breakMinutes.toLong()
                                require(minutes in 0..2880)
                                val offset = breakOffset.toLong()
                                require(offset in 0..2880)
                                val breaks =
                                    if (minutes == 0L) emptyList()
                                    else
                                        listOf(
                                            TimeSpan(
                                                start.plusSeconds(offset * 60),
                                                start.plusSeconds((offset + minutes) * 60),
                                            )
                                        )
                                val changed =
                                    original.copy(
                                        time = TimeSpan(start, end),
                                        breaks = breaks,
                                        bonus = Money.parse(bonus),
                                        adjustment = Money.parse(adjustment, true),
                                        status = ShiftStatus.entries[status],
                                    )
                                require(
                                    changed.status != ShiftStatus.CONFIRMED || end <= Instant.now()
                                )
                                Earnings.calculate(changed)
                                save(changed)
                            }
                            .onFailure {
                                error =
                                    "Проверьте время, перерыв и суммы. Подтверждать можно только закончившуюся смену (до 48 часов)."
                            }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Сохранить смену")
                }
                TextButton(onClick = close) { Text("Отмена") }
            }
        }
    }
}

@Composable
private fun MoneyScreen(ledger: Ledger, vm: TrackerViewModel) {
    val row = requireNotNull(ledger.schedule)
    val today = LocalDate.now(ZoneId.of(row.zone))
    var periodText by rememberSaveable {
        mutableStateOf(Period.containing(today, row.periodDay).start.toString())
    }
    val period = Period.containing(LocalDate.parse(periodText), row.periodDay)
    val own = total(ledger.shifts.map { it.domain() }.filter { it.date in period })
    val statement = ledger.statements.firstOrNull { it.period == period.start.toString() }
    val payments = ledger.payments.filter { it.period == period.start.toString() }
    val reconciliation =
        Reconciliation(
            own,
            statement?.gross,
            statement?.deductions,
            payments.map { Payment(LocalDate.parse(it.date), it.amount) },
        )
    var showPayment by remember { mutableStateOf(false) }
    var showStatement by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<PaymentRow?>(null) }
    val future =
        period
            .dates()
            .filter { it >= today }
            .mapNotNull { planned(ledger, it) }
            .filter { it.status == ShiftStatus.PLANNED }
    Page {
        Text("Деньги", style = MaterialTheme.typography.headlineMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { periodText = period.start.minusMonths(1).toString() },
                modifier = Modifier.semantics { contentDescription = "Предыдущий расчётный период" },
            ) {
                Text("‹")
            }
            Text(
                "${period.start.display()} —\n${period.endExclusive.minusDays(1).display()}",
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { periodText = period.start.plusMonths(1).toString() },
                modifier = Modifier.semantics { contentDescription = "Следующий расчётный период" },
            ) {
                Text("›")
            }
        }
        Value("Свой расчёт · подтверждено до удержаний", Money.text(own))
        Value(
            "Будущие смены · предварительно",
            Money.text(total(future.map { it.copy(status = ShiftStatus.CONFIRMED) })),
        )
        Text(
            "Прогноз не гарантирует начисления. Неизвестные премии и удержания не предполагаются равными нулю.",
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()
        Section("Сошлось?") {
            Value(
                "Начисления работодателя · до удержаний",
                statement?.gross?.let(Money::text) ?: "Не указаны",
            )
            Value(
                "Разница начислений · работодатель минус свой расчёт",
                reconciliation.grossDifference?.let(Money::text) ?: "Недостаточно данных",
            )
            Value("Известные удержания", statement?.deductions?.let(Money::text) ?: "Не указаны")
            Value(
                "По листку к выплате · после удержаний",
                reconciliation.employerNet?.let(Money::text) ?: "Недостаточно данных",
            )
            Value("Фактически получено · после удержаний", Money.text(reconciliation.paid))
            Value(
                "Разница выплат · получено минус листок",
                reconciliation.paymentDifference?.let(Money::text) ?: "Недостаточно данных",
            )
            reconciliation.explanations().forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onClick = { showStatement = true }) { Text("Ввести расчётный листок") }
        }
        Section("Выплаты за период") {
            if (payments.isEmpty())
                Text("Пока не добавлены. Укажите аванс и остальные поступления отдельно.")
            payments.forEach { payment ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${LocalDate.parse(payment.date).display()}\n${Money.text(payment.amount)}",
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { remove = payment }) { Text("Удалить") }
                }
            }
            Button(onClick = { showPayment = true }) { Text("Добавить выплату") }
        }
        Section("Архив смен") {
            val shifts = ledger.shifts.map { it.domain() }.filter { it.date in period }.reversed()
            if (shifts.isEmpty()) Text("За этот период сохранённых смен нет.")
            shifts.forEach {
                Text(
                    "${it.date.display()} · ${statusText(it.status)}\n${Money.text(Earnings.calculate(it).total)}"
                )
            }
        }
    }
    if (showPayment)
        FormDialog("Новая выплата", { showPayment = false }) { done ->
            var date by rememberSaveable { mutableStateOf(today.toString()) }
            var amount by rememberSaveable { mutableStateOf("") }
            var error by remember { mutableStateOf(false) }
            Text(
                "Относится к периоду с ${period.start.display()}, даже если поступила в другом месяце."
            )
            Field("Дата поступления · ГГГГ-ММ-ДД", date, { date = it })
            Field("Получено · ₽", amount, { amount = it }, true)
            if (error)
                Text(
                    "Проверьте дату и положительную сумму.",
                    color = MaterialTheme.colorScheme.error,
                )
            Button(
                onClick = {
                    runCatching {
                            val value = Money.parse(amount)
                            require(value > 0)
                            vm.addPayment(period.start, LocalDate.parse(date), value)
                            done()
                        }
                        .onFailure { error = true }
                }
            ) {
                Text("Сохранить выплату")
            }
        }
    if (showStatement)
        FormDialog("Расчётный листок", { showStatement = false }) { done ->
            var gross by rememberSaveable {
                mutableStateOf(statement?.gross?.let(::minorInput) ?: "")
            }
            var deductions by rememberSaveable {
                mutableStateOf(statement?.deductions?.let(::minorInput) ?: "")
            }
            var error by remember { mutableStateOf(false) }
            Text(
                "Перепишите итоговые суммы за период. Пустое поле означает «неизвестно»; известный ноль укажите явно."
            )
            Field("Начислено до удержаний · ₽", gross, { gross = it }, true)
            Field("Все удержания · ₽", deductions, { deductions = it }, true)
            if (error)
                Text(
                    "Проверьте суммы: до двух знаков после запятой.",
                    color = MaterialTheme.colorScheme.error,
                )
            Button(
                onClick = {
                    runCatching {
                            vm.saveStatement(
                                StatementRow(
                                    period.start.toString(),
                                    gross.takeIf { it.isNotBlank() }?.let { Money.parse(it) },
                                    deductions.takeIf { it.isNotBlank() }?.let { Money.parse(it) },
                                )
                            )
                            done()
                        }
                        .onFailure { error = true }
                }
            ) {
                Text("Сохранить листок")
            }
        }
    remove?.let { payment ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("Удалить выплату?") },
            text = {
                Text(
                    "${Money.text(payment.amount)} за ${LocalDate.parse(payment.date).display()}. Сверка будет пересчитана."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deletePayment(payment.id)
                        remove = null
                    }
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun FormDialog(
    title: String,
    close: () -> Unit,
    content: @Composable ColumnScope.(done: () -> Unit) -> Unit,
) {
    Dialog(onDismissRequest = close) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp).imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                content(close)
                TextButton(onClick = close) { Text("Отмена") }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    ledger: Ledger,
    preferences: Preferences,
    vm: TrackerViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    var editSchedule by remember { mutableStateOf(false) }
    var editRate by remember { mutableStateOf(false) }
    var erase by remember { mutableStateOf(false) }
    var csvConfirm by remember { mutableStateOf(false) }
    var fileAction by remember { mutableStateOf<String?>(null) }
    var pendingPassword by remember { mutableStateOf<CharArray?>(null) }
    val backupLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            val password = pendingPassword
            pendingPassword = null
            if (uri == null || password == null) password?.fill('\u0000')
            else
                vm.backup(password) { bytes ->
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use {
                        it.write(bytes)
                    }
                }
        }
    val restoreLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val password = pendingPassword
            pendingPassword = null
            if (uri == null || password == null) password?.fill('\u0000')
            else
                vm.restore(password) {
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            require(output.size() + read <= EncryptedBackup.MAX_BYTES + 48)
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    }
                }
        }
    val csvLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri
            ->
            if (uri != null)
                vm.exportCsv { bytes ->
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use {
                        it.write(bytes)
                    }
                }
        }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            vm.preferences(preferences.copy(reminders = granted))
            if (!granted)
                vm.message.value = "Уведомления не разрешены. Учёт смен продолжает работать."
        }
    BackHandler(editSchedule) { editSchedule = false }
    if (editSchedule) {
        val today = LocalDate.now(ZoneId.of(requireNotNull(ledger.schedule).zone))
        SetupForm(
            ledger.schedule,
            Earnings.ruleAt(today, ledger.rates.map { it.domain() }),
            busy,
        ) { row, rule ->
            vm.setup(row, rule.copy(effectiveFrom = today))
            editSchedule = false
        }
        return
    }
    Page {
        TextButton(onClick = onBack) { Text("Назад") }
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        Section("Внешний вид") {
            Choice(
                listOf("Как в системе", "Светлая", "Тёмная"),
                listOf("system", "light", "dark").indexOf(preferences.theme).coerceAtLeast(0),
            ) {
                vm.preferences(preferences.copy(theme = listOf("system", "light", "dark")[it]))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Цвета обоев · Android 12+", modifier = Modifier.weight(1f))
                Switch(
                    checked = preferences.dynamic,
                    onCheckedChange = { vm.preferences(preferences.copy(dynamic = it)) },
                    modifier = Modifier.semantics { contentDescription = "Динамические цвета" },
                )
            }
        }
        Section("График и оплата") {
            Text(
                "${ledger.schedule?.zone}. Изменение графика сохраняет подтверждённые смены. Новые условия действуют с выбранной даты; чеки прошлых смен не меняются."
            )
            OutlinedButton(onClick = { editSchedule = true }) { Text("Изменить график") }
            OutlinedButton(onClick = { editRate = true }) { Text("Добавить ставку с даты") }
            ledger.rates.reversed().forEach { r ->
                Text(
                    "С ${LocalDate.parse(r.effective).display()}: ${Money.text(r.rate)} ${if (r.type == PayType.HOURLY.name) "в час" else "за смену"} · ночные ${r.nightBps.toBigDecimal().movePointLeft(2)}%"
                )
            }
        }
        Section("Напоминания") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Проверить завершённую смену", modifier = Modifier.weight(1f))
                Switch(
                    checked = preferences.reminders,
                    onCheckedChange = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= 33)
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else vm.preferences(preferences.copy(reminders = enabled))
                    },
                    modifier = Modifier.semantics { contentDescription = "Напоминания о сменах" },
                )
            }
            Text(
                "Без геолокации и постоянной службы. Android может задерживать напоминания для экономии батареи.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Section("Ваши данные") {
            OutlinedButton(enabled = !busy, onClick = { csvConfirm = true }) { Text("Экспорт CSV") }
            OutlinedButton(enabled = !busy, onClick = { fileAction = "backup" }) {
                Text("Зашифрованная резервная копия")
            }
            OutlinedButton(enabled = !busy, onClick = { fileAction = "restore" }) {
                Text("Восстановить из копии")
            }
            Text(
                "Копия защищена паролем (AES-256-GCM). Пароль не хранится и не восстанавливается. Сохраните его отдельно. Восстановление заменяет текущие данные после проверки целостности.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(enabled = !busy, onClick = { erase = true }) {
                Text("Удалить все данные", color = MaterialTheme.colorScheme.error)
            }
        }
        Section("Конфиденциальность") {
            Text(
                "Данные о сменах, начислениях и выплатах хранятся на этом устройстве. Нет аккаунта, облачной синхронизации, рекламного SDK и внешней аналитики в этой версии. Автоматическое системное резервное копирование отключено; делайте свою зашифрованную копию."
            )
            Text(
                "Приложение помогает сверить собственные записи. Оно не рассчитывает автоматически все налоги и юридические надбавки."
            )
            Text(
                "Версия 0.2.0-alpha.1 · Название предварительное",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (editRate)
        FormDialog("Новые условия оплаты", { editRate = false }) { done ->
            var effective by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
            var type by rememberSaveable { mutableIntStateOf(0) }
            var rate by rememberSaveable { mutableStateOf("") }
            var night by rememberSaveable { mutableStateOf("0") }
            var supplement by rememberSaveable { mutableStateOf("0") }
            var error by remember { mutableStateOf(false) }
            Field("С даты · ГГГГ-ММ-ДД", effective, { effective = it })
            Choice(listOf("Почасовая", "За смену"), type) { type = it }
            Field("Ставка · ₽", rate, { rate = it }, true)
            Field("Ночная доплата · %", night, { night = it }, true)
            Field("Доплата за смену · ₽", supplement, { supplement = it }, true)
            Text(
                "Сохранённые смены сохраняют свой снимок условий. Ставка с той же датой заменяется для новых смен."
            )
            if (error) Text("Проверьте дату и суммы.", color = MaterialTheme.colorScheme.error)
            Button(
                onClick = {
                    runCatching {
                            vm.saveRate(
                                PayRule(
                                    LocalDate.parse(effective),
                                    if (type == 0) PayType.HOURLY else PayType.PER_SHIFT,
                                    Money.parse(rate),
                                    night
                                        .replace(',', '.')
                                        .toBigDecimal()
                                        .movePointRight(2)
                                        .intValueExact(),
                                    Money.parse(supplement),
                                )
                            )
                            done()
                        }
                        .onFailure { error = true }
                }
            ) {
                Text("Сохранить условия")
            }
        }
    if (csvConfirm)
        AlertDialog(
            onDismissRequest = { csvConfirm = false },
            title = { Text("CSV содержит финансовые данные") },
            text = {
                Text(
                    "Этот формат не зашифрован. Выберите доверенное место хранения. Для переноса полной истории используйте зашифрованную копию."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        csvConfirm = false
                        csvLauncher.launch("it-adds-up.csv")
                    }
                ) {
                    Text("Выбрать файл")
                }
            },
            dismissButton = { TextButton(onClick = { csvConfirm = false }) { Text("Отмена") } },
        )
    fileAction?.let { action ->
        FormDialog(
            if (action == "backup") "Пароль резервной копии"
            else "Восстановление заменит текущие данные",
            { fileAction = null },
        ) { done ->
            var password by remember { mutableStateOf("") }
            var repeat by remember { mutableStateOf("") }
            Field("Пароль · минимум 8 символов", password, { password = it }, password = true)
            if (action == "backup")
                Field("Повторите пароль", repeat, { repeat = it }, password = true)
            Text(
                if (action == "backup")
                    "Без пароля копию нельзя восстановить. Сохраните пароль отдельно."
                else
                    "Текущая база будет заменена только после проверки пароля, целостности и версии. Отменить успешное восстановление нельзя; сначала сохраните свою копию."
            )
            Button(
                enabled = password.length >= 8 && (action != "backup" || password == repeat),
                onClick = {
                    pendingPassword?.fill('\u0000')
                    pendingPassword = password.toCharArray()
                    password = ""
                    repeat = ""
                    done()
                    if (action == "backup") backupLauncher.launch("it-adds-up.iau")
                    else
                        restoreLauncher.launch(
                            arrayOf("application/octet-stream", "application/json", "*/*")
                        )
                },
            ) {
                Text(if (action == "backup") "Сохранить копию" else "Выбрать и восстановить")
            }
        }
    }
    if (erase)
        AlertDialog(
            onDismissRequest = { erase = false },
            title = { Text("Удалить все локальные данные?") },
            text = {
                Text(
                    "Смены, ставки, выплаты, сверки и настройки будут удалены. Это действие нельзя отменить без вашей резервной копии. Ранее экспортированные файлы останутся там, где вы их сохранили."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.erase()
                        erase = false
                    }
                ) {
                    Text("Удалить всё", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { erase = false }) { Text("Отмена") } },
        )
}
