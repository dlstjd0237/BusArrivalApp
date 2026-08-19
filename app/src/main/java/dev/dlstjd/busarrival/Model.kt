package dev.dlstjd.busarrival

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 정류소 검색 결과 (getStationByName) */
data class BusStop(val stId: String, val stNm: String, val arsId: String)

/** 정류소를 지나는 노선 (getRouteByStation) */
data class BusRoute(
    val busRouteId: String,
    val busRouteNm: String,
    val typeName: String,
    val term: String,
)

/** 저장 대상. ord 를 들고 있는 게 이 앱의 전부다. */
data class TrackedRoute(
    val stopId: String,
    val stopName: String,
    val arsId: String,
    val routeId: String,
    val routeName: String,
    val ord: Int,
    val nextStopName: String = "",
    val direction: String = "",
) {
    val key: String get() = "$routeId@$stopId"
}

data class Arrival(
    val firstSeconds: Int?,
    val firstMessage: String,
    val secondSeconds: Int?,
    val secondMessage: String,
    val fetchedAtEpoch: Long,
)

/** "273번" / "9100인천" — 숫자만인 노선명에만 '번'을 붙인다. */
val TrackedRoute.label: String
    get() = if (routeName.isNotEmpty() && routeName.all { it.isDigit() }) "${routeName}번" else routeName

/** 이 시간이 지난 캐시는 카운트다운을 믿지 않는다. */
const val STALE_MS = 10 * 60 * 1000L

private fun elapsedSec(fetchedAtEpoch: Long, nowEpoch: Long) =
    ((nowEpoch - fetchedAtEpoch).coerceAtLeast(0L) / 1000L).toInt()

fun Arrival.isStale(nowEpoch: Long) = nowEpoch - fetchedAtEpoch > STALE_MS

/** 조회 시각 기준 남은 초. 정보 없음이면 null. */
fun Arrival.remainingSeconds(nowEpoch: Long): Int? =
    firstSeconds?.minus(elapsedSec(fetchedAtEpoch, nowEpoch))

/** "4분 12초" / "곧 도착" / API 원문 메시지("운행종료" 등) */
fun Arrival.firstText(nowEpoch: Long): String {
    if (isStale(nowEpoch)) return firstMessage.ifBlank { "갱신 필요" }
    val remain = remainingSeconds(nowEpoch) ?: return firstMessage.ifBlank { "정보 없음" }
    return formatDuration(remain)
}

/** "다음 13분" */
fun Arrival.secondText(nowEpoch: Long): String {
    if (isStale(nowEpoch)) return ""
    val remain = secondSeconds?.minus(elapsedSec(fetchedAtEpoch, nowEpoch))
        ?: return secondMessage.takeIf { it.isNotBlank() && it != firstMessage }?.let { "다음 $it" } ?: ""
    return if (remain <= 30) "다음 곧 도착" else "다음 ${(remain + 30) / 60}분"
}

fun formatDuration(seconds: Int): String = when {
    seconds <= 20 -> "곧 도착"
    seconds < 60 -> "${seconds}초"
    else -> "${seconds / 60}분 ${seconds % 60}초"
}

private val hms = DateTimeFormatter.ofPattern("HH:mm:ss")

/** "09:12:04 기준" — 오래된 값이면 "3분 전 기준"까지 붙인다. */
fun fetchedAtText(fetchedAtEpoch: Long, nowEpoch: Long): String {
    if (fetchedAtEpoch <= 0L) return "조회 전"
    val at = hms.format(Instant.ofEpochMilli(fetchedAtEpoch).atZone(ZoneId.systemDefault()))
    val agoSec = elapsedSec(fetchedAtEpoch, nowEpoch)
    return if (agoSec >= 60) "$at 기준 · ${agoSec / 60}분 전" else "$at 기준"
}

/** 서울시 노선 유형 코드 */
fun routeTypeName(code: String?): String = when (code) {
    "1" -> "공항"
    "2" -> "마을"
    "3" -> "간선"
    "4" -> "지선"
    "5" -> "순환"
    "6" -> "광역"
    "7" -> "인천"
    "8" -> "경기"
    "0", "9" -> "기타"
    else -> ""
}
