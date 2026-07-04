# 포챔스 서포터 앱 (Pokémon Champions Support App)

포켓몬 챔피언스(포챔스) 대전 중, 화면에 뜬 **상대 포켓몬 이름을 온디바이스 OCR 로 읽어**
그 포켓몬의 **타입 / 특성 / 주요 기술 / 방어 상성**을 게임 위에 오버레이로 실시간 표시하는
안드로이드 앱.

- **플랫폼**: 안드로이드 전용(오버레이+화면캡처는 안드로이드에서만 가능. iOS 불가).
- **서버 비용 0원**: 모든 처리가 온디바이스(OCR·매칭·DB 조회). 데이터는 앱 내장 JSON.
- **버전**: v0.1.0
- **패키지**: `com.pochamps.supporter`

> 문서: [DESIGN.md](DESIGN.md)(실현성/설계) · [PROGRESS.md](PROGRESS.md)(진행 이력 P1~P13) · [FIELD_TEST.md](FIELD_TEST.md)(실기기 검증 절차)

---

## ⚠️ 실기기 선검증(K1~K4)이 남아 있음

코드/유닛테스트/APK 산출은 완료됐지만, **런타임 성립 여부는 실기기에서만 판정된다.**
특히 **K1(FLAG_SECURE 캡처 차단)** 이 막히면 앱 자체가 불성립이다(우회 불가).
반드시 [FIELD_TEST.md](FIELD_TEST.md) 의 K1 절차부터(5분) 확인할 것.

---

## 아키텍처 요약 (컴포넌트 [1]~[7])

DESIGN.md 3장의 데이터 플로우. 포그라운드 서비스 안에서 한 사이클이 돈다:

```
[1] CaptureManager   MediaProjection → VirtualDisplay → ImageReader (다운스케일 프레임)
        │
[2] FrameGate        ROI 변화 감지(다운샘플 해시 diff) — 바뀔 때만 다음 단계 (발열/전력 절감)
        │
[3] RoiCropper       비율(0~1) ROI 크롭 + 2x 업스케일 (실패 시 상단 절반 fallback)
        │
[4] OcrEngine        ML Kit Text Recognition v2 (언어별 recognizer) → 이름 라인
        │
[5] NameMatcher      정규화 + candidate_index 완전일치 → 실패 시 Levenshtein fuzzy → 후보
        │
[6] LocalRepository  내장 JSON 조회 → 타입/특성/사용률 기술/종족값/메가 링크
        │
[7] OverlayRenderer  WindowManager(TYPE_APPLICATION_OVERLAY) + Compose 카드 (터치 통과, 드래그)
```

- **소형 창 전략**: 오버레이 창을 카드 bounds(WRAP_CONTENT)로만 유지 → 창 밖은 자동으로 게임에 터치 통과.
- **파이프라인**: conflate 채널로 최신 프레임만 워커(Dispatchers.Default)에 전달(backpressure).
- **순수 JVM 분리**: FrameGate/RoiConfig/NameMatcher/PipelineDecider/TypeChart/OverlayCardData 등
  Android 비의존 로직은 별도 클래스로 빼 Robolectric 없이 유닛 테스트(125 테스트).

### 소스 위치
```
android/app/src/main/java/com/pochamps/supporter/
  capture/   [1] CaptureManager · CaptureService(FGS) · [2] FrameGate · [3] RoiConfig/RoiCropper ·
             RecognitionPipeline · PipelineDecider
  ocr/       [4] OcrEngine
  matching/  [5] NameMatcher · MatchResult
  data/      [6] PokedexRepository · AssetsPokedexLoader · Pokedex/Usage/CandidateIndex/Localized ·
             TypeChart · AppSettings
  overlay/   [7] OverlayRenderer · OverlayCard · OverlayCardData · OverlayPosition
  ui/        MainActivity(온보딩 + 설정)
```

---

## 데이터 파이프라인 (`data/`)

앱에 내장되는 JSON 3종을 만드는 스크립트(스냅샷 방식 — 메타가 자주 안 바뀌므로 1회 수집).
소스: **op.gg**(포켓덱스 + 9언어 사전) + **championsbattledata**(실사용률).

| 산출물 | 내용 | 규모 |
|---|---|---|
| `pokedex_db.json` | 317종 × 9언어 이름/타입/특성/종족값/movepool + 타입·특성·기술 다국어 사전 + base↔메가 링크 | ~990KB |
| `usage_db.json` | 234종 × {싱글,더블} 실사용률(기술/아이템/특성/성격/EV/파트너) | ~1.8MB |
| `candidate_index.json` | 표시명 충돌 그룹(species root) + 언어별 `정규화이름→root` 조회 | ~170KB |

이 3종은 `android/app/src/main/assets/` 에 복사되어 APK 에 동봉된다.

### 재실행법 (메타 갱신 시)
반드시 **순서대로** 실행한다(뒤 스크립트가 앞 산출물을 조인):
```bash
cd data
python3 scrape_pokedex.py          # → pokedex_db.json  (op.gg 뼈대 + 9언어 사전)
python3 merge_usage.py             # → usage_db.json     (championsbattledata 사용률, pokedex 와 조인)
python3 build_candidate_index.py   # → candidate_index.json (표시명 충돌 그룹 + lookup)

# 갱신본을 앱 assets 로 복사
cp pokedex_db.json usage_db.json candidate_index.json ../android/app/src/main/assets/
```
> 소스 교체 시 각 스크립트의 어댑터(예: `OpggAdapter`)만 갈아끼우면 된다.

---

## 원격 데이터 갱신 (앱 재설치 없이 데이터만 교체) — P13

레귤레이션/메타가 바뀌면 앱을 재빌드·재설치하지 않고 **데이터만** 갱신할 수 있다
(DESIGN.md 4-6). 서버 연산 없음 — **정적 파일 호스팅 + manifest 버전 체크**로 서버비 0원.

### 동작 방식
- `data/build_release.py` 가 JSON 3종을 **gzip 압축** + `manifest.json`(dataVersion, 파일별 sha256/size)으로 패키징 → `data/dist/`.
- 이 `dist/` 폴더를 **GitHub Pages / Cloudflare Pages** 등 정적 호스팅에 올린다.
- 앱 설정의 **"데이터 업데이트"** 버튼을 누르면(수동 — v0.1 은 자동 백그라운드 체크 없음):
  manifest 조회 → `dataVersion` 비교 → 신규면 3종 다운로드(gzip 해제) + **sha256 검증** →
  `filesDir/db/` 에 **원자적 교체**(임시파일→rename, `version.txt` 커밋).
- 앱은 실행 시 `filesDir/db/` 에 유효본이 있으면 **우선 로드**, 없거나 손상이면 **assets 내장본으로 폴백**
  (오프라인·실패 안전). 네트워크는 표준 `HttpURLConnection`(신규 의존성 없음), 실패 시 조용히 기존본 유지.

### 운영 절차 (데이터 갱신 배포)
```bash
# 1) 데이터 재수집(위 "재실행법") 후, 배포 패키지 생성
cd data
python3 build_release.py                 # dataVersion = 오늘 날짜스탬프(YYYYMMDD)
#   또는 python3 build_release.py --version 42   # 정수 버전 명시
#   → data/dist/{pokedex_db,usage_db,candidate_index}.json.gz + manifest.json

# 2) dist/ 를 커밋하고 호스팅에 반영(아래 "GitHub Pages 로 dist/ 호스팅" 참조)
git add data/dist && git commit -m "data: release YYYYMMDD"

# 3) 앱에서 설정 → "데이터 업데이트" 버튼 → 신규 버전 다운로드
```
> `dataVersion` 비교는 **정수면 정수 비교, 아니면 사전순**(날짜스탬프 `YYYYMMDD` 도 사전순=시간순이라 안전).

### base URL 설정 (빌드 시)
`app/build.gradle.kts` 의 `DATA_UPDATE_BASE_URL` 이 갱신 대상 URL이다.
- **기본값은 빈 문자열 → 갱신 비활성**(내장본만 사용, 오프라인 안전). 미배포 상태에서도 안전하게 동작.
- 배포 시 `dist/` 를 호스팅한 URL 로 교체(끝에 `/` 포함). 예:
  ```kotlin
  buildConfigField("String", "DATA_UPDATE_BASE_URL",
      "\"https://<user>.github.io/<repo>/data/dist/\"")
  ```
  또는 CLI 오버라이드: `./gradlew :app:assembleRelease -PdataUpdateBaseUrl=https://.../data/dist/`

### GitHub Pages 로 dist/ 호스팅 (사용자 설정)
1. GitHub 에 저장소를 만들고 이 프로젝트를 push (원격 생성/push 는 별도로 진행).
2. GitHub → **Settings → Pages** → Source 를 `Deploy from a branch`, 브랜치 `main` / 폴더 `/ (root)` 로 설정.
3. 배포 후 `data/dist/manifest.json` 은 다음 URL 로 공개된다:
   `https://<user>.github.io/<repo>/data/dist/manifest.json`
4. 위 URL 의 **base 부분**(`.../data/dist/`)을 `DATA_UPDATE_BASE_URL` 에 넣고 릴리스 빌드.
5. 이후 데이터 갱신 = `build_release.py` 재실행 → `data/dist/` 커밋·push → Pages 자동 반영 → 앱에서 버튼.

> Cloudflare Pages 도 동일: 저장소 연결 후 출력 루트를 `/` 로 두면 `/data/dist/manifest.json` 가 그대로 서빙된다.
> HTTPS 가 기본이므로 릴리스 빌드에서 추가 설정 불필요(cleartext 는 디버그 E2E 테스트에서만 허용).

---

## 빌드

요구: Android SDK(platform 35, build-tools), JDK 17+. `android/local.properties` 에 `sdk.dir` 설정.

```bash
cd android

# 유닛 테스트(순수 JVM — 125 테스트)
./gradlew :app:testDebugUnitTest

# 디버그 APK (bundled ML Kit ~55MB, 로그/실측 편함)
./gradlew :app:assembleDebug
#   → app/build/outputs/apk/debug/app-debug.apk

# 릴리스 APK (R8 minify + arm64 단일 ABI, ~16.6MB, 디버그 키 서명)
./gradlew :app:assembleRelease
#   → app/build/outputs/apk/release/app-release.apk
```

- **릴리스 minify**: R8 on(`isMinifyEnabled`/`isShrinkResources`). keep 규칙은
  `android/app/proguard-rules.pro`(kotlinx-serialization 데이터 모델 + ML Kit).
- **서명**: v0.1.0 은 사이드로드 테스트 편의를 위해 디버그 키로 서명. Play 업로드 시 정식 키로 교체.

설치는 [FIELD_TEST.md](FIELD_TEST.md) (a) 참조.

---

## 권한 (AndroidManifest)
- `SYSTEM_ALERT_WINDOW` — 게임 위 오버레이.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` — 화면 캡처 FGS(Android 14+ 필수 조합).
- `POST_NOTIFICATIONS` — 캡처 중 상태바 알림(Android 13+).
- `INTERNET` — 원격 데이터 갱신(수동 버튼)의 manifest/gz 다운로드용. base URL 미설정 시 네트워크 호출 없음.

메모리 훅/자동입력 없음 — **화면에 공개된 정보만 표시**한다.

## 언어
- 앱 UI: 한국어(기본) + 영어(`values-en`). 데이터(포켓몬/타입/기술 이름): 9언어.
- 게임 언어는 설정에서 선택 → OCR recognizer + 표시/검색 언어에 일괄 연동.

---

## 문서
- [DESIGN.md](DESIGN.md) — 실현성 검토, 선검증 K1~K4, 기술 스택, 아키텍처, UI/UX, 온보딩.
- [PROGRESS.md](PROGRESS.md) — 페이즈별 진행 이력(P1 데이터 → P5 마무리)과 실기기 검증 체크리스트.
- [FIELD_TEST.md](FIELD_TEST.md) — 실기기 테스트 절차(K1 우선, ROI 보정, OCR 실측, 트러블슈팅).
