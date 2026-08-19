package dev.dlstjd.busarrival

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore("bus")
private val TARGETS = stringPreferencesKey("targets")
private val CACHE = stringPreferencesKey("cache")

suspend fun loadTargets(ctx: Context): List<TrackedRoute> = parseTargets(ctx.dataStore.data.first()[TARGETS])

/** 위젯은 이 Flow 를 구독해서 저장값이 바뀌면 바로 다시 그린다. */
fun targetsFlow(ctx: Context): Flow<List<TrackedRoute>> = ctx.dataStore.data.map { parseTargets(it[TARGETS]) }

private fun parseTargets(raw: String?): List<TrackedRoute> {
    if (raw == null) return emptyList()
    return runCatching {
        JSONArray(raw).map { o ->
            TrackedRoute(
                stopId = o.getString("stopId"),
                stopName = o.getString("stopName"),
                arsId = o.optString("arsId"),
                routeId = o.getString("routeId"),
                routeName = o.getString("routeName"),
                ord = o.getInt("ord"),
                nextStopName = o.optString("nextStopName"),
                direction = o.optString("direction"),
            )
        }
    }.getOrDefault(emptyList())
}

suspend fun saveTargets(ctx: Context, targets: List<TrackedRoute>) {
    val json = JSONArray().apply {
        targets.forEach { t ->
            put(
                JSONObject()
                    .put("stopId", t.stopId).put("stopName", t.stopName).put("arsId", t.arsId)
                    .put("routeId", t.routeId).put("routeName", t.routeName)
                    .put("ord", t.ord).put("nextStopName", t.nextStopName)
                    .put("direction", t.direction)
            )
        }
    }
    ctx.dataStore.edit { it[TARGETS] = json.toString() }
}

/** 마지막 성공 응답. 앱을 켜자마자 보여줄 값이자, 네트워크 실패 시 보여줄 값. */
suspend fun loadCache(ctx: Context): Map<String, Arrival> = parseCache(ctx.dataStore.data.first()[CACHE])

fun cacheFlow(ctx: Context): Flow<Map<String, Arrival>> = ctx.dataStore.data.map { parseCache(it[CACHE]) }

private fun parseCache(raw: String?): Map<String, Arrival> {
    if (raw == null) return emptyMap()
    return runCatching {
        JSONArray(raw).associate { o ->
            o.getString("key") to Arrival(
                firstSeconds = o.optInt("first", -1).takeIf { it >= 0 },
                firstMessage = o.optString("firstMsg"),
                secondSeconds = o.optInt("second", -1).takeIf { it >= 0 },
                secondMessage = o.optString("secondMsg"),
                fetchedAtEpoch = o.optLong("at"),
            )
        }
    }.getOrDefault(emptyMap())
}

suspend fun saveCache(ctx: Context, cache: Map<String, Arrival>) {
    val json = JSONArray().apply {
        cache.forEach { (key, a) ->
            put(
                JSONObject()
                    .put("key", key)
                    .put("first", a.firstSeconds ?: -1).put("firstMsg", a.firstMessage)
                    .put("second", a.secondSeconds ?: -1).put("secondMsg", a.secondMessage)
                    .put("at", a.fetchedAtEpoch)
            )
        }
    }
    ctx.dataStore.edit { it[CACHE] = json.toString() }
}

/**
 * 저장된 노선을 전부 조회해 캐시에 반영. 실패한 노선은 이전 캐시값을 그대로 둔다(화면이 비지 않게).
 * @return 갱신된 캐시와 첫 에러 메시지(전부 성공이면 null)
 */
suspend fun refreshAndCache(ctx: Context): Pair<Map<String, Arrival>, String?> {
    val targets = loadTargets(ctx)
    val cache = loadCache(ctx).toMutableMap()
    var error: String? = null
    for (t in targets) {
        try {
            cache[t.key] = fetchArrival(t)
        } catch (e: Exception) {
            if (error == null) error = e.message ?: "조회 실패"
        }
    }
    // 저장된 노선에서 빠진 캐시는 버린다
    val keys = targets.map { it.key }.toSet()
    val pruned = cache.filterKeys { it in keys }
    saveCache(ctx, pruned)
    return pruned to error
}

private inline fun <T> JSONArray.map(transform: (JSONObject) -> T): List<T> =
    (0 until length()).map { transform(getJSONObject(it)) }

private inline fun <K, V> JSONArray.associate(transform: (JSONObject) -> Pair<K, V>): Map<K, V> =
    (0 until length()).associate { transform(getJSONObject(it)) }
