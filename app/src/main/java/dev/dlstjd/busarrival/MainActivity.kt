package dev.dlstjd.busarrival

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 화면이 켜져 있는 동안의 폴링 주기. onStop 이면 멈춘다(일일 한도 보호). */
private const val POLL_MS = 20_000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding()) { App() }
                }
            }
        }
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    var targets by remember { mutableStateOf<List<TrackedRoute>?>(null) }
    var editing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        userApiKey = loadApiKey(ctx)
        val loaded = loadTargets(ctx)
        targets = loaded
        editing = loaded.isEmpty()
    }

    val current = targets
    when {
        current == null -> Centered("불러오는 중…")
        editing -> SetupScreen(current) { saved ->
            targets = saved
            editing = false
        }
        else -> HomeScreen(current) { editing = true }
    }
}

@Composable
private fun Centered(text: String) =
    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) { Text(text) }

// ---------------- 화면 B: 홈 ----------------

@Composable
private fun HomeScreen(targets: List<TrackedRoute>, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var cache by remember { mutableStateOf<Map<String, Arrival>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastFetchAt by remember { mutableLongStateOf(0L) }

    // minGapMs 는 자동 폴링 전용 throttle. 사용자가 직접 누른 새로고침은 0을 넘겨 무조건 조회한다.
    suspend fun refresh(minGapMs: Long) {
        if (minGapMs > 0 && System.currentTimeMillis() - lastFetchAt < minGapMs) return
        lastFetchAt = System.currentTimeMillis()
        loading = true
        val (updated, err) = refreshAndCache(ctx)
        cache = updated
        error = err
        loading = false
        now = System.currentTimeMillis()
        runCatching { BusWidget().updateAll(ctx) }
    }

    // 진입 즉시 캐시부터 그린다 (콜드 스타트 체감 속도)
    LaunchedEffect(Unit) { cache = loadCache(ctx) }

    LaunchedEffect(targets) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(1_000)
                }
            }
            launch {
                while (true) {
                    refresh(15_000)
                    delay(POLL_MS)
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .clickable { scope.launch { refresh(0) } }
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.End) {
            TextButton(onClick = { scope.launch { refresh(0) } }) { Text("↻ 새로고침") }
            TextButton(onClick = onSettings) { Text("설정") }
        }
        targets.forEach { t ->
            ArrivalCard(t, cache[t.key], now)
            Spacer(Modifier.height(16.dp))
        }
        val fetchedAt = targets.firstNotNullOfOrNull { cache[it.key] }?.fetchedAtEpoch ?: 0L
        Text(
            fetchedAtText(fetchedAt, now) + if (loading) " · 갱신 중" else " · 자동갱신",
            style = MaterialTheme.typography.bodySmall,
        )
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text("화면을 탭하면 즉시 새로고침", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ArrivalCard(t: TrackedRoute, arrival: Arrival?, now: Long) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(t.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                t.stopName + if (t.arsId.isNotBlank()) " (${t.arsId})" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                arrival?.firstText(now) ?: "조회 중…",
                fontSize = 46.sp,
                fontWeight = FontWeight.Bold,
            )
            val second = arrival?.secondText(now).orEmpty()
            if (second.isNotBlank()) {
                Text(second, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

// ---------------- 화면 A: 설정 ----------------

@Composable
private fun SetupScreen(initial: List<TrackedRoute>, onDone: (List<TrackedRoute>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var stops by remember { mutableStateOf<List<BusStop>>(emptyList()) }
    var stop by remember { mutableStateOf<BusStop?>(null) }
    var routes by remember { mutableStateOf<List<BusRoute>>(emptyList()) }
    var picked by remember { mutableStateOf(initial) }
    var confirm by remember { mutableStateOf<TrackedRoute?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var apiKey by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { apiKey = loadApiKey(ctx) }

    fun work(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (e: Exception) {
                error = e.message ?: "실패"
            } finally {
                busy = false
            }
        }
    }

    fun search() = work {
        saveApiKey(ctx, apiKey)
        stop = null
        routes = emptyList()
        stops = searchStops(query.trim())
        if (stops.isEmpty()) error = "검색 결과가 없습니다"
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text("정류장 · 노선 설정", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("공공데이터포털 인증키 (Decoding)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("data.go.kr 에서 서울시 정류소·노선·도착정보 3종을 활용신청하면 받는 키") },
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("정류장 이름") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && query.isNotBlank(), onClick = { search() }) { Text("검색") }
            if (picked.isNotEmpty()) {
                Button(onClick = {
                    scope.launch {
                        saveApiKey(ctx, apiKey)
                        saveTargets(ctx, picked)
                        scheduleWidgetRefresh(ctx)
                        runCatching { BusWidget().updateAll(ctx) }
                        onDone(picked)
                    }
                }) { Text("완료 (${picked.size})") }
            }
        }

        if (busy) {
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator()
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        if (picked.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("트래킹 목록", style = MaterialTheme.typography.titleSmall)
            picked.forEach { t ->
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("${t.routeName} · ${t.stopName} (ord ${t.ord})", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { picked = picked - t }) { Text("삭제") }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()

        val selected = stop
        LazyColumn(Modifier.fillMaxSize()) {
            if (selected == null) {
                items(stops) { s ->
                    ListRow(s.stNm + if (s.arsId.isNotBlank()) "  (${s.arsId})" else "") {
                        work {
                            stop = s
                            routes = routesAtStop(s.arsId)
                            if (routes.isEmpty()) error = "이 정류장의 경유 노선을 가져오지 못했습니다"
                        }
                    }
                }
            } else {
                item {
                    Text(
                        "${selected.stNm} (${selected.arsId}) 경유 노선",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                items(routes) { r ->
                    val term = if (r.term.isNotBlank()) " · 배차 ${r.term}분" else ""
                    ListRow("${r.busRouteNm}  ${r.typeName}$term") {
                        work {
                            val (ord, next, direction) = resolveOrd(r.busRouteId, selected.stId, selected.arsId)
                            confirm = TrackedRoute(
                                stopId = selected.stId,
                                stopName = selected.stNm,
                                arsId = selected.arsId,
                                routeId = r.busRouteId,
                                routeName = r.busRouteNm,
                                ord = ord,
                                nextStopName = next,
                                direction = direction,
                            )
                        }
                    }
                }
            }
        }
    }

    // 상·하행 혼동이 이 앱의 1순위 리스크라 저장 전에 다음 정류장을 보여주고 확인받는다
    confirm?.let { t ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("${t.routeName}번 · 방향 확인") },
            text = {
                Text(
                    "정류장: ${t.stopName} (${t.arsId})\n" +
                        "방면: ${t.direction.ifBlank { "정보 없음" }}\n" +
                        "다음 정류장: ${t.nextStopName.ifBlank { "정보 없음" }}\n" +
                        "순번(ord): ${t.ord}\n\n" +
                        "가려는 방향이 맞나요?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (picked.none { it.key == t.key }) picked = picked + t
                    confirm = null
                }) { Text("추가") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun ListRow(text: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
    }
}
