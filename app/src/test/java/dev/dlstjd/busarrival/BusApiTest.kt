package dev.dlstjd.busarrival

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class BusApiTest {

    private val arrivalXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <ServiceResult>
          <comMsgHeader/>
          <msgHeader><headerCd>0</headerCd><headerMsg>정상적으로 처리되었습니다.</headerMsg></msgHeader>
          <msgBody>
            <itemList>
              <stId>123000123</stId>
              <stNm>청량리역환승센터</stNm>
              <arsId>06123</arsId>
              <busRouteId>100100273</busRouteId>
              <rtNm>273</rtNm>
              <staOrd>25</staOrd>
              <traTime1>252</traTime1>
              <arrmsg1>4분12초후[2번째 전]</arrmsg1>
              <traTime2>780</traTime2>
              <arrmsg2>13분0초후[6번째 전]</arrmsg2>
            </itemList>
          </msgBody>
        </ServiceResult>
    """.trimIndent()

    @Test
    fun `도착정보 XML에서 필요한 필드만 뽑는다`() {
        val item = parseItems(arrivalXml).single()
        assertEquals("273", item["rtNm"])
        assertEquals("252", item["traTime1"])
        assertEquals("4분12초후[2번째 전]", item["arrmsg1"])
        assertEquals("780", item["traTime2"])
    }

    @Test
    fun `경유 정류소 목록에서 ord와 다음 정류장을 찾는다`() {
        val xml = """
            <ServiceResult>
              <msgHeader><headerCd>0</headerCd></msgHeader>
              <msgBody>
                <itemList><seq>24</seq><station>123000122</station><arsId>06122</arsId><stationNm>앞 정류장</stationNm></itemList>
                <itemList><seq>25</seq><station>123000123</station><arsId>06123</arsId><stationNm>청량리역환승센터</stationNm></itemList>
                <itemList><seq>26</seq><station>123000124</station><arsId>06124</arsId><stationNm>다음 정류장</stationNm></itemList>
              </msgBody>
            </ServiceResult>
        """.trimIndent()
        val items = parseItems(xml)
        val hit = items.first { it["station"] == "123000123" }
        assertEquals("25", hit["seq"])
        val next = items.first { it["seq"] == "26" }
        assertEquals("다음 정류장", next["stationNm"])
    }

    @Test
    fun `에러 헤더는 예외로 올라온다`() {
        val xml = """
            <ServiceResult>
              <msgHeader><headerCd>7</headerCd><headerMsg>일일 트래픽 초과</headerMsg></msgHeader>
            </ServiceResult>
        """.trimIndent()
        val e = runCatching { parseItems(xml) }.exceptionOrNull()
        assertTrue(e is BusApiException)
        assertEquals("일일 트래픽 초과", e?.message)
    }

    @Test
    fun `인증키 오류도 예외로 올라온다`() {
        val xml = """
            <OpenAPI_ServiceResponse>
              <cmmMsgHeader>
                <returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg>
              </cmmMsgHeader>
            </OpenAPI_ServiceResponse>
        """.trimIndent()
        assertTrue(runCatching { parseItems(xml) }.exceptionOrNull() is BusApiException)
    }

    @Test
    fun `깨진 응답에도 크래시하지 않고 예외 메시지를 준다`() {
        assertTrue(runCatching { parseItems("<html>503") }.exceptionOrNull() is BusApiException)
        assertTrue(runCatching { parseItems("") }.exceptionOrNull() is BusApiException)
    }

    @Test
    fun `남은 시간은 조회 시각 기준으로 흘러간다`() {
        val at = 1_700_000_000_000L
        val a = Arrival(252, "4분12초후[2번째 전]", 780, "13분0초후", at)
        assertEquals("4분 12초", a.firstText(at))
        assertEquals("3분 12초", a.firstText(at + 60_000))
        assertEquals("다음 13분", a.secondText(at))
        assertEquals("곧 도착", a.firstText(at + 250_000))
        assertEquals(2, a.remainingSeconds(at + 250_000))
    }

    @Test
    fun `정보 없음이면 API 원문 메시지를 그대로 보여준다`() {
        val at = 1_700_000_000_000L
        val a = Arrival(null, "운행종료", null, "", at)
        assertEquals("운행종료", a.firstText(at))
        assertEquals("", a.secondText(at))
        assertNull(a.remainingSeconds(at))
    }

    @Test
    fun `오래된 캐시는 카운트다운하지 않는다`() {
        val at = 1_700_000_000_000L
        val a = Arrival(252, "4분12초후", 780, "13분", at)
        val later = at + STALE_MS + 1
        assertTrue(a.isStale(later))
        assertEquals("4분12초후", a.firstText(later))
        assertTrue(fetchedAtText(at, later).contains("분 전"))
    }

    @Test
    fun `위젯은 평일 출퇴근 시간대에만 네트워크를 쓴다`() {
        assertTrue(inActiveWindow(LocalDateTime.of(2026, 8, 19, 8, 10)))   // 수요일 아침
        assertTrue(inActiveWindow(LocalDateTime.of(2026, 8, 19, 18, 0)))   // 수요일 저녁
        assertTrue(!inActiveWindow(LocalDateTime.of(2026, 8, 19, 13, 0)))  // 점심
        assertTrue(!inActiveWindow(LocalDateTime.of(2026, 8, 22, 8, 10)))  // 토요일
    }

    @Test
    fun `노선 유형 코드를 이름으로 바꾼다`() {
        assertEquals("간선", routeTypeName("3"))
        assertEquals("지선", routeTypeName("4"))
        assertEquals("", routeTypeName(null))
    }
}
