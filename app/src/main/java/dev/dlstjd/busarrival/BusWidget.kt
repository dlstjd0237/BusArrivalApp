package dev.dlstjd.busarrival

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.layout.Column
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class BusWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 네트워크는 워커와 탭에서만 돌고, 렌더는 저장값을 구독해 그린다.
        // (한 번 읽어 넘기면 update() 해도 세션이 살아있는 동안 값이 그대로라 갱신이 안 보인다)
        val initialTargets = loadTargets(context)
        val initialCache = loadCache(context)
        provideContent {
            val targets by targetsFlow(context).collectAsState(initial = initialTargets)
            val cache by cacheFlow(context).collectAsState(initial = initialCache)
            GlanceTheme {
                WidgetBody(targets, cache)
            }
        }
    }
}

@Composable
private fun WidgetBody(targets: List<TrackedRoute>, cache: Map<String, Arrival>) {
    val now = System.currentTimeMillis()
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(8.dp)
    ) {
        val fetchedAt = targets.firstNotNullOfOrNull { cache[it.key] }?.fetchedAtEpoch ?: 0L
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(
                fetchedAtText(fetchedAt, now),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                "↻ 새로고침",
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = GlanceModifier
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .clickable(actionRunCallback<WidgetRefreshAction>()),
            )
        }
        if (targets.isEmpty()) {
            Text("앱에서 정류장을 설정하세요", style = TextStyle(color = GlanceTheme.colors.onSurface))
            return@Column
        }
        // 노선이 위젯 높이보다 많을 수 있어 스크롤 가능한 목록으로
        LazyColumn(GlanceModifier.fillMaxSize()) {
            items(targets) { t ->
                Column(
                    GlanceModifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clickable(actionRunCallback<WidgetRefreshAction>())
                ) {
                    Text(
                        t.label,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    Text(
                        cache[t.key]?.firstText(now) ?: "-",
                        style = TextStyle(
                            color = GlanceTheme.colors.primary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }
        }
    }
}

class BusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BusWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        scheduleWidgetRefresh(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(WIDGET_WORK)
    }
}

/** 위젯 탭 = 즉시 갱신 */
class WidgetRefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val r = runCatching { refreshAndCache(context) }
        (r.exceptionOrNull()?.message ?: r.getOrNull()?.second)?.let { Log.w("BusWidget", "refresh failed: $it") }
        BusWidget().update(context, glanceId)
    }
}

class WidgetRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (inActiveWindow(LocalDateTime.now())) {
            // 실패해도 재시도하지 않는다(무한 재시도 금지). 위젯은 마지막 캐시값을 계속 보여준다.
            runCatching { refreshAndCache(applicationContext) }
        }
        runCatching { BusWidget().updateAll(applicationContext) }
        return Result.success()
    }
}

private const val WIDGET_WORK = "bus-widget-refresh"

fun scheduleWidgetRefresh(context: Context) {
    val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(WIDGET_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
}

// ponytail: 갱신 시간대는 이 상수 두 줄이 전부. 출퇴근 시간이 다르면 여기만 고친다.
private val MORNING = LocalTime.of(7, 0)..LocalTime.of(9, 30)
private val EVENING = LocalTime.of(17, 30)..LocalTime.of(19, 30)

/** 평일 출퇴근 시간대에만 위젯이 네트워크를 쓴다. 그 외에는 마지막 값 표시. */
internal fun inActiveWindow(at: LocalDateTime): Boolean {
    if (at.dayOfWeek == DayOfWeek.SATURDAY || at.dayOfWeek == DayOfWeek.SUNDAY) return false
    val t = at.toLocalTime()
    return t in MORNING || t in EVENING
}
