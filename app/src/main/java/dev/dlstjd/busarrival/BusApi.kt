package dev.dlstjd.busarrival

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory

/** 서울시 버스정보 오픈API (비상업 이용 한정) */
private const val BASE = "http://ws.bus.go.kr/api/rest"

class BusApiException(message: String) : Exception(message)

/**
 * 사용자가 설정 화면에서 넣은 공공데이터포털 인증키. 앱 진입 시 Prefs 에서 채운다.
 * 비어 있으면 빌드에 포함된 키(BuildConfig)를 쓴다. 배포용 APK 는 빌드 키 없이 나가므로
 * 받은 사람은 자기 키를 넣어 쓴다.
 */
@Volatile
var userApiKey: String = ""

// ---------- 공개 API ----------

suspend fun searchStops(query: String): List<BusStop> =
    call("stationinfo/getStationByName", "stSrch" to query)
        .mapNotNull { m ->
            val id = m["stId"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            BusStop(id, m["stNm"].orEmpty(), m["arsId"].orEmpty())
        }
        .distinctBy { it.stId }

suspend fun routesAtStop(arsId: String): List<BusRoute> =
    call("stationinfo/getRouteByStation", "arsId" to arsId)
        .mapNotNull { m ->
            val id = (m["busRouteId"] ?: m["routeId"])?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            BusRoute(
                busRouteId = id,
                busRouteNm = (m["busRouteNm"] ?: m["rtNm"] ?: m["busRouteAbrv"]).orEmpty(),
                typeName = routeTypeName(m["busRouteType"] ?: m["routeType"]),
                term = m["term"].orEmpty(),
            )
        }
        .distinctBy { it.busRouteId }
        .sortedBy { it.busRouteNm }

private data class RouteStation(val seq: Int, val stId: String, val arsId: String, val name: String, val direction: String)

/**
 * 노선의 경유 정류소 목록에서 ord(순번)·다음 정류장·방면을 뽑는다. 온보딩에서 딱 한 번 호출한다.
 * stId 우선, 없으면 arsId 로 매칭 (상·하행 같은 이름 정류장 혼동 방지).
 */
suspend fun resolveOrd(routeId: String, stopId: String, arsId: String): Triple<Int, String, String> {
    // 서울시 API의 실제 오퍼레이션명에 오타가 있다(getStaionByRoute). 언젠가 고쳐질 경우를 대비해 한 번만 재시도.
    val items = try {
        call("busRouteInfo/getStaionByRoute", "busRouteId" to routeId)
    } catch (e: BusApiException) {
        call("busRouteInfo/getStationByRoute", "busRouteId" to routeId)
    }
    val stations = items.mapNotNull { m ->
        val seq = m["seq"]?.trim()?.toIntOrNull() ?: return@mapNotNull null
        RouteStation(seq, m["station"].orEmpty(), m["arsId"].orEmpty(), m["stationNm"].orEmpty(), m["direction"].orEmpty())
    }
    val hit = stations.firstOrNull { it.stId == stopId }
        ?: stations.firstOrNull { arsId.isNotBlank() && it.arsId == arsId }
        ?: throw BusApiException("이 노선의 경유 정류소 목록에서 해당 정류장을 찾지 못했습니다")
    val next = stations.firstOrNull { it.seq == hit.seq + 1 }?.name.orEmpty()
    return Triple(hit.seq, next, hit.direction)
}

suspend fun fetchArrival(t: TrackedRoute): Arrival {
    val m = call(
        "arrive/getArrInfoByRoute",
        "stId" to t.stopId, "busRouteId" to t.routeId, "ord" to t.ord.toString(),
    ).firstOrNull() ?: throw BusApiException("도착 정보가 비어 있습니다")
    return Arrival(
        firstSeconds = m["traTime1"]?.trim()?.toIntOrNull()?.takeIf { it > 0 },
        firstMessage = m["arrmsg1"].orEmpty().trim(),
        secondSeconds = m["traTime2"]?.trim()?.toIntOrNull()?.takeIf { it > 0 },
        secondMessage = m["arrmsg2"].orEmpty().trim(),
        fetchedAtEpoch = System.currentTimeMillis(),
    )
}

// ---------- 내부 ----------

private suspend fun call(path: String, vararg params: Pair<String, String>): List<Map<String, String>> =
    parseItems(httpGet(buildUrl(path, params.toMap())))

private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

internal fun buildUrl(path: String, params: Map<String, String>): String {
    val key = userApiKey.ifBlank { BuildConfig.BUS_API_KEY }
    if (key.isBlank()) throw BusApiException("인증키가 없습니다. 설정 화면에서 공공데이터포털 인증키를 넣어주세요.")
    // 포털의 Encoding 키를 그대로 붙여넣은 경우 이중 인코딩 방지
    val serviceKey = if (key.contains('%')) key else enc(key)
    val query = params.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
    return "$BASE/$path?serviceKey=$serviceKey&$query"
}

private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5_000
        readTimeout = 5_000
        requestMethod = "GET"
    }
    try {
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw BusApiException("서버 응답 오류 ($code)")
        body
    } finally {
        conn.disconnect()
    }
}

/**
 * <itemList> 를 태그명->텍스트 맵으로. 에러 헤더면 예외.
 * XML 라이브러리를 더 붙일 만한 응답이 아니고, javax.xml 은 안드로이드/JVM 양쪽에 있어 단위 테스트가 그냥 된다.
 */
internal fun parseItems(xml: String): List<Map<String, String>> {
    if (xml.isBlank()) throw BusApiException("빈 응답")
    val doc = try {
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // 외부 엔티티 차단 (지원 안 하는 파서면 무시)
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(xml.byteInputStream())
    } catch (e: Exception) {
        throw BusApiException("응답을 해석하지 못했습니다: ${xml.take(120)}")
    }

    fun text(tag: String): String? = doc.getElementsByTagName(tag).item(0)?.textContent?.trim()

    // 서울 API 헤더 / 공공데이터포털 공통 에러
    val headerCd = text("headerCd")
    if (headerCd != null && headerCd != "0") {
        throw BusApiException(text("headerMsg") ?: "API 오류 ($headerCd)")
    }
    text("returnAuthMsg")?.let { throw BusApiException(it) }
    val resultCode = text("resultCode")
    if (resultCode != null && resultCode.trimStart('0').isNotEmpty()) {
        throw BusApiException(text("resultMsg") ?: "API 오류 ($resultCode)")
    }

    val nodes = doc.getElementsByTagName("itemList")
    return (0 until nodes.length).map { i ->
        val item = nodes.item(i)
        buildMap {
            val children = item.childNodes
            for (j in 0 until children.length) {
                (children.item(j) as? Element)?.let { put(it.tagName, it.textContent.orEmpty().trim()) }
            }
        }
    }
}
