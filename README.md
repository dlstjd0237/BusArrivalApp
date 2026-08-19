# 버스 도착 알림 앱

앱을 켜면(또는 홈 화면 위젯을 보면) 바로 남은 시간이 뜬다. 서울시 버스 오픈API 사용, 비상업 개인용.

```
273번
청량리역환승센터 (06123)

  4분 12초
  다음 13분

09:12:04 기준 · 자동갱신
```

## 이 앱의 핵심

서울 도착정보 API(`getArrInfoByRoute`)는 `ord`(그 노선에서 정류장의 순번)를 요구한다.
**설정할 때 한 번만** 노선의 경유 정류소 목록을 받아 `ord`를 계산해 저장해두므로,
이후 매일 아침 동작은 **HTTP GET 1번 + 파싱 1번**이 전부다.

## 1. 준비: 공공데이터포털 활용신청

[data.go.kr](https://www.data.go.kr)에서 3개 서비스 신청 (자동승인, 키 반영에 시간이 걸릴 수 있음)

| 서비스 | 쓰는 곳 |
|---|---|
| 서울특별시_정류소정보조회 (#15000303) | 정류장 이름 검색 / 경유 노선 목록 |
| 서울특별시_노선정보조회 (#15000193) | `ord` 산출 |
| 서울특별시_버스도착정보조회 (#15000314) | 도착 예정 시간 |

## 2. 빌드

```bash
cp local.properties.example local.properties
# sdk.dir 와 DATA_GO_KR_KEY(Decoding 키)를 채운다. 이 파일은 git에 올라가지 않는다.
./gradlew testDebugUnitTest   # 파서/포맷/스케줄 단위 테스트
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

Decoding 키를 넣으면 앱이 알아서 URL 인코딩한다. Encoding 키(`%` 포함)를 넣어도 이중 인코딩 없이 그대로 쓴다.

## 3. 사용

1. 첫 실행 → 정류장 이름 검색 → 정류장 선택 → 경유 노선 선택
2. **방향 확인 다이얼로그**에서 "다음 정류장"을 보고 방향이 맞는지 확인 후 추가 (상·하행 혼동 방지)
3. 여러 노선을 추가할 수 있고, "완료"를 누르면 홈 화면으로
4. 홈: 20초 주기 자동 갱신(화면 꺼지면 중단), 화면 탭 = 즉시 갱신, "설정"으로 재설정
5. 위젯: 홈 화면에 2x1 위젯 추가. 평일 07:00~09:30 / 17:30~19:30 에 15분 주기 갱신, 탭하면 즉시 갱신

## 4. 릴리스 (GitHub Actions)

- `main` push → 디버그 APK 아티팩트
- `v1.0.0` 태그 push → 서명 릴리스 APK + GitHub Release 자동 첨부 (`versionCode` = run number, `versionName` = 태그)

필요한 Secrets: `DATA_GO_KR_KEY`, `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

```bash
keytool -genkey -v -keystore bus.jks -keyalg RSA -keysize 2048 -validity 10000 -alias bus
openssl base64 -in bus.jks | tr -d '\n'   # -> KEYSTORE_BASE64
```

> 키스토어를 잃어버리면 같은 앱으로 업데이트 설치가 불가하다. 별도 백업 필수.

## 5. 코드 구조

```
Model.kt        데이터 클래스 + 순수 포맷 함수 (테스트 대상)
BusApi.kt       HTTP GET 4종 + XML 파서
Prefs.kt        DataStore 저장/캐시 + refreshAndCache()
MainActivity.kt Compose 화면 2개 (설정 / 홈)
BusWidget.kt    Glance 위젯 + WorkManager 주기 갱신
```

Repository/UseCase 레이어, DI, Retrofit, JSON 라이브러리 없음. 구현체 하나짜리 인터페이스도 없음.

## 6. 계획서와 다른 점

- **HTTP 클라이언트: OkHttp 대신 `HttpURLConnection`.** 안드로이드의 `HttpURLConnection`은 내부적으로 OkHttp로 구현돼 있어 엔진은 같은데 의존성과 APK 용량만 줄어든다. GET 4개에 클라이언트 라이브러리를 붙일 이유가 없었다. 되돌리려면 `httpGet()` 한 함수만 갈아끼우면 된다.
- **XML 파싱: `XmlPullParser` 대신 `javax.xml`(DOM).** 둘 다 무의존성이지만 `javax.xml`은 JVM에도 있어서 파서 단위 테스트가 로보렉트릭 없이 그냥 돌아간다. 응답이 수십 KB라 DOM으로 충분하다.
- **여러 노선 선택(시나리오 2.1)을 P0으로 구현.** 저장 구조가 리스트라 F-07(프리셋)은 이 위에 올리면 된다.

## 7. 알아둘 것

- 서울 버스 오픈API는 **상업적 이용 불가**. 스토어 유료 배포 금지, 개인용으로 범위 고정.
- 개발계정 트래픽 **1,000건/일**. 화면 비활성 시 폴링 중단 + 15초 미만 재조회 차단 + 위젯 시간대 제한으로 보호한다. 노선 1개 기준 앱을 하루 20분 켜두면 약 60건.
- API 장애/네트워크 실패 시 마지막 캐시값과 "N분 전"을 표시하고 재시도하지 않는다. 10분 지난 캐시는 카운트다운을 멈추고 원문 메시지를 보여준다.
- 인증키는 APK를 디컴파일하면 노출된다. 배포 범위를 넓힐 계획이 생기면 프록시를 한 겹 둘 것.
