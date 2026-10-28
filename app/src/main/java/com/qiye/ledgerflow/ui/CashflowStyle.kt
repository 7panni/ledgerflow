package com.qiye.ledgerflow.ui

// Adapted from the user's CashFlowApp.kt; original source snapshot and changes:
// 项目记录/来源快照/0.1.2 and 项目记录/设计/0.1.2-现金流UI复用.md.
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qiye.ledgerflow.core.*
import com.qiyue.cashflow.shared.CivilDate
import com.qiyue.cashflow.shared.CoreForecastDay
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

val LedgerGreen = Color(0xFF173F34)
val ExpenseColor = Color(0xFFA64C41)
val IncomeColor = LedgerGreen
val Paper = Color(0xFFF4F0E8)
val Panel = Color(0xFFFAF8F3)
val SoftGreen = Color(0xFFD2E2DC)
val Ink = Color(0xFF202521)
val Muted = Color(0xFF77756F)
val Line = Color(0xFFCFD1CA)

@Composable fun CashflowTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = LedgerGreen, onPrimary = Color.White,
        primaryContainer = SoftGreen, onPrimaryContainer = LedgerGreen,
        secondary = LedgerGreen, onSecondary = Color.White,
        secondaryContainer = SoftGreen, onSecondaryContainer = LedgerGreen,
        tertiary = LedgerGreen, onTertiary = Color.White,
        error = ExpenseColor, onError = Color.White,
        errorContainer = Color(0xFFF0DDD7), onErrorContainer = Color(0xFF6F2D27),
        background = Paper, onBackground = Ink, surface = Panel, onSurface = Ink,
        surfaceVariant = Color(0xFFE9E6DE), onSurfaceVariant = Muted,
        outline = Color(0xFFBFC5BE), outlineVariant = Line,
        surfaceContainerLowest = Color.White, surfaceContainerLow = Panel,
        surfaceContainer = Panel, surfaceContainerHigh = Color(0xFFF0EDE6),
        surfaceContainerHighest = Color(0xFFE9E6DE),
    ), shapes = Shapes(
        extraSmall = RoundedCornerShape(3.dp), small = RoundedCornerShape(3.dp),
        medium = RoundedCornerShape(3.dp), large = RoundedCornerShape(18.dp),
        extraLarge = RoundedCornerShape(24.dp),
    ), content = content)
}

@Composable fun CashTopBar(destination: Int) = Row(
    Modifier.fillMaxWidth().background(Paper).statusBarsPadding().height(62.dp).padding(horizontal = 14.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
) {
    Column {
        Text(listOf("记账与现金流", "账目", "统计", "计划", "账户", "管理")[destination], fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, letterSpacing = (-.5).sp)
        if (destination != 0) Text(when(destination){1->"搜索、编辑与按日回顾";2->"按月、季度、年度查看收支";3->"固定、分期与未来收支";4->"点余额校准 · 计算器与转账";else->"分类、借贷、设置与数据"}, color = Muted, fontSize = 10.sp)
    }
    if (destination == 0) {
        val date = java.time.LocalDate.now()
        val weekday = listOf("一", "二", "三", "四", "五", "六", "日")[date.dayOfWeek.value - 1]
        Text("${date.monthValue}月${date.dayOfMonth}日 · 周$weekday", color = Muted, fontSize = 10.sp)
    }
}

@Composable fun CashBottomBar(selected: Int, onSelect: (Int) -> Unit) = Row(
    Modifier.fillMaxWidth().background(Panel).border(1.dp, Line).navigationBarsPadding().height(58.dp).padding(top = 4.dp),
    horizontalArrangement = Arrangement.SpaceAround,
) {
    listOf("首页", "账目", "统计", "计划", "账户", "管理").forEachIndexed { index, title ->
        Column(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(index) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(listOf("⌂", "≡", "◔", "▤", "¥", "⚙")[index], color = if (index == selected) LedgerGreen else Muted, fontSize = 17.sp)
            Text(title, color = if (index == selected) LedgerGreen else Muted, fontSize = 10.sp, fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable fun PageSectionHeader(title: String, trailing: @Composable () -> Unit = {}) = Row(
    Modifier.fillMaxWidth().heightIn(min = 28.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom,
) { Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp); trailing() }

@Composable fun PeriodSelector(selected: Int, onSelect: (Int) -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    listOf(30, 90, 180, 365).forEach { value ->
        Column(Modifier.clickable { onSelect(value) }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${value}天", color = if (value == selected) LedgerGreen else Muted, fontSize = 10.sp, fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Normal)
            Spacer(Modifier.width(24.dp).height(2.dp).background(if (value == selected) LedgerGreen else Color.Transparent))
        }
    }
}

@Composable fun ForecastMetricCell(label: String, value: String, modifier: Modifier, warn: Boolean = false) = Column(
    modifier.heightIn(min = 80.dp).background(Panel).border(.5.dp, Line).padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
) {
    Text(label, color = Muted, fontSize = 9.sp)
    Text(value, color = if (warn) ExpenseColor else Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 15.sp)
}

/** The same timeline is used for upcoming plans and actual entries; no balance logic lives here. */
data class CashflowBill(
    val id: String, val epochDay: Long, val name: String, val accountName: String,
    val amountMinor: Long, val isTransfer: Boolean = false, val detail: String = "", val currencyCode: String = "CNY",
)

@Composable fun UpcomingEventsCard(upcoming: List<CashflowBill>,onDelete:((String)->Unit)?=null,canDelete:(String)->Boolean={true},onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel).border(1.dp, Line)) {
        upcoming.forEachIndexed { index, event ->
            val date = CivilDate.fromEpochDay(event.epochDay)
            key(event.id){EntryActionRow(event.id,canDelete(event.id),{onSelect(event.id)},onDelete?.let{remove->{remove(event.id)}}){rowModifier->
            Row(rowModifier.fillMaxWidth().heightIn(min = 66.dp).padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(92.dp).height(66.dp)) {
                    if (index < upcoming.lastIndex) Box(Modifier.align(Alignment.BottomStart).offset(x = 18.dp).width(1.dp).height(34.dp).background(Line))
                    if (index > 0) Box(Modifier.align(Alignment.TopStart).offset(x = 18.dp).width(1.dp).height(28.dp).background(Line))
                    Box(Modifier.align(Alignment.CenterStart).offset(x = 13.dp).size(11.dp).background(if (event.amountMinor < 0) ExpenseColor else LedgerGreen, CircleShape).border(2.dp, Panel, CircleShape))
                    Row(Modifier.align(Alignment.CenterStart).padding(start = 34.dp), verticalAlignment = Alignment.Bottom) {
                        Text(date.day.toString(), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(3.dp))
                        Text("${date.month}月", color = Muted, fontSize = 9.sp, modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
                Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(event.name, Modifier.weight(1f, fill = false), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (event.accountName.isNotBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Text(event.accountName, color = Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (event.detail.isNotBlank()) Text(event.detail, color = Muted, fontSize = 9.sp, lineHeight = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(6.dp))
                Text(if (event.isTransfer) "↔ ${nativeMoney(event.amountMinor,event.currencyCode)}" else (if(event.amountMinor<0)"−" else "+")+nativeMoney(kotlin.math.abs(event.amountMinor),event.currencyCode), color = if (event.isTransfer) Ink else if (event.amountMinor < 0) ExpenseColor else LedgerGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            }}
            if (index < upcoming.lastIndex) HorizontalDivider(Modifier.padding(start = 36.dp), color = Line)
        }
    }
}

// Original chart geometry, grid, type sizes, colors and tap-to-select behavior are preserved.
@Composable fun ForecastChart(days: List<CoreForecastDay>, modifier: Modifier, onSelectedDay: (CoreForecastDay) -> Unit) {
    val values = days.map { it.cnyBalanceMinor.toFloat() }
    var selectedIndex by remember(days) { mutableStateOf<Int?>(null) }
    val rawMin = values.minOrNull() ?: 0f
    val rawMax = values.maxOrNull() ?: 0f
    // A constant balance needs a visible middle line and distinct axis ticks.
    val flatPadding = maxOf(100f, rawMin.absoluteValue * .05f)
    val min = if (rawMin == rawMax) rawMin - flatPadding else rawMin
    val max = if (rawMin == rawMax) rawMax + flatPadding else rawMax
    val yTicks = listOf(1f, .75f, .5f, .25f, 0f).map { fraction -> min + (max - min) * fraction }
    val range = (max - min).coerceAtLeast(1f)
    Column(modifier.semantics { contentDescription = "余额预测曲线，点按查看日期和金额；也可打开逐日明细。" }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(47.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                yTicks.forEach { tick -> Text(axisMoney(tick.toLong()), color = Muted, fontSize = 8.sp, maxLines = 1) }
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().pointerInput(days) {
                detectTapGestures { position ->
                    if (days.isNotEmpty() && size.width > 0) {
                        val index = (position.x / size.width * days.lastIndex.coerceAtLeast(1)).roundToInt().coerceIn(0, days.lastIndex)
                        selectedIndex = index
                        onSelectedDay(days[index])
                    }
                }
            }) {
                if (values.size < 2) return@Canvas
                listOf(0f, .25f, .5f, .75f, 1f).forEach { fraction ->
                    drawLine(Color.Gray.copy(alpha = .18f), androidx.compose.ui.geometry.Offset(0f, size.height * fraction), androidx.compose.ui.geometry.Offset(size.width, size.height * fraction), strokeWidth = 1f)
                    drawLine(Color.Gray.copy(alpha = .12f), androidx.compose.ui.geometry.Offset(size.width * fraction, 0f), androidx.compose.ui.geometry.Offset(size.width * fraction, size.height), strokeWidth = 1f)
                }
                if (min < 0 && max > 0) {
                    val zeroY = size.height - (0 - min) / range * size.height
                    drawLine(ExpenseColor.copy(alpha = .38f), androidx.compose.ui.geometry.Offset(0f, zeroY), androidx.compose.ui.geometry.Offset(size.width, zeroY), strokeWidth = 2f)
                }
                val p = Path()
                values.forEachIndexed { i, v ->
                    val x = size.width * i / values.lastIndex.coerceAtLeast(1)
                    val y = size.height - (v - min) / range * size.height
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                drawPath(p, color = if (values.any { it < 0 }) ExpenseColor else LedgerGreen, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                selectedIndex?.let { index ->
                    val x = size.width * index / values.lastIndex.coerceAtLeast(1)
                    val y = size.height - (values[index] - min) / range * size.height
                    val point = androidx.compose.ui.geometry.Offset(x, y)
                    drawLine(Color.Gray.copy(alpha = .45f), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 2f)
                    drawCircle(Color.White, radius = 8f, center = point)
                    drawCircle(LedgerGreen, radius = 5f, center = point)
                }
            }
        }
        if (days.isNotEmpty()) Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(47.dp))
            listOf(0, days.lastIndex / 4, days.lastIndex / 2, days.lastIndex * 3 / 4, days.lastIndex).forEach { index ->
                Text(monthDay(days[index].epochDay), Modifier.weight(1f), color = Color.Gray, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
        selectedIndex?.let { index -> Text("${LedgerCalendar.format(days[index].epochDay)} · ${cashMoney(days[index].cnyBalanceMinor)}", Modifier.fillMaxWidth(), color = LedgerGreen, fontWeight = FontWeight.SemiBold, fontSize = 10.sp) }
    }
}

fun cashMoney(amount: Long) = if (amount < 0) "−¥${Money.format(-amount)}" else "¥${Money.format(amount)}"
fun signedCashMoney(amount: Long) = (if (amount >= 0) "+" else "−") + cashMoney(amount.absoluteValue)
fun monthDay(epochDay: Long): String = CivilDate.fromEpochDay(epochDay).let { "${it.month}/${it.day}" }
private fun axisMoney(amount: Long): String {
    val value = amount / 100.0
    return if (value.absoluteValue >= 1000) "${if (value < 0) "−" else ""}${(value.absoluteValue / 1000).roundToInt()}k"
    else "${if (value < 0) "−" else ""}${value.absoluteValue.roundToInt()}"
}
