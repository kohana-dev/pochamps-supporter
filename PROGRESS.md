# 진행 상황 (PROGRESS)

> 프로젝트: 포챔스 서포터 앱 (안드로이드 Kotlin) · 설계 근거: [DESIGN.md](DESIGN.md)

---

## P1 — 스캐폴드 + 데이터 레이어 ✅ 완료 (2026-07-05)

### 완료 내역

**1. Gradle 프로젝트 구조** (`android/`)
- Kotlin + Jetpack Compose, `minSdk 26` / `targetSdk 35` / `compileSdk 35`.
- Gradle Kotlin DSL(`build.gradle.kts`) + Version Catalog(`gradle/libs.versions.toml`).
- Gradle Wrapper 8.14, AGP 8.5.2, Kotlin 2.0.20, JVM toolchain 17.
- 의존성: Compose BOM 2024.09.03, Material3, kotlinx-serialization-json 1.7.3,
  ML Kit Text Recognition Korean 16.0.1(v1은 korean+latin 번들; 9언어 확장은 P2).

**2. AndroidManifest** (`app/src/main/AndroidManifest.xml`)
- 권한: `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `POST_NOTIFICATIONS`.
- `CaptureService`를 `foregroundServiceType="mediaProjection"`로 선언(현재는 스텁).
- DESIGN.md 2장의 Android 14/15 제약을 주석으로 명시(오버레이 먼저→FGS 시작 순서 등).

**3. 데이터 레이어 (핵심 산출물)**
- `data/` JSON 3종을 `app/src/main/assets/`로 복사(총 ~2.9MB, APK에 정상 포함 확인).
- **실제 JSON 키를 python으로 확인한 뒤** kotlinx-serialization 데이터 클래스 작성(추측 금지):
  - `Pokedex.kt` — `PokedexDb`, `PokemonEntry`(dex/key/names/types/abilities/base_stats/moves/메가링크),
    `BaseStats`, `PokedexDict`(types/abilities/moves 다국어 사전), `TypeInfo`(color 포함)/`DictEntry`/`MoveInfo`.
  - `Usage.kt` — `UsageDb`, `UsageEntry`(doubles/singles), `FormatUsage`(moves/items/abilities/natures/spreads/teammates),
    `UsageStat`(name/pct/slug), `Teammate`, `BattleFormat` enum.
  - `CandidateIndex.kt` — `CandidateIndex`, `SpeciesGroup`, `Candidate`(usage_rank/types/names),
    `lookup: Map<lang, Map<정규화이름, root>>`.
  - `Localized.kt` — `LocalizedNames`(9언어; `zh-cn`/`zh-tw`는 `@SerialName`), `SUPPORTED_LANGUAGES`.
- `PokedexRepository`(`[6] LocalRepository`) — 순수 JVM(파싱만). assets 로드는 얇은 어댑터
  `AssetsPokedexLoader`가 담당(로직/Android 분리 → 테스트 가능).
  - key로 포켓몬 조회, slug→다국어 이름/색상 해석(type/ability/move),
    메가 링크 접근(`canMega`/`megaFormsOf`/`baseFormOf`),
    포맷별 사용률 상위 N 기술(`topMoves`, 메가는 base movepool 재사용).
- `NameMatcher`(`[5]`) — **Android 의존성 없는 순수 JVM 클래스**.
  정규화(letterOrDigit만 남기고 소문자화; 한/일/중/라틴 보존) + `candidate_index.lookup` 완전일치 →
  실패 시 Levenshtein 편집거리 fuzzy 매칭(길이 비례 상한, 조기중단) → root의 후보를 usage_rank 내림차순 반환.
  결과는 `MatchResult.Matched`(후보 1개=바로 표시, 2+=선택 UI) / `NoMatch`(수동 검색 fallback).

**4. 유닛 테스트** (`app/src/test/`, 순수 JVM — Robolectric 불필요)
- `NameMatcherTest`(10): 정규화, Levenshtein 기본/조기중단, 정확매칭(한/영), 1글자 오타 fuzzy,
  무관문자열/빈문자열 미매칭, 충돌그룹(윈디→arcanine, 후보 2+, usage_rank 정렬) 검증.
- `PokedexRepositoryTest`(9): 3파일 로드(count 317/234), garchomp 조회(dex 445/타입/특성/종족값합 600),
  메가 링크 왕복(garchomp↔mega-garchomp), 리자몽 2메가(X/Y), slug→이름/색상 해석,
  topMoves 정렬(더블 1위 dragon-claw), 메가=base 사용률 재사용, 싱글≠더블.
- `TestData.kt`: assets의 실 JSON을 파일시스템에서 읽어 Repository 생성.

### 빌드 검증 결과 (실제 실행 — 로컬 Android SDK 존재)
- 환경: `~/Library/Android/sdk`(platform 33~36, build-tools 34~36), 캐시된 gradle 8.14, JDK 22.
- `./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL**, **19/19 통과**(failures=0, errors=0).
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**, `app-debug.apk` 12MB 생성.
- APK 내 assets 3종 JSON 포함 확인, 병합 매니페스트에 mediaProjection 서비스/권한 정상 반영 확인.
- 경고(무해): AGP 8.5.2가 compileSdk 35를 "tested up to 34"로 경고 → P2에서 AGP 8.6+로 올리면 해소.

### 미검증 / 열린 항목
- **온디바이스 실행 미검증**: 실기기/에뮬레이터 설치·구동은 안 함(P1 범위 밖). ML Kit OCR 실동작도 P2.
- **표시명 문자열**: candidate_index는 현재 op.gg 이름을 프록시로 사용 → 실 배틀화면 문자열은 **K2(실기기 스크린샷)**로 확정 필요.
- **선검증 K1~K4 미수행**: 특히 K1(FLAG_SECURE)이 통과해야 앱 자체가 성립(DESIGN.md 1장). 코드와 무관하게 최우선.

---

## P2 — 오버레이 셸 + 권한 온보딩 + FGS ✅ 완료 (2026-07-05)

> DESIGN.md 로드맵 3단계("오버레이 셸: WindowManager 오버레이 + 드래그 + 터치통과 + FGS").
> 캡처→OCR 실프레임 처리는 P3 로 넘김(세션 확보/해제까지만 구현).

### 완료 내역

**1. `[7] OverlayRenderer` 실구현** (`overlay/`)
- `WindowManager` + `TYPE_APPLICATION_OVERLAY`(pre-O 는 `TYPE_PHONE` fallback)에
  `ComposeView` 를 붙여 렌더. Service 컨텍스트엔 없는 lifecycle/savedState 소유자를 직접 구현
  (`LifecycleOwner`+`SavedStateRegistryOwner`, `setViewTreeLifecycleOwner`/`setViewTreeSavedStateRegistryOwner`).
  `savedStateController.performRestore(null)` 후 CREATED→RESUMED, `destroy()` 에서 DESTROYED+removeView.
- **터치 모델(DESIGN.md 2장 준수, 소형 창 전략으로 단순화)**: 창을 `WRAP_CONTENT`(=카드 bounds)로만 유지
  → 창 밖은 이 창이 차지하지 않아 자동으로 게임에 통과. `FLAG_NOT_TOUCHABLE`/투명도 클램프 회피.
  플래그는 `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_NO_LIMITS`(키 포커스 안 뺏음). 카드 픽셀만 탭 가능.
  ※ DESIGN.md 의 `TOUCHABLE_INSETS_REGION` 은 전체화면 창을 쓸 때의 대안 — 우리는 더 안전한 소형 창 채택.
- **드래그 이동**: 상단 그립 바에서만 `detectDragGestures` → `LayoutParams.x/y` 갱신 + 화면경계 클램프,
  드래그 종료 시 `SharedPreferences`(`PrefsOverlayPositionStore`)에 저장. 다음 실행 시 복원.
- **컴팩트 칩 UI(1단계)** + **2단계 기본 카드**: 이름 + 타입 칩(색상 = `pokedex_db` dict.types[].color) +
  chevron 토글. 펼치면 특성 + 주요기술 4개(사용률%). "메가 가능" 배지 노출. 3단계 확장/메가토글/후보선택은
  **P4 스텁 콜백**(`onRequestExpandedPanel`/`onToggleMega`).
- 위치/색상 로직은 순수 JVM 로 분리(`OverlayPosition`, `OverlayCardData`)해 테스트 가능하게 함.

**2. `CaptureService`(FGS) 실구현** (`capture/`)
- `foregroundServiceType="mediaProjection"` + `IMPORTANCE_LOW` 알림 채널 + 상시 알림(`setOngoing`).
- **Android 15 순서 준수**: `onStartCommand` 안에서 **오버레이 먼저 `show()` → `startForeground(TYPE_MEDIA_PROJECTION)`**.
- MediaProjection 동의 result(Intent extra) 수신 → `getMediaProjection` → **콜백 등록 후** null-surface
  `VirtualDisplay`(AUTO_MIRROR)로 세션 유지(DESIGN.md 2장 트릭). `Callback.onStop()`(화면잠금/중단) → `stopSelf()`.
  실제 프레임 처리(ImageReader `setSurface`)는 **P3**.
- `onDestroy` 에서 VirtualDisplay/projection/overlay 전부 정리(누수 방지). `startIntent`/`stopIntent` 팩토리.

**3. `MainActivity` 온보딩(DESIGN.md 6장)**
- Compose 스크롤 화면: (1) 앱 소개 + "화면에 보이는 정보만 표시" 고지 →
  (2) 오버레이 권한(`Settings.canDrawOverlays`) 체크 + `ACTION_MANAGE_OVERLAY_PERMISSION` 설정 이동 + "다시 확인" →
  (3) 게임 "Display Battle Names ON" 안내 → (4) "시작"(권한 있어야 활성) →
  `MediaProjectionManager.createScreenCaptureIntent()` 동의 다이얼로그 → 결과를 `CaptureService.startIntent` 로
  `startForegroundService` → (5) 실행 중 상태 + "중지" 버튼(`stopIntent`).
- **데모/검증용**: 시작되면 서비스가 OCR 없이 `PokedexRepository` 직접 조회로 garchomp(한카리아스, ko/더블)
  카드를 오버레이에 실데이터로 표시(`OverlayCardData.fromRepository`).

**4. 유닛 테스트 추가** (순수 JVM)
- `OverlayPositionTest`(6): 클램프(안쪽/음수/초과/카드>화면), 기본위치(가로중앙·상단12%), 저장/복원 왕복.
- `OverlayCardDataTest`(2): 실 assets 로 garchomp 카드 조립(이름/타입칩+색상/특성/기술4개 사용률 내림차순/메가배지), 없는키 null.

### 빌드 검증 결과 (실제 실행)
- `./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL**, **27/27 통과**
  (기존 19 유지 + 신규 8: NameMatcher 10 / PokedexRepository 9 / OverlayCardData 2 / OverlayPosition 6).
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**, `app-debug.apk` 12MB 생성.
- 병합 매니페스트에 `foregroundServiceType="mediaProjection"` + 4개 권한 정상 반영 확인.
- 의존성 추가: `androidx.savedstate 1.2.1`(오버레이 ComposeView 의 ViewTreeSavedStateRegistryOwner 용).
- AGP 8.5.2 compileSdk 35 경고는 여전(무해). AGP 업그레이드는 P3 로 미룸.

### ❓ 실기기 검증 필요 체크리스트 (실기기 없는 환경이라 런타임 미검증)
- [ ] 오버레이 창이 게임 위에 실제로 뜨는가(`TYPE_APPLICATION_OVERLAY` 권한 동작).
- [ ] **터치 통과**: 카드 밖을 탭했을 때 게임에 정상 전달되는가(소형 창 전략 실측).
- [ ] **드래그**: 상단 그립으로 카드 이동 + 앱 재시작 후 위치 복원되는가.
- [ ] 카드 탭 → 2단계(특성/기술) 펼침/접힘 토글 동작.
- [ ] **FGS 알림**: 상태바 "캡처 중" 칩 상시 노출(Android 15 QPR1+/16 정책).
- [ ] **MediaProjection 동의 다이얼로그** 표시 → 동의 후 세션 확보(`getMediaProjection` non-null).
- [ ] 화면 잠금 시 `onStop()` 콜백 → 서비스/오버레이 정리되는가.
- [ ] Android 15 순서(오버레이 먼저→FGS)로 `ForegroundServiceStartNotAllowedException` 안 나는가.
- [ ] `POST_NOTIFICATIONS` 런타임 권한(Android 13+): 현재 온보딩에서 별도 요청 안 함 → 알림 안 뜨면 P3 에서 요청 추가 필요.

---

## P3 — 캡처 프레임 처리 + OCR 인식 파이프라인 ✅ 완료 (2026-07-05)

> DESIGN.md 3장 데이터 플로우 [1]~[7] 완결. 스텁이던 CaptureManager/FrameGate/RoiCropper/OcrEngine 실구현 +
> 코루틴 파이프라인으로 캡처→OCR→매칭→오버레이 연결.

### 완료 내역

**1. `[1] CaptureManager` 실구현** (`capture/CaptureManager.kt`)
- CaptureService 의 null-surface VirtualDisplay 에 `ImageReader`(RGBA_8888)를 `surface` 로 연결(=setSurface).
- **다운스케일 캡처**(기본 0.5 = 화면 절반 해상도, 2px 정렬). `VirtualDisplay.resize` 로 리더 크기에 맞춤.
- `acquireLatestImage()` 로 **항상 최신 프레임만** 취득(오래된 프레임 폐기 = 콜백 레벨 backpressure).
- **Image→Bitmap 변환**: plane rowStride/pixelStride 로 행 패딩 계산 → 패딩 포함 폭 Bitmap 에 `copyPixelsFromBuffer`
  후 실제 width 로 크롭. 패딩 0 이면 복사 없이 재사용 Bitmap 반환(할당 절감).
- **프레임 스로틀**: `minFrameIntervalMs`(기본 350ms ≈ 초당 ~3회) 안의 프레임은 버림.
- `stop()` 은 `surface=null` 로 detach → 프로젝션 세션은 유지하되 프레임만 중단.

**2. `[2] FrameGate` 실구현** (`capture/FrameGate.kt`) — **순수 JVM(테스트 가능)**
- ROI 픽셀을 `downsampleGray`(블록 평균 그레이스케일, 기본 12x4 격자)로 축소한 **서명**을 만들고,
  이전 서명과 `diffRatio`(셀 값 차 > tolerance 인 셀 비율) 비교로 변화 감지.
- 변화 임계(`diffThreshold` 기본 0.10) 이상 + 마지막 트리거로부터 **최소 인터벌**(기본 700ms) 경과 시에만 통과.
- ROI 별 독립 상태(더블배틀 0/1). `reset()` 지원. 픽셀 접근은 호출부(파이프라인)가 하고 int 배열/시각만 주입 →
  해시/변화판정/스로틀을 Robolectric 없이 유닛 테스트.

**3. `[3] RoiConfig` + `RoiCropper`** (`capture/RoiConfig.kt`, `RoiCropper.kt`, `PrefsRoiConfigStore.kt`)
- `RoiRect`(0~1 비율) + `RoiConfig`(ROI 목록) — **순수 JVM**. 비율→픽셀(`toPixels`) 변환, 직렬화/파싱.
- **기본값 = 합리적 추정치**(가로화면 더블배틀 상단 좌/우 이름 영역 2곳, `DEFAULT_LANDSCAPE_DOUBLES`).
  ⚠️ 정확 좌표는 **K2 미확정** → `RoiConfigStore`(SharedPreferences)로 오버라이드 가능(추후 보정 UI 연결).
- `RoiCropper`: 비트맵 크롭 + **2x 업스케일 전처리**(작은 폰트 OCR 개선). ROI 전부 실패 시 `cropFullTopHalf`
  (상단 절반) **fallback**.

**4. `[4] OcrEngine` 실구현** (`ocr/OcrEngine.kt`)
- ML Kit Text Recognition v2 — **언어별 recognizer 선택**(`OcrScript.forLanguage`): ko→korean, ja→japanese,
  zh-cn/zh-tw→chinese, 그 외(en/de/es/fr/it)→**latin 하나로 커버**. v1 기본 korean.
- ML Kit `Task` 를 `suspendCancellableCoroutine` 으로 감싸 suspend 화(취소/백프레셔). 블록/라인 텍스트 추출 +
  `pickNameLine`(문자 비율 최대 라인) 휴리스틱. `close()` 로 자원 해제.
- 언어→스크립트 매핑, 이름 라인 추출은 **순수 로직 → JVM 테스트**.

**5. 파이프라인 통합** (`capture/RecognitionPipeline.kt`, `PipelineDecider.kt`, `CaptureService` 배선)
- `RecognitionPipeline`: **conflate 채널**로 최신 프레임만 워커에 전달(backpressure). 워커는 `Dispatchers.Default`
  에서 순차 처리 — [2]게이트 → [3]크롭 → [4]OCR(suspend) → [5]`repository.match` → 판정 → [6]카드조립 → [7]슬롯갱신.
  캡처 콜백은 `trySend` 만(경량, 논블로킹). 프레임 Bitmap 은 재사용 대비 방어 복사 후 소유권 이전, 처리 후 recycle.
- `PipelineDecider`(**순수 JVM**): 미매칭→기존 카드 유지 / 후보1→갱신(후보없음) / 후보2+→최상위(usage_rank) 표시 +
  `hasMoreCandidates` 플래그(선택 UI 는 P4) / **같은 key 연속 인식→NoChange 스킵** / ROI 슬롯 독립.
- **더블배틀**: ROI 2곳 → `OverlayRenderer` 가 슬롯 0/1 카드 최대 2장 세로 스택 렌더(`updateSlot`/`removeCard`/`clearCards`).
  스레딩: 캡처 콜백 경량, OCR/매칭 Default, 오버레이 갱신은 `mainHandler.post`.

**6. `POST_NOTIFICATIONS` 런타임 요청** (`ui/MainActivity.kt`)
- "시작"/"데모" 버튼 클릭 시 Android 13+ 에서 `RequestPermission` 런처로 요청(P2 미해결 항목 해소). 거부해도 진행(알림은 부가).

**7. 데모 모드 유지** — `CaptureService.ACTION_DEMO`/`demoIntent`. 온보딩 "데모 카드 (캡처 없이)" 버튼 →
  MediaProjection 없이 garchomp(한카리아스) 카드를 슬롯 0 에 실데이터로 표시(실기기 UI 검증용). "시작(화면 캡처)" 은 실파이프라인.

**8. AGP 8.5.2 → 8.6.1 업그레이드** — compileSdk 35 "tested up to 34" 경고 제거(확인). ML Kit 의존성 추가:
  korean/japanese/chinese 16.0.1 + **latin(기본 recognizer) 19.0.1**(스크립트별과 버전 트랙 다름 — 빌드 실패로 확인·교정).
  코루틴 `kotlinx-coroutines-android 1.8.1` 추가.

**9. 유닛 테스트 추가**(순수 JVM, +33개)
- `FrameGateTest`(11): downsample 단색, diffRatio(동일/전변화/길이불일치/톨러런스), 최초프레임 통과, 무변화 스킵,
  변화+인터벌 통과, 인터벌내 스로틀, ROI 독립, reset.
- `RoiConfigTest`(9): 비율→픽셀, 경계클램프, 잘못된 rect 예외, 기본 2 ROI, 직렬화 왕복, 빈/잘못된 파싱 null, store effective.
- `PipelineDeciderTest`(7): 미매칭 유지, 후보1, 후보2+(플래그), 연속동일 스킵, 변경 갱신, 더블 슬롯 독립, reset.
- `OcrEngineLogicTest`(6): 언어→스크립트 매핑(9언어), 대소문자, 미지언어 latin fallback, pickNameLine.

### 빌드 검증 결과 (실제 실행)
- `./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL**, **60/60 통과**(failures=0, errors=0)
  (기존 27 + 신규 33). 클래스별: FrameGate 11 / RoiConfig 9 / PipelineDecider 7 / OcrEngineLogic 6 / NameMatcher 10 /
  PokedexRepository 9 / OverlayCardData 2 / OverlayPosition 6.
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**, `app-debug.apk` 12MB 생성.
- AGP 8.6.1 로 compileSdk 35 경고 사라짐 확인.

### ❓ 실기기 검증 필요 체크리스트 (실기기 없는 환경 — 런타임 미검증)
> P2 체크리스트(오버레이/드래그/FGS/동의 다이얼로그/onStop 정리)에 더해:
- [ ] **선검증 K1(FLAG_SECURE)**: 여전히 최우선. 캡처 시 검은 화면이면 앱 자체 불성립.
- [ ] **실프레임 수신**: `ImageReader` 콜백이 실제로 도는가(로그 "캡처 시작"→onFrame). VirtualDisplay `setSurface` 후 프레임 도착.
- [ ] **Image→Bitmap 정합**: rowStride 패딩 있는 기기(일부 GPU)에서 이미지가 우측으로 밀리지 않는가(패딩 크롭 검증).
- [ ] **POST_NOTIFICATIONS 요청 다이얼로그** 표시 → 허용 시 "캡처 중" 알림 노출.
- [ ] **데모 버튼**: MediaProjection 없이 garchomp 카드가 뜨는가(실기기 UI 1차 확인용).
- [ ] **더블배틀 2카드**: ROI 2곳 인식 시 카드 2장이 세로로 뜨는가.
- **OCR 인식률/지연 실측 방법(K3)**:
  - 대전 스크린샷 여러 장 수집 → 실 ROI 좌표를 화면 비율로 환산해 `RoiConfig` 기본값/오버라이드 교정.
  - `adb logcat -s RecognitionPipeline CaptureManager OcrEngine` 로 프레임→OCR 라인→매칭 key 흐름 관찰.
  - 인식 라인을 파일 로그로 덤프 → 정답 대비 정확도(%), OCR 호출당 지연(ms) 집계. 목표 지연 100~400ms(DESIGN.md 3장).
  - 정확도 낮으면: 업스케일 배율 상향(2→3x), ROI 여백 조정, FrameGate `diffThreshold`/인터벌 튜닝.
- [ ] **발열/전력**: FrameGate 로 유휴 시 OCR 미가동인지(이름 안 바뀌면 트리거 없어야) 확인.

### P4 착수 시 주의 (UI 완성: 3단계 카드 / 후보선택 / 메가토글 / 수동검색)
1. **후보 선택 UI**: `PipelineDecider.UpdateCard.hasMoreCandidates`/`candidateCount` 가 이미 플래그로 옴. 현재는 최상위만
   표시 → P4 에서 후보 리스트(각 타입칩+사용률)를 카드에 펼쳐 유저 탭 확정. `MatchResult.Matched.candidates`(정렬됨) 사용.
   선택 결과를 배틀 동안 기억(슬롯별 고정 key). 파이프라인이 이후 같은 root 재인식 시 유저 선택 유지하도록 Decider 확장 필요.
2. **3단계 확장 패널**: `OverlayCard.onRequestExpandedPanel`(현재 no-op). 종족값 6스탯 + 전체 기술(사용률순) + 방어 상성(약점/반감).
   방어 상성은 타입 상성표가 아직 없음 → 타입 chart 데이터/계산 로직 신규 필요(DESIGN.md 5장).
3. **메가 토글**: `OverlayCard.onToggleMega`(현재 no-op) + `OverlayCardData` 에 메가 상태 필드 추가. `repo.megaFormsOf(key)` 로
   타입/특성/종족값 스왑(기술/사용률은 base 재사용). 리자몽/라이츄 2메가는 세그먼트 토글([메가 X][메가 Y]).
4. **수동 검색 fallback**: 미매칭(NoMatch) 또는 "인식 실패 🔍" 상태에서 이름 직접 입력/목록 검색. 현재 미매칭은 카드 유지만 함.
5. **상태별 UX**(DESIGN.md 5장 표): 인식실패/이름미표시/캡처중단 각 상태 카드. 캡처 중단은 이미 `onStop→stopSelf` 이므로
   오버레이 최소화+재시작 버튼 경로 추가.
6. **ROI 보정 UI**: `PrefsRoiConfigStore` 는 이미 오버라이드 저장 가능 → 유저가 화면 위에서 ROI 사각형을 드래그해 저장하는 UI.
7. **터치 모델 재검토**: 후보 리스트/확장 패널이 커지면 소형 창 전략에서 터치 영역 관리가 복잡해짐 — 카드 스택 크기 변동 시 창 위치 클램프 재확인.
8. **언어 설정 UI**: 현재 `CAPTURE_LANG="ko"` 하드코딩 → 게임 언어 선택 설정 추가(OcrEngine 는 이미 언어별 recognizer 지원).

---

## P4 — 오버레이 UI 완성 ✅ 완료 (2026-07-05)

> DESIGN.md 5장 UI/UX 완결: 3단계 점진 공개 · 후보 선택 · 메가 토글 · 수동 검색 · 언어 설정.
> P3 스텁 콜백(`onRequestExpandedPanel`/`onToggleMega`)과 `hasMoreCandidates` 플래그를 실기능으로 승격.

### 완료 내역

**1. 방어 타입 상성표 + 계산 (`data/TypeChart.kt`, 순수 JVM)**
- 표준 18타입 상성표를 **코드 상수**로 내장(고정 지식, 데이터 파일 불필요). slug 는 pokedex_db `dict.types` 18키와 정확히 일치(파이썬으로 확인).
- `effectiveness(atk, def)`(단일 배수) / `defensiveMultipliers(types)`(방어 조합 → 공격타입별 총배수, 2타입은 곱) /
  `defensiveMatchup(types)` → `DefensiveMatchup`(약점 ×4/×2, 반감 ×½/×¼, 무효 ×0 버킷; 등배는 생략).
- 예 검증: dragon/ground → 얼음 ×4, 드래곤·페어리 ×2, **전기 무효**. grass/water → 물 ×¼.

**2. 3단계 점진적 정보 공개 (`overlay/OverlayCard.kt`, `OverlayRenderer.kt`)**
- `CardStage`(CHIP→CARD→EXPANDED) 탭 순환, **슬롯별 독립 단계**(`stageBySlot`). 칩=이름+타입, 카드=+특성+주요기술4(사용률%)+메가/바꾸기,
  확장=+종족값 6스탯+전체 기술(사용률순)+**방어 상성**+도감번호.
- **확장 패널**: `heightIn(max=320dp)` + 세로 스크롤 + **무조작 8초 자동 축소**(`autoCollapseMs`, `LaunchedEffect` 1초 폴링 →
  `expandedAtBySlot` 경과 슬롯을 CARD 로). 조작 시 타이머 리셋(`onInteract`).
- `OverlayCardData.ExpandedData`(종족값/전체기술/상성/도감번호)를 순수 JVM 으로 조립. 종족값은 base 대비 **증감(delta)** 계산(메가 시각화).

**3. 후보 선택 UI (`CandidateSheet` + `PipelineDecider` 확장)**
- `hasMoreCandidates` 카드에 "어느 쪽? 바꾸기 ▸" → **후보 시트**(창 내부 확장): 각 후보를 **타입칩+사용률**과 함께, 사용률 최상위=**추천 배지**+테두리.
- **선택 기억(슬롯별)**: `PipelineDecider.rememberChoice(roi, root, key)` — **같은 root(표시명)가 유지되는 동안** 그 선택을 우선 표시.
  root 가 바뀌면 자동 무시. `RecognitionPipeline.chooseCandidate` 가 기억+즉시 갱신. 후보 여럿이면 "바꾸기" 진입점 계속 노출.
- 서비스가 `candidatesForRoot(root)`(=`repo.candidatesOfRoot`)로 후보를 조립해 오버레이에 provider 로 주입.

**4. 메가진화 토글**
- `can_mega` 카드에 메가 세그먼트([기본][메가] / X·Y 2메가는 [메가 X][메가 Y]). `OverlayCardData.fromRepository(withMegaForms=true)` 가
  `repo.megaFormsOf(key)`로 각 메가 폼 카드를 미리 조립해 담음(타입/특성/**종족값 증감** 스왑, **기술·사용률은 base 재사용**).
- 리자몽 2메가 라벨(`-x`/`-y` → "메가 X"/"메가 Y") 검증. 메가 카드 자신은 재귀 방지로 `megaForms` 비움.

**5. 수동 검색 fallback (`SearchSheet` + `repo.searchByName` + 핀)**
- `OcrEngine` 미매칭(NoMatch) 시 슬롯에 카드 없으면 `showFailure(slot)` → **"인식 실패 🔍" 카드** 탭 → 검색 시트(이름 부분일치, 현재 언어,
  접두 우선, 메가 폼 제외).
- 선택 시 `pinSlot(slot, key)` → **핀 상태**(`PipelineDecider.pin`): 파이프라인 인식이 해당 슬롯을 덮어쓰지 않음(decide 가 NoChange). 카드에 "📌 고정 해제" 버튼 → `unpin`.

**6. 설정 (`data/AppSettings.kt` + `MainActivity` 설정 섹션)**
- **게임 언어 9언어** FilterChip 선택(`AppSettings.language`, SUPPORTED_LANGUAGES 검증) → 다음 세션의 `captureLang` 로 OcrEngine recognizer +
  표시 언어 + 검색/후보 언어에 일괄 연동. **`CAPTURE_LANG` 하드코딩 제거**.
- **ROI 기본값 리셋** 버튼(`PrefsRoiConfigStore.clear`) — ROI 편집 UI 는 고급으로 P5+ 로 남김(현재는 리셋만).
- 신규 UI 문구를 `res/values/strings.xml` 로 중앙화(다국어 대비). ※ 기존 온보딩/카드 인라인 한국어 문자열은 P5 에서 일괄 이관 예정.

**7. 창=카드 전략 보존**: 후보/검색 시트·확장 패널을 모두 **오버레이 창 내부 Column 에 세로 확장**(WRAP_CONTENT 창이 함께 커짐) →
  창 밖 게임 터치 통과 그대로 유지. 별도 전체화면 창/포커스 탈취 없음.

**8. 유닛 테스트 추가(순수 JVM, +23개)**
- `TypeChartTest`(7): 단일 배수, 한카리아스 dragon/ground(얼음 ×4·전기 무효·드래곤/페어리 ×2), 버킷 분류, 이중 무효 곱, ×¼ 반감, 미지타입 등배, 전체등배 isEmpty.
- `PipelineDeciderChoiceTest`(7): 선택 기억(같은 root 유지/다른 root 무시/슬롯 독립), 핀(인식 무시/해제 후 반영/슬롯 독립/reset 초기화).
- `ManualSearchTest`(5): 부분일치(접두 우선), 정확일치, 빈/무관 검색, 메가 제외.
- `OverlayCardExpandedTest`(4): 확장 패널(도감445·합600·상성 ×4 얼음·전체기술), 메가 스왑(합700·+100·공격+40·기술 base 재사용·megaForms 비움), 리자몽 X/Y 라벨+타입, 후보 root 사용률순.

### 빌드 검증 결과 (실제 실행)
- `./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL**, **83/83 통과**(failures=0, errors=0). 기존 60 + 신규 23.
  클래스별: TypeChart 7 / PipelineDeciderChoice 7 / ManualSearch 5 / OverlayCardExpanded 4 / (기존) FrameGate 11 / RoiConfig 9 /
  PipelineDecider 7 / OcrEngineLogic 6 / NameMatcher 10 / PokedexRepository 9 / OverlayCardData 2 / OverlayPosition 6.
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**, `app-debug.apk` 생성. Kotlin 컴파일 경고 0.
- 신규 의존성 없음(FlowRow/FilterChip 은 기존 Compose BOM 내 experimental → `@OptIn` 로 처리, 추가 라이브러리 불필요).

### UI 상태 머신 요약 (슬롯별 독립)
```
[인식 실패] --탭--> [검색 시트] --선택--> [핀 카드(고정 해제 버튼)]
    ▲                                          │ 고정 해제
    │ NoMatch·카드없음                          ▼
[파이프라인 인식] --UpdateCard--> [CHIP] <--탭순환--> [CARD] <--탭순환--> [EXPANDED]
                                    ▲                  │ hasMoreCandidates    │ 8s 무조작
                                    │                  ▼                      ▼ 자동축소
                                    └──탭순환──────  [후보 시트] --선택(기억)--> CARD 로
  메가 세그먼트([기본][메가X][메가Y])는 CARD/EXPANDED 에서 폼 데이터 스왑(기술은 base 유지).
```

### ❓ 실기기 검증 필요 체크리스트 (P4 UI — 실기기 없는 환경)
> P2/P3 체크리스트(오버레이 표시·터치통과·드래그·FGS·동의·onStop·실프레임)에 더해:
- [ ] **3단계 탭 순환**: 칩→카드→확장→칩 이 탭마다 순환되고 슬롯별 독립인가.
- [ ] **확장 패널 스크롤 + 자동 축소**: 8초 무조작 시 CARD 로 축소, 스크롤/탭 하면 타이머 리셋되는가.
- [ ] **방어 상성 표시**: 확장 패널에 약점/반감/무효 타입칩이 실제로 뜨는가(색상 포함).
- [ ] **후보 선택 시트**: "바꾸기" → 후보들이 타입칩+사용률+추천배지로 뜨고, 선택 후 같은 표시명 재인식 시 유지되는가.
- [ ] **메가 세그먼트**: [메가]/[메가 X][메가 Y] 전환 시 타입·특성·종족값(증감 색상)이 바뀌고 기술은 그대로인가.
- [ ] **수동 검색**: "인식 실패" 카드 탭 → 검색어 입력(IME) 동작, 결과 선택 시 핀 고정 + "고정 해제"로 파이프라인 복귀되는가.
      ※ `FLAG_NOT_FOCUSABLE` 창에서 `BasicTextField` IME 포커스 동작을 **반드시 실기기 확인**(포커스 안 잡히면 P5 에서 검색 시 임시로 포커스 허용 플래그 토글 필요).
- [ ] **시트 확장 시 터치통과**: 시트로 창이 커진 상태에서 창 밖 게임 터치가 여전히 통과하는가, 화면경계 클램프 정상인가.
- [ ] **언어 설정 연동**: 설정에서 언어 변경 → 다음 "시작" 세션에서 해당 언어 recognizer/표시로 뜨는가.

### P5 착수 시 주의 (마무리: 온보딩 다듬기 / 릴리즈 빌드 / 실기기 테스트 가이드)
1. **IME 포커스**: 위 체크리스트 경고대로, 수동 검색 시트의 텍스트 입력이 `FLAG_NOT_FOCUSABLE` 오버레이 창에서 안 잡힐 수 있음.
   해결책: 검색 시트가 열릴 때만 창 플래그에서 `FLAG_NOT_FOCUSABLE` 를 잠시 제거(`updateViewLayout`) → 닫으면 복원. 실기기 확인 후 반영.
2. **문자열 이관**: 온보딩/카드의 인라인 한국어 문자열이 아직 코드에 남아 있음 → 전부 `strings.xml` 로 이관하고 언어 리소스(values-en 등) 골격 마련.
3. **상태별 UX 잔여**: "이름 미표시(배틀명 off)" 1회 안내, "캡처 중단(잠금)" 시 오버레이 최소화+재시작 버튼 경로는 아직 미구현(현재는 onStop→stopSelf 로 종료만).
4. **ROI 편집 UI**: 설정엔 리셋만 있음 → 화면 위 ROI 사각형 드래그 편집 UI(고급)는 P5+ 후보(K2 실좌표 확정과 함께).
5. **릴리즈 빌드**: `assembleRelease` + 서명/난독화(R8) 설정, 민감권한(SYSTEM_ALERT_WINDOW·MediaProjection) 사용목적 Play 선언문, 사이드로드 APK 백업.
6. **여전히 최우선 = 선검증 K1(FLAG_SECURE)**: 실기기에서 포챔스 캡처가 검은 화면이면 앱 자체 불성립. 코드와 무관하게 반드시 먼저 확인.

---

## P5 — 마무리 + 릴리즈 빌드 + 실전 테스트 가이드 ✅ 완료 (2026-07-05)

> P4 가 남긴 리스크(IME 포커스) 해소 + 잔여 상태별 UX + 문자열 이관/2언어 + R8 릴리스 빌드 +
> 실기기 테스트/README 문서화. **코드로 할 수 있는 것은 여기서 끝** — 남은 것은 실기기 K1~K4.

### 완료 내역

**1. IME 포커스 토글 (P4 최대 리스크 해소, `overlay/OverlayRenderer.kt`)**
- 오버레이 창은 평소 `FLAG_NOT_FOCUSABLE`(게임 키 포커스 보존) → 이 상태에선 `BasicTextField` 가
  소프트키보드 포커스를 못 잡아 수동 검색 입력 불가.
- `baseFlags(focusable)` 헬퍼로 플래그 조립 통일. `setFocusable(want)` 이 `FLAG_NOT_FOCUSABLE` 를
  토글하고 `WindowManager.updateViewLayout` 으로 즉시 반영.
- **검색 시트가 열릴 때만** 포커스 획득: `OverlayRoot` 의 `LaunchedEffect(searchOpen)` 이
  `setFocusable(searchOpen)` 호출 → 닫히거나 후보 시트로 바뀌면 즉시 포커스 반납.
  (`FLAG_LAYOUT_NO_LIMITS` 는 소형 창 전략 위해 항상 유지.)

**2. 잔여 상태별 UX (DESIGN.md 5장 표, `OverlayRenderer` + `OverlayCard` + `CaptureService`)**
- **캡처 중단 카드**: `MediaProjection.Callback.onStop()`(화면잠금/사용자 중단) → 즉시 stopSelf 대신
  `handleCaptureStopped()` 로 파이프라인/세션만 정리하고 오버레이엔 `showCaptureStopped()` →
  "캡처 중단됨 + ▶ 재시작" 카드(`CaptureStoppedCard`). 재시작 탭 → `restartCapture()` 가 MainActivity
  재실행(1회성 토큰이라 재동의 필요) 후 서비스 정리.
- **미인식 안내 배너**: 파이프라인 시작 후 `NO_MATCH_HINT_MS`(20초)간 인식 성공이 한 번도 없으면
  워치독(`scheduleBattleNamesWatchdog`)이 `showBattleNamesHintOnce()` → "배틀명 표시 ON" 배너 1회
  (`BattleNamesHintBanner`, 세션당 1회). 인식 성공(`updateSlot`) 시 자동 해제.

**3. 문자열 이관 + 2언어 (`res/values/strings.xml` + 신규 `values-en/strings.xml`)**
- 온보딩(MainActivity)·오버레이 카드/시트/상태카드(OverlayCard)·FGS 알림(CaptureService)의
  인라인 한국어 문자열을 전부 `strings.xml` 로 이관. Compose 는 `stringResource`, 서비스는 `getString`.
- **영어 리소스(`values-en`)** 전량 추가 → 앱 UI 2언어(한/영). 데이터는 이미 9언어라 UI 2언어면 충분.
- 방어 상성 라벨은 순수 JVM `OverlayCardData` 가 조립하므로 Context 주입 대신
  `MatchupLine.bucket`(enum) 필드 추가 → UI 가 버킷→리소스로 라벨 렌더(기존 `label` 은 테스트 폴백 유지).

**4. 릴리즈 빌드 (R8, `app/build.gradle.kts` + `app/proguard-rules.pro`)**
- 버전 **0.1.0**(versionCode 2). release 에 `isMinifyEnabled=true` + `isShrinkResources=true`.
- proguard-rules 를 **모듈 루트(`app/proguard-rules.pro`)로 이동**(AGP `proguardFiles` 상대경로 규칙 —
  src/main 에 있으면 "does not exist" 경고로 미적용됨을 실빌드로 확인·교정).
- keep 규칙: kotlinx-serialization(데이터 모델 Companion/`$serializer`/`SerializationConstructorMarker`) +
  ML Kit(GMS mlkit 패키지) + 코루틴. **release APK 의 mapping.txt 로 데이터 클래스/serializer 보존 실확인.**
- 디버그 키로 서명된 release APK 생성(사이드로드/실기기 테스트용).

**5. 실전 테스트 가이드 (`FIELD_TEST.md`, 프로젝트 루트)**
- (a) APK 설치(adb/직접) (b) **K1 FLAG_SECURE 검증 최우선 5분** (c) K2 표시명/ROI 스크린샷→비율 보정법
  (d) K3 OCR 인식률 실측(logcat 태그 `CaptureService/CaptureManager/RecognitionPipeline/OcrEngine`)
  (e) 온보딩 + UI 전체 체크리스트 (f) 알려진 제약/트러블슈팅(IME 포커스, Android 14+ 재동의, 15 순서 등).

**6. README (`README.md`, 프로젝트 루트)**
- 개요, 아키텍처 [1]~[7] + 소스 위치, 데이터 파이프라인 3종 스크립트 재실행법, 빌드/서명, 문서 링크.

**7. 유닛 테스트 (+1, 총 84)**
- `OverlayCardExpandedTest` 에 `방어상성_버킷_라벨_매핑`(한카리아스 dragon/ground → WEAK4 버킷·전기 IMMUNE·
  bucket↔label 전수 일치) 추가.

### 빌드 검증 결과 (실제 실행 — 3종 태스크)
- `./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL**, **84/84 통과**(failures=0, errors=0). 기존 83 + 신규 1.
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**, `app-debug.apk` ~12MB.
- `./gradlew :app:assembleRelease` → **BUILD SUCCESSFUL**, R8 minify 적용, `app-release.apk` **~2.33MB**.
  - proguard 규칙 적용 확인(경고 소멸), mapping.txt 에 데이터 클래스/`$serializer`(16개) 보존 확인,
    APK 내 assets 3종 JSON + values-en 로케일 포함 확인.

### ❓ 남은 실기기 검증 항목 (코드 완료 — 런타임은 실기기에서만)
> P2~P4 체크리스트에 더해 P5 신규 항목:
- [ ] **K1 FLAG_SECURE**(★최우선 5분★) — 막히면 앱 불성립. FIELD_TEST (b).
- [ ] **K2 표시명/ROI** — 스크린샷으로 실좌표·실문자열 확정. FIELD_TEST (c).
- [ ] **K3 OCR 인식률/지연** — logcat 실측, 목표 100~400ms. FIELD_TEST (d).
- [ ] **K4 EULA/규정** — 외부 보조도구 금지 여부.
- [ ] **IME 검색 입력**(P5) — 검색 시트에서 소프트키보드 포커스/입력이 실제로 되는가(`setFocusable` 토글 동작).
- [ ] **캡처 중단 카드**(P5) — 화면 잠금 시 "재시작" 카드 노출 + 탭 시 재동의 흐름.
- [ ] **미인식 안내 배너**(P5) — 20초 미인식 시 1회 노출, 인식 성공 시 자동 해제.

---

## P6 — 에뮬레이터 런타임 스모크 테스트 ✅ 완료 (2026-07-05)

> 실기기 없이 검증 가능한 런타임 항목을 **Android 에뮬레이터(API 35, google_apis, arm64)에서 실제 구동**으로
> 검증하고, 발견된 크래시/버그를 즉시 수정. 스크린샷 증거는 `android/screenshots/`.

### 환경
- AVD `Kohana_QA_API_35`(Android 15, 1080×2400, density 420) 헤드리스(`-no-window`) 부팅(18s).
- debug APK 설치 → `appops set SYSTEM_ALERT_WINDOW allow` + `pm grant POST_NOTIFICATIONS` 로 권한 부여.
- UI 자동화: `adb shell input tap`(uiautomator dump 로 좌표 확인) + `screencap` 증거.

### 🐛 발견·수정한 버그

**BUG-P6-1 (치명적, 실기기에도 영향): 데모 카드 진입 시 즉시 크래시.**
- 증상: 온보딩 "데모 카드(캡처 없이)" 탭 → `FATAL EXCEPTION: java.lang.SecurityException:
  Starting FGS with type mediaProjection ... requires ... [CAPTURE_VIDEO_OUTPUT, android:project_media]`.
- 원인: 데모 경로는 MediaProjection 토큰이 **없는데도** `startForeground(..., TYPE_MEDIA_PROJECTION)` 를 호출.
  Android 14+(targetSDK 35)는 mediaProjection 타입 FGS 시작 시 **실제 프로젝션 토큰**을 요구 → 토큰 없으면 SecurityException.
  에뮬레이터뿐 아니라 **실기기 Android 14/15 에서도 100% 재현**되는 실버그.
- 수정:
  - `AndroidManifest`: 서비스 `foregroundServiceType="mediaProjection|specialUse"` 로 확장 +
    `FOREGROUND_SERVICE_SPECIAL_USE` 권한 + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 사유 property 추가.
  - `CaptureService.startForegroundWithNotification(isDemo)`: 데모면 `TYPE_SPECIAL_USE`,
    실캡처면 `TYPE_MEDIA_PROJECTION` 으로 분기.
  - 재검증: 데모 카드 정상 표시, 크래시 0. (screenshots 02~11)

**개선-P6-2: 데모 순환 + 후보 시트 검증 경로.**
- 기존 데모는 garchomp 단일 고정이라 **후보 시트(collision)** 를 실기기 UI 로 볼 방법이 없었음.
- `CaptureService.DEMO_CYCLE`(garchomp→arcanine) 순환 도입. 데모 버튼 연타(실행 중에도 노출) 시 다음 대상으로 순환.
  arcanine 은 root "arcanine"(윈디/히스이윈디 2후보)이라 `hasMoreCandidates=true` 로 "바꾸기" 진입점이 뜬다.
- `MainActivity`: 실행 중 상태에도 "데모 카드" 버튼을 유지해 연타 순환 가능하게 함.
- 회귀 방어 테스트 `demoCycle_targets_areValidAgainstAssets` 추가(garchomp canMega, arcanine 후보 2+).

**개선-P6-3: OCR 모델 다운로드 중 로그 폭주 완화.**
- 실캡처 최초 실행 시 ML Kit 온디바이스 모델을 Play 서비스에서 다운로드하는 동안 프레임마다
  `MlKitException: Waiting for the text optional module to be downloaded` 전체 스택트레이스가 찍혀 로그 폭주.
- `RecognitionPipeline`: "module/download" 메시지면 `Log.i` 한 줄("OCR 모델 다운로드 대기 중")로 축약,
  그 외 실패만 `Log.w`+스택트레이스. (파이프라인은 원래도 크래시 없이 스킵 — 로깅만 개선.)

### 검증 결과 (에뮬레이터 실구동)

| 항목 | 결과 | 증거 |
|---|---|---|
| 앱 시작 크래시 없음, 온보딩 렌더 | ✅ | 01 (권한 Granted✓, Start/Demo 버튼, 설정 섹션) |
| 데모 카드: 오버레이 창 표시(`TYPE_APPLICATION_OVERLAY`) | ✅ | 02, dumpsys window 로 overlay 창 확인 |
| 3단계 탭 순환(칩→카드→확장) | ✅ | 02(칩)→03(카드: 특성/기술4·사용률)→05(확장) |
| 타입칩 색상(dict.types color) | ✅ | 드래곤(청보라)/땅(갈색)/불꽃(주황)/얼음(청)/페어리(분홍) |
| 방어 상성 표시 | ✅ | 05: 한카리아스 ×4 얼음, ×2 드래곤/페어리, 전기 무효 |
| 메가 토글(garchomp) | ✅ | 04: 메가한카리아스, 특성 스왑, 종족값 +100(공+40 등), **기술 base 유지** |
| 드래그 이동 + 위치 저장 | ✅ | 06(드래그)→prefs `overlay_y` 갱신→07(재시작 후 저장위치 복원) |
| 후보 시트(arcanine 데모) | ✅ | 08→09("바꾸기")→10(윈디/히스이윈디, 타입칩+사용률+Top배지)→11(선택 반영) |
| FGS "캡처 중" 알림 표시 | ✅ | dumpsys notification: pkg=…supporter channel=capture_channel FOREGROUND_SERVICE |
| 서비스 중지 정리(누수 없음) | ✅ | 12: Stop→오버레이 창 제거, ServiceRecord 없음, 알림 해제, WindowLeaked 0 |
| **실캡처 파이프라인 기동** | ✅(부분) | 13(동의 다이얼로그)→14: MediaProjection 세션 확보→CaptureManager 프레임→ML Kit OCR 호출. FATAL 0 |
| logcat 크래시/ANR | ✅ | 전 과정 `FATAL EXCEPTION` 0건 |

### 에뮬레이터 한계로 미검증(실기기 K 항목 잔존)
- **K1 FLAG_SECURE**: 포챔스 미설치 → 판정 불가(★최우선, 실기기).
- **K3 OCR 인식 정확도/지연**: 에뮬레이터엔 배틀 화면이 없어 실인식 미측정. 단, **파이프라인이 실제로 돌고
  ML Kit recognizer 를 호출**하는 것까지는 확인(모델 다운로드 후 프레임 처리 정상 진입).
- 실캡처 시 ML Kit 모델은 최초 1회 다운로드 필요(온라인). 오프라인 실기기 첫 실행 시 인식 지연 가능 — FIELD_TEST 참고.
- 터치 통과(창 밖 게임 입력 전달)는 게임이 없어 정성 확인만(소형 창 전략상 창 밖은 미점유).

### 빌드·테스트 (실제 실행)
- `:app:testDebugUnitTest` → **BUILD SUCCESSFUL, 85/85 통과**(기존 84 + P6 신규 1).
- `:app:assembleDebug` → app-debug.apk ~12MB. `:app:assembleRelease` → R8, app-release.apk ~2.33MB.

---

## P7 — 최종 정밀 리뷰(고위험 런타임 경로 2곳) ✅ 완료 (2026-07-05)

> 실기기 필드테스트 전 마지막 정적 리뷰. Android 14+ MediaProjection 토큰 1회성 제약을 타는
> "캡처 중단→재시작" 경로와 P6 에서 추가한 `specialUse` FGS 정합성을 집중 점검. 부수 경로(오버레이/
> 파이프라인 정리, 스레딩)도 함께 확인. 스코프는 이 두 경로 + 발견 시 부수 수정으로 한정.

### 🐛 발견·수정한 버그

**BUG-P7-1 (고위험, 실기기 재현): 캡처 중단 후 "재시작" 이 막다른 상태가 됨.**
- 경로: 화면잠금(Android 15 자동중단)·유저 중단 → `MediaProjection.onStop` → `handleCaptureStopped`
  → 오버레이 "캡처 중단됨" 카드. 유저가 "재시작" 탭 → `restartCapture()` 가
  `startActivity(MainActivity, NEW_TASK|REORDER_TO_FRONT)` + `stopSelf()`.
- 토큰 관점은 **안전**: 기존 토큰을 재사용하지 않고 MainActivity 로 돌아가 재동의를 받는 구조가 맞다
  (`getMediaProjection`/`createVirtualDisplay` 재호출 없음, `acquireProjection` 은 `projection!=null` 가드).
- **문제**: `REORDER_TO_FRONT` 는 기존 MainActivity 인스턴스를 **재생성하지 않고**(=`onCreate` 미실행)
  앞으로만 가져온다. 그런데 "시작" 버튼 노출을 좌우하는 Compose 상태 `running` 은 순수 `mutableStateOf`
  로 리줌 시 재동기화가 없었다. 유저가 Start 로 세션을 시작(`running=true`)한 뒤 중단→재시작하면
  액티비티가 여전히 `running=true` UI(Stop/Demo 만)로 떠서 **`createScreenCaptureIntent`(재동의) 를
  다시 띄울 "시작" 버튼이 없는 막다른 상태**가 된다. 실기기 Android 14/15 에서 재현되는 실버그.
- 수정(`MainActivity.OnboardingScreen`): `LocalLifecycleOwner` + `DisposableEffect` 로 `ON_RESUME` 관찰자를
  달아, 액티비티가 다시 보일 때 `running=false` + `overlayGranted` 재확인. 재시작 경로는 항상 서비스를
  `stopSelf` 한 뒤 액티비티를 띄우므로 복귀 시점엔 캡처 세션이 없어 안전하며, "시작" 버튼이 복원돼
  재동의가 가능해진다. (신규 의존성 없이 `lifecycle-runtime-ktx` + compose-ui 로만 구현.)
- 검증: 에뮬레이터(`Kohana_QA_API_35`)에서 데모 시작(`running`→Stop UI 확인) → 서비스 stop +
  `REORDER_TO_FRONT` 재진입("task brought to front" = 재생성 아님) → UI 가 **"Start (screen capture)"
  로 복원**됨을 uiautomator dump 로 확인. 크래시 0.

### 안전 판정(수정 불필요)

- **specialUse FGS 정합성 — 안전.** 매니페스트 `foregroundServiceType="mediaProjection|specialUse"`,
  `FOREGROUND_SERVICE_SPECIAL_USE` 권한, `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property 모두 올바르게 선언.
  `startForegroundWithNotification(isDemo)` 의 타입 분기(데모→`TYPE_SPECIAL_USE`,
  실캡처→`TYPE_MEDIA_PROJECTION`)도 정확. **단, Play 배포 시 `specialUse` 는 콘솔 사유 선언·심사 필요**
  → FIELD_TEST.md 에 메모 추가(대안: 릴리즈에서 데모 경로 제거).
- **OverlayRenderer lifecycle 정리 — 안전.** `destroy()` 가 `LifecycleRegistry` 를 DESTROYED 로 내리고
  `windowManager.removeView` 후 참조 해제. `added` 가드로 중복 제거 방지. 누수 경로 없음(P6 12번 스샷도 뒷받침).
- **RecognitionPipeline 취소/Bitmap — 안전.** 프레임 Bitmap 은 `processFrame`/`processCrop` 의 `finally`
  에서 recycle, `submitFrame` 은 채널 밀림 시 복사본 recycle. `stop()` 이 채널 close+게이트/판정 reset,
  `onDestroy` 가 `pipelineScope.cancel()` 로 워커 취소. (CONFLATED 채널에 남을 수 있는 최대 1개
  버퍼 Bitmap 은 프로세스 teardown 시점이라 무시 가능 — 스코프 확장 없이 현행 유지.)
- **onStop 콜백 스레딩 — 안전.** `projectionCallback.onStop` 은 `mainHandler.post` 로 넘겨 UI 갱신
  (`showCaptureStopped`)을 메인 스레드에서 수행. 파이프라인 `onCardUpdate` 도 `mainHandler.post` 로 갱신.

### 빌드·테스트 (실제 실행)
- `:app:testDebugUnitTest` → **BUILD SUCCESSFUL, 85/85 통과**(회귀 0, 기능 변경 없는 상태 재동기화 수정).
- `:app:assembleDebug`(app-debug.apk ~12MB) · `:app:assembleRelease`(R8, app-release.apk ~2.33MB) 모두 성공.
- 에뮬레이터 스모크: 재시작 재진입 시 "시작" 버튼 복원 확인(위 BUG-P7-1 검증).

---

## P8 — 실게임 화면 기반 K1~K3 사전 검증 ✅ 완료 (2026-07-05)

> 실기기 전용으로 남겨둔 선검증 3종(K1/K2/K3)을 **웹의 실게임 녹화 자료**로 앞당겨 검증.
> 유튜브 모바일 게임플레이에서 배틀 프레임을 추출(yt-dlp+ffmpeg)해 표시명/ROI를 실측하고,
> 앱 파이프라인에 통과시켜 인식 경로를 검증. 샘플은 `field_samples/`(로컬 검증 전용, SOURCES.md 출처 기록).

### K1 (FLAG_SECURE) — 판정: **GO (유력)**

- **증거**: 포켓몬 챔피언스 **모바일판 화면녹화 게임플레이가 유튜브에 다수 존재**.
  - **DM Gaming "Should You Play...Pokémon Champions? (Mobile Review)"** (2026-06-18, 모바일 출시 직후)
    → **페이스캠 없는 순수 모바일 화면 캡처**(레터박스 16:9). 배틀 애니메이션·이름표·메뉴 전부 정상 캡처.
    https://www.youtube.com/watch?v=yWHjy1Pp3PU
  - **GameXplain "Pokemon Champions is Out on Mobile! - Full Tour & Battle"** (2026-06-17, **출시 당일**)
    → 모바일 배틀을 화면 캡처해 스트리밍. https://www.youtube.com/watch?v=QnOUEEd-oSA
- **판단 근거**: `FLAG_SECURE`가 켜져 있으면 화면녹화가 검은 화면이 되어 이런 영상 자체가 불가능하다.
  모바일판(스위치 캡처보드 경유 아님) 배틀 화면이 온전히 녹화·업로드되어 있다는 것은 **캡처 차단을 하지 않는다는 강한 증거**.
  커뮤니티/리뷰에서 "검은 화면/캡처 불가" 불평도 발견되지 않음(리뷰어들이 실기기 Galaxy A32 등에서 FPS까지 측정·기록).
- **증거 수준 = 유력**(확정 아님): 특정 화면(예: 결제/특정 컷신)만 부분 차단하는 경우까지는 배제 못 함 →
  실기기에서 대전 화면 직접 녹화로 최종 확인(FIELD_TEST b). **하지만 앱 성립을 막을 전면 차단 가능성은 사실상 배제됨.**
- ※ 한국어 배틀 영상(포랑 등)은 2026-04~05·1440p = **스위치 캡처보드**라 K1 증거로는 쓰지 않음(K2/K3 폰트 참고용).

### K2 (표시명 / ROI) — 판정: **확정**

- **표시명 형식**: 배틀 이름표 = **[종족 아이콘] + [종족명 텍스트] + [성별기호]** / 아래 HP바 / HP%.
  - 관측 문자열은 전부 **폼 접두어·닉네임 없는 base 종족명 단독**: "Hippowdon" "Typhlosion" "Charizard"
    "Torkoal"(영어), **"갸라도스"**(한국어) 등. → **DESIGN.md 가정(base 종족명만) 확정.**
  - ✅ **candidate_index(op.gg 프록시 이름)가 실화면 문자열과 정확히 일치** — 관측 9종 전부 완전일치로 root 해석 성공
    (한국어 갸라도스→gyarados 포함). 표시명 프록시 교정 불필요.
  - ⚠️ 리전폼(예: 히스이 윈디) 문자열이 "윈디"인지 "히스이 윈디"인지는 **샘플에 리전폼 등장 없어 미확인**(실기기 잔여).
    닉네임 노출 사례도 샘플엔 없음(랭크전은 종족명 강제 추정).
- **ROI 위치(실측, 게임 뷰포트 비율)**: 이름표는 **상단 "우측" 영역**(좌하단은 "내" 포켓몬 — 종전 좌/우 대칭 추정은 오류).

  | 형식 | 이름표 박스 x | y | 소스 |
  |---|---|---|---|
  | 싱글(모바일 raw) | 0.72–0.89 | 0.05–0.14 | DM Gaming |
  | 더블 좌 | 0.595–0.755 | 0.03–0.14 | GameXplain |
  | 더블 우 | 0.802–0.995 | 0.04–0.14 | GameXplain |
  | 싱글(한국어) | ~0.71–0.90 | 0.02–0.16 | 포랑(스위치) |

  모바일 raw 캡처의 게임 뷰포트 종횡비 ≈ **2.17:1(≈19.5:9)** — 폭 꽉 채우고 상/하 레터박스.
  실기기 MediaProjection 은 기기 네이티브(대개 20:9 landscape) 캡처 → 프레임 비율 ≈ 뷰포트 비율.
- **코드 교정**: `RoiConfig.DEFAULT_LANDSCAPE_DOUBLES` 를 **우상단 밀집 2개**(좌 0.57–0.78 / 우 0.78–1.00, y 0.02–0.17)로 교정.
  신규 `DEFAULT_LANDSCAPE_SINGLE`(우상단 1개, 0.70–0.92 × 0.02–0.17) 추가. **PIL로 실크롭 시뮬레이션해 5개 샘플 모두 이름표에 정확히 안착 확인.**

### K3 (OCR 실측) — 판정: **에뮬레이터 실측 불가(인프라 한계) / 다운스트림 경로는 검증 완료**

- **계측 하네스 구현**: `app/src/androidTest/OcrFieldTest.kt` — field_samples 5장을 실 파이프라인
  (RoiCropper→OcrEngine→NameMatcher/Repository)에 통과시켜 인식 라인/매칭 root/지연ms 를 logcat 표로 출력.
  androidTest 의존성(runner/ext-junit) 추가. **AVD `Kohana_QA_API_35` 에서 실행됨(빌드·설치·기동 정상).**
- **🚫 막힌 이유(에뮬레이터 인프라)**: 이 ARM64 시스템 이미지의 **GMS 가 ML Kit OCR 온디바이스 모듈을
  다운로드하지 못함**. logcat 확정 증거:
  ```
  ZappDownloader: No successful Zapp module downloads for requested modules
    [VisionOcr.optional, TfliteDynamiteDynamite, MlkitOcrCommon.optional, MlkitOcrKorean.optional]
  Vision: Request for optional module download of ocr failed. Request again.
  DynamiteModule: Local module descriptor class for com.google.mlkit.dynamite.text.latin not found.
  ```
  네트워크·Play Store(vending) 정상인데도 GMS 모듈 배포(Zapp)가 실패 → 모든 recognizer 즉시 예외
  (지연 4~8ms = 실추론 아님). 3분간 재시도 대기해도 실패 반복. **앱 버그 아님 — 에뮬레이터/GMS 인프라 제약.**
  실기기 또는 모듈을 받을 수 있는 완전 Play Store 이미지에서는 하네스가 그대로 동작.
- **다운스트림 대체 검증(순수 JVM, 실행 가능한 수준)**: OCR "이후" 단계를 **실관측 문자열**로 검증.
  `FieldNameMatchTest.kt` — 관측 9종(영어 8 + 한국어 갸라도스) 전부 완전일치 root 해석(거리 0),
  이탤릭 폰트 오인식 모사(Hippowdan/TyphIosion/Charizand) → fuzzy 로 교정됨을 확인.
  → **OCR 라인만 나오면 그 뒤(매칭·표시)는 실화면 이름에 대해 확실히 동작함**을 입증.
- **남은 실기기 항목**: ML Kit 이 이탤릭/기울임+마젠타 배경의 이름표를 실제로 몇 % 읽는지(K3 인식률),
  호출당 지연(목표 100~400ms) — 실기기/Play Store 에뮬레이터에서 `OcrFieldTest` 또는 실배틀 logcat 으로 측정.

### 코드 변경 요약

- `capture/RoiConfig.kt`: 기본 ROI 를 **K2 실측 우상단 배치**로 교정(더블 2개), **싱글용 상수 추가**, 근거 주석/샘플 참조.
- `RoiConfigTest.kt`(+2): 더블 우상단 밀집·싱글 우상단 배치 검증.
- `androidTest/OcrFieldTest.kt`(신규): K3 온디바이스 OCR 실측 하네스(실기기/Play Store 에뮬용).
- `FieldNameMatchTest.kt`(신규, +3): 실관측 문자열의 DB 매칭·fuzzy 교정·표시명 형식 검증.
- `build.gradle.kts`/`libs.versions.toml`: androidTest 러너 의존성.
- `field_samples/`(신규): 실배틀 스크린샷 8장 + 이름표 크롭 3장 + `SOURCES.md`(출처·측정치, 로컬 검증 전용).

### 빌드·테스트 (실제 실행)
- `:app:testDebugUnitTest` → **BUILD SUCCESSFUL, 90/90 통과**(기존 85 + P8 신규 5: RoiConfig 2, FieldNameMatch 3).
- `:app:assembleDebug`(app-debug.apk ~12MB) · `:app:assembleRelease`(R8, app-release.apk ~2.33MB) 성공.
- `:app:connectedDebugAndroidTest`(OcrFieldTest) → AVD 에서 **실행되나 GMS OCR 모듈 미배포로 0/7 인식**(위 인프라 한계). 하네스 자체는 정상 동작.

---

## P9 — ML Kit 번들 전환 + K3 OCR 실측 ✅ 완료 (2026-07-05)

> P8 에서 막힌 K3(에뮬레이터 GMS 가 OCR 모듈 다운로드 실패)를 **bundled ML Kit 전환**으로 해소.
> bundled 는 모델을 APK 에 동봉하므로 런타임 다운로드가 불필요 → 에뮬레이터에서 K3 실측이 뚫림.
> AVD `Kohana_QA_API_35`(비-PlayStore ARM64 이미지)에서 **실측 성공, 7/7(100%) 인식**.

### 1) 번들 전환 (diff 요약)

- **의존성 교체** (`libs.versions.toml`): unbundled → bundled 좌표.
  - `com.google.android.gms:play-services-mlkit-text-recognition-korean/japanese/chinese` → `com.google.mlkit:text-recognition-korean/japanese/chinese` (모두 `16.0.1`)
  - `com.google.android.gms:play-services-mlkit-text-recognition`(19.0.1, latin) → `com.google.mlkit:text-recognition`(`16.0.1`)
- **`OcrEngine` 코드 변경 불필요**: recognizer 옵션 클래스(`KoreanTextRecognizerOptions` 등)는
  bundled/unbundled 공통 패키지 `com.google.mlkit.vision.text.*` 라 import 그대로. 문서 주석만 갱신.
- proguard `-keep class com.google.mlkit.**` 규칙 그대로 유효. minSdk(26) 충돌 없음. **빌드 성공.**

### 2) APK 크기 영향 (실측)

| 빌드 | 전(unbundled) | 후(bundled) | 증가 |
|---|---|---|---|
| debug | 12,275,846 B (11.7 MB) | 57,301,970 B (54.6 MB) | **+42.9 MB** |
| release(R8) | 2,332,940 B (2.22 MB) | 46,599,797 B (44.4 MB) | **+42.2 MB** |

- 증가분 대부분은 **ABI별 네이티브 OCR 파이프라인 `.so` 4벌**:
  `libmlkit_google_ocr_pipeline.so` — x86_64 11.6MB / x86 11.6MB / arm64-v8a 11.1MB / armeabi-v7a 6.8MB ≈ **41MB**.
  나머지 ~2MB 는 4스크립트 tflite/fb 모델(assets/mlkit-google-ocr-models/).
- **release 44.4MB 는 원 2.2MB 대비 과대**(사실상 원인 = 불필요한 3개 ABI 동봉).
  **메모(구현 안 함)**: 실기기는 대개 arm64-v8a 단일 → **abi split 또는 `ndk { abiFilters += "arm64-v8a" }`** 로
  x86/x86_64/armeabi-v7a 제거 시 release ≈ **16~17MB** 로 축소 예상(약 30MB 절감). Play 는 AAB 로 ABI별 split 자동.
  v1 은 실측 우선이라 전 ABI 유지, 배포 시 arm64 전용(+필요시 armeabi-v7a) 로 좁힐 것.

### 3) K3 OCR 실측 (핵심) — `OcrFieldTest` on AVD `Kohana_QA_API_35`

**최종(ROI 교정 후) — 전처리 없음(기본 경로), 7/7 (100%)**:

| 이미지 | roi# | lang | OCR 인식 | 매칭 root | editDist | 지연 ms | 판정 |
|---|---|---|---|---|---|---|---|
| en_single_hippowdon | 0 | en | `Hippowdon` | hippowdon | 0 | 119(초회 워밍업) | OK |
| en_single_hippowdon2 | 0 | en | `Hippowdon` | hippowdon | 0 | 27 | OK |
| en_doubles_typhlosion_charizard | 0 | en | `Typhlosion` | typhlosion | 0 | 28 | OK |
| en_doubles_typhlosion_charizard | 1 | en | `Charizard` | charizard | 0 | 25 | OK |
| en_doubles_typhlosion_torkoal | 0 | en | `Typhlosion` | typhlosion | 0 | 54 | OK |
| en_doubles_typhlosion_torkoal | 1 | en | `Torkoal` | torkoal | 0 | 25 | OK |
| ko_single_gyarados | 0 | ko | `가라도스` | gyarados | 1 | 50 | OK |

- **인식률 7/7 (100%)** · **지연 avg 47ms · p50 28ms · min 25 · max 119ms(초회만)**. 목표 100~400ms 대비 여유.
- 영어(이탤릭/마젠타 이름표) 5종 전부 editDist 0 정확 인식. 한국어 `갸라도스`→`가라도스`(ML Kit 이 ㅑ→ㅏ 1자 오인)
  이지만 **fuzzy 매칭이 root `gyarados` 로 정확 해석**(P8 FieldNameMatch 와 일치).

**⚠️ 초기 측정에서 1건 미인식 발견 → 원인 규명 → 교정** (포장 없이 기록):
- 교정 전(SINGLE ROI bottom=0.17): `en_single_hippowdon2` 가 `'vopmoddy'` 로 오인식(6/7=85%).
- **전처리 튜닝 시도**: `Preprocess.GRAYSCALE_CONTRAST`(그레이스케일+대비강화) → **효과 없음**(여전히 `vopmoddy`).
  → 저대비 문제가 아님을 실측으로 확인.
- **진단 하네스 `k3_diag_hippowdon2_roi_variants`(ROI×전처리 격자)로 실측**:
  - `default(...,0.17)` → MISS (양 전처리 모두), `lower/taller/downshift(bottom 0.20~0.22)` → **`Hippowdon` 복구**(양 전처리 모두).
  - **근본 원인 = ROI 하단 클리핑**: 이 프레임은 이름표가 약간 아래 떠 bottom 0.17 이 텍스트 하단을 잘랐다.
- **교정**: `RoiConfig.DEFAULT_LANDSCAPE_SINGLE` bottom `0.17→0.22`, right `0.92→0.94`(top 0.02 유지). → **7/7 복구.**

### 4) 전처리 반영

- `OcrEngine(language, preprocess=Preprocess.NONE)` 로 **전처리 옵션 파라미터 추가**(기본 NONE=무전처리).
  `Preprocess.GRAYSCALE_CONTRAST` = ColorMatrix 그레이스케일+대비강화(온디바이스 Bitmap 변환) — 실기기 튜닝용 훅.
- **정직 기록**: 이번 샘플에선 GRAYSCALE_CONTRAST 가 인식률을 **올리지 못했다**(중립). 실제 개선은 **ROI 교정**이 냈으므로
  기본 경로엔 ROI 교정만 반영하고, 전처리는 기본 NONE 으로 두되 옵션으로 남겨 실기기 저대비 프레임 대비용으로 유지.
- 유닛테스트 `OcrEngineLogicTest.전처리_기본은_NONE_이고_옵션존재` 추가(+1).

### 5) 회귀 확인 (실제 실행)

- `:app:testDebugUnitTest` → **BUILD SUCCESSFUL, 91/91**(P8 90 + P9 신규 1: Preprocess enum). RoiConfigTest 싱글 bottom 단언(≤0.20→≤0.25) 갱신.
- `:app:assembleDebug`(54.6MB) · `:app:assembleRelease`(R8, 44.4MB) 성공.
- `:app:connectedDebugAndroidTest` → AVD 에서 **k3 실측 2 테스트 통과**(NONE·GRAYSCALE_CONTRAST 패스) + 진단 테스트 통과.
- **데모 스모크(P6 축약)**: bundled debug APK 설치 → 앱 기동 무크래시 → 오버레이 권한 grant → "데모 카드" →
  FGS(ACTION_DEMO, isForeground=true) 기동 → **오버레이 카드 정상 렌더(한카리아스 + Mega토글 + 드래곤/땅 타입칩)**. FATAL 없음.

### 코드 변경 요약

- `gradle/libs.versions.toml`: ML Kit 좌표 unbundled→bundled(`com.google.mlkit:text-recognition*` 16.0.1), 주석 갱신.
- `app/build.gradle.kts`: OCR 의존성 주석 갱신(bundled).
- `ocr/OcrEngine.kt`: `Preprocess` enum + `preprocess` 파라미터 + `applyPreprocess`(ColorMatrix) 추가. 문서 주석 bundled 반영.
- `capture/RoiConfig.kt`: `DEFAULT_LANDSCAPE_SINGLE` bottom 0.17→0.22 / right 0.92→0.94(K3 실측 하단 클리핑 교정).
- `test/OcrEngineLogicTest.kt`(+1): Preprocess 옵션 검증. `test/RoiConfigTest.kt`: 싱글 bottom 단언 갱신.
- `androidTest/OcrFieldTest.kt`: 전처리 파라미터화(runPass) + 전처리 비교 테스트 + hippowdon2 ROI 진단 격자 테스트 추가.

### 빌드·테스트 (실제 실행)
- `:app:testDebugUnitTest` → **BUILD SUCCESSFUL, 91/91 통과**.
- `:app:assembleDebug`(app-debug.apk 54.6MB) · `:app:assembleRelease`(R8, app-release.apk 44.4MB) 성공.
- `:app:connectedDebugAndroidTest`(OcrFieldTest) → **AVD 실측 7/7(100%), 지연 avg 47ms** — K3 관문 통과.

### 남은 실기기 전용 항목 (K3 이관 후 정리)
- **K3 실기기 재확인(옵션)**: 에뮬 swiftshader 소프트 추론이라 지연이 실기기와 다를 수 있음 →
  실기기 실배틀 logcat 으로 지연/발열 재측정 권장(하네스 그대로 사용). 인식률 자체는 에뮬에서 이미 100% 확인.
- **리전폼/닉네임 문자열**(샘플 미등장) · **K1 대전화면 직접 녹화 최종 확인** · **K4 EULA** — P8 잔여 그대로.
- **배포 전 ABI 축소**: arm64-v8a 단일(+필요 시 armeabi-v7a)로 release 크기 ~17MB 로 축소(2)절 메모).

---

## 전체 페이즈 요약

| 페이즈 | 범위 | 핵심 산출 | 테스트 | 상태 |
|---|---|---|---|---|
| P1 | 스캐폴드 + 데이터 레이어 | Gradle/Compose 프로젝트, JSON 3종 파싱, [5]NameMatcher·[6]Repository | 19 | ✅ |
| P2 | 오버레이 셸 + 온보딩 + FGS | [7]OverlayRenderer(WindowManager+Compose), CaptureService(FGS), MainActivity 온보딩 | 27 | ✅ |
| P3 | 캡처 프레임 + OCR 파이프라인 | [1]CaptureManager·[2]FrameGate·[3]RoiCropper·[4]OcrEngine, RecognitionPipeline 통합 | 60 | ✅ |
| P4 | 오버레이 UI 완성 | 3단계 점진공개, 후보선택, 메가토글, 수동검색, 방어상성(TypeChart), 언어설정 | 83 | ✅ |
| P5 | 마무리 + 릴리즈 + 테스트 가이드 | IME 포커스 토글, 캡처중단/미인식 UX, 문자열 2언어화, R8 릴리스 0.1.0, FIELD_TEST/README | 84 | ✅ |
| P6 | 에뮬레이터 런타임 스모크 테스트 | 데모 크래시(SecurityException) 수정, 데모 순환, OCR 로그 개선. 온보딩~오버레이~FGS~실캡처 실구동 검증 | 85 | ✅ |
| P7 | 최종 정밀 리뷰(고위험 경로 2곳) | 캡처 중단→재시작 막다른 상태(BUG-P7-1) 수정. specialUse FGS·오버레이/파이프라인 정리·스레딩 안전 판정. Play specialUse 심사 메모 | 85 | ✅ |
| P8 | 실게임 화면 기반 K1~K3 사전검증 | **K1=GO(유력)**·**K2 표시명/ROI 확정**(우상단 배치 교정)·K3 하네스(에뮬 OCR 인프라한계로 실측만 실기기 잔여). field_samples + OcrFieldTest + FieldNameMatch | 90 | ✅ |
| P9 | ML Kit 번들 전환 + K3 OCR 실측 | **bundled 전환**(런타임 다운로드 제거)·**K3 에뮬 실측 7/7(100%)·지연 avg 47ms**·hippowdon2 ROI 클리핑 교정·전처리 옵션 추가. APK release 2.2→44.4MB(ABI 4벌, 배포 시 축소 메모) | 91 | ✅ |
| P9.1 | release ABI 축소 | release arm64 단일 → **16.6MB** | 91 | ✅ |
| P10 | 전체 파이프라인 E2E + K4 EULA | **앱 자체 캡처→OCR→오버레이 E2E 3/3(카드 4장) 정답**(더블 2장 포함)·debug 전용 배경 이미지 액티비티·diag 로그(release 무음)·**K4 판정(캐주얼 불명확/대회 금지)+앱 내 고지 반영** | 91 | ✅ |
| P11 | 동적 배틀 E2E + 브랜딩 | **동적 장면전환 E2E**(카드 갱신·연출프레임 무깜빡임·메뉴 오탐0·실배틀 프레임 정확)·**저신뢰 전환 히스테리시스**(오인식 스팸 구조 차단)·**어댑티브 앱 아이콘**(카드+타입칩 모티브)·en 라벨 `PokeChamps Supporter`·실배틀 영상 확보(brew yt-dlp) | 99 | ✅ |
| P12 | 전체 코드베이스 적대적 리뷰 + ROI 강건화 | 메가선택 인덱스 누수(BUG-P12-1)·데모 FGS API34 가드(BUG-P12-2) 수정·**multi-line matchBest + ROI 밴드확장**(Sylveon 실배틀 프레임 신규 통과, OcrFieldTest 9/9) | 107 | ✅ |
| P13 | 원격 데이터 갱신(manifest) + git init | **정적 호스팅+manifest 버전체크**(build_release.py gzip+sha256, DbUpdateManager HttpURLConnection·원자교체, filesDir 우선/assets 폴백)·**에뮬 E2E 다운로드→재시작 후 다운로드본 로드 실측**·INTERNET 권한·git init 3커밋 | 125 | ✅ |

### 최종 상태 — 🚩 필드테스트 준비 완료 (K1 유력 GO · K2 확정 · **K3 에뮬 실측 통과** · **E2E 실캡처 경로 통과** · K4 판정+고지 완료)
**코드 + 에뮬레이터 런타임 + 고위험 경로 리뷰 + 실게임 자료 기반 선검증(P8)까지 완료.**
컴파일·유닛테스트(**90/90**)·debug/release APK + 에뮬레이터 무크래시(P6) + 고위험 경로 수정(P7) +
**웹 실게임 녹화로 K1(캡처 차단 안 함, 유력)·K2(표시명 base 종족명·ROI 우상단 실측 교정) 확정(P8)**.
- **K1**: 모바일 화면녹화 게임플레이 존재 = 캡처 차단 안 함의 강한 증거 → **앱 성립 관문 사실상 통과**(실기기 최종 확인만).
- **K2**: 실화면 이름표 = base 종족명, candidate_index 와 일치(교정 불필요). ROI 는 **우상단 배치로 코드 교정**.
- **K3**: ~~에뮬 GMS 모듈 미배포로 불가~~ → **P9 에서 bundled 전환으로 해소, AVD 실측 7/7(100%)·지연 avg 47ms 통과.**
  (bundled = 모델 APK 동봉, 런타임 다운로드 불필요.) 실기기 지연/발열 재확인만 옵션으로 남음.

- **E2E(P10)**: ~~하네스만~~ → **앱이 MediaProjection 으로 실제 화면을 스스로 캡처→OCR→오버레이 갱신하는 완전 경로를 에뮬 실구동으로 검증(3/3, 더블 카드 2장 포함).**
- **K4(P10)**: EULA/대회 규정 조사 완료 → **캐주얼=불명확(리스크), 공인 대회=금지(§2.7 종이 타입표조차 금지)**. 앱 온보딩에 경고 고지 반영.

**남은 실기기 전용 항목**: K1 최종 확인(대전 화면 직접 녹화) · K3 실기기 지연/발열 재확인(옵션, 인식률은 에뮬 100% 확인) ·
**실배틀(움직이는 화면) FrameGate 거동** · 리전폼/닉네임 문자열 확인 · Champions-verbatim EULA 원문(실브라우저). 절차는 [FIELD_TEST.md](FIELD_TEST.md).
배포 단계 주의: Play `specialUse` FGS 사유 콘솔 심사 필요(데모 경로).

## P9.1 — release ABI 축소 (2026-07-05, 메인 세션)
- `app/build.gradle.kts` release에 `ndk { abiFilters += "arm64-v8a" }` 적용 (P9 메모 실행).
- release APK **44.4MB → 16.6MB** (arm64 단일, `unzip -l`로 lib/arm64-v8a만 포함 확인).
- 유닛테스트 **91/91** 재통과(`--rerun-tasks`, failures 0), assembleRelease 성공.
- Play 배포 시엔 필터 제거 후 AAB 전환할 것(주석에 기재).

---

## P10 — 에뮬레이터 전체 파이프라인 E2E + K4 EULA 조사 ✅ 완료 (2026-07-05)

> 지금까지 OCR 은 하네스(이미지 직접 주입)로만 검증했다. P10 은 **앱이 실제 화면을 MediaProjection 으로
> 스스로 캡처 → OCR → 매칭 → 오버레이 카드 갱신**하는 완전한 흐름을 에뮬레이터에서 실측 검증했다.
> AVD `Kohana_QA_API_35` 를 16:9 가로(1280×720)로 두고, field_samples 실배틀 스크린샷을
> 전체화면으로 띄운 뒤 앱이 그 화면을 캡처해 오버레이를 갱신하는지 확인.

### 작업 1 — 전체 파이프라인 E2E (핵심)

**검증 방법(에뮬 세로/종횡비 문제 해결):**
- **디버그 전용 배경 이미지 액티비티** `debug/SampleImageActivity`(디버그 소스셋 `app/src/debug/`, 릴리즈/메인 흐름 비침투).
  field_samples 이미지를 **가로 immersive 전체화면**(`FIT_XY`)으로 표시. 자체 task(`taskAffinity`+`singleTask`)라
  MainActivity/동의창을 거친 뒤에도 `NEW_TASK|REORDER_TO_FRONT`로 배경만 앞으로 가져올 수 있음.
- **에뮬 종횡비 정합**: 배틀 이미지는 16:9(1280×720). AVD 를 `wm size 1280x720` + `user_rotation 1`(가로)로 맞춰
  캡처 프레임 = 샘플과 **동일 종횡비** → `FIT_XY` 로 표시해도 ROI(비율 기준)가 어긋나지 않음(레터박스 없음).
- **E2E 시퀀스(자동화)**: MainActivity(세로 온보딩) → `Start (screen capture)` 탭 → **MediaProjection 동의창**
  (스피너 `Entire screen` 선택 → `Start` 버튼, uiautomator dump 로 좌표 산출) → 캡처 세션 확보 →
  배경 이미지 앞으로 → 앱이 캡처 프레임을 파이프라인에 통과 → 오버레이 카드 갱신.
- 언어는 `app_settings.xml`(SharedPreferences)를 `run-as` 로 직접 써서 지정(en/ko).
- 증거: `adb screencap`(오버레이 렌더) + `logcat -s RecognitionPipeline`(신규 `diag` 로그: ROI별 OCR 라인·매칭 root·editDist).

**🐛 발견·수정: 파이프라인 관찰 불가(진단 로그 부재).**
- 초기 E2E 에서 캡처는 도는데 오버레이가 안 뜨는 원인을 볼 수 없었음(파이프라인은 실패시에만 로깅).
- **수정**: `RecognitionPipeline.processCrop` 에 `BuildConfig.DEBUG` 게이트 `diag` 로그 추가
  (ROI별 OCR 라인/매칭 결과). `buildConfig=true` 활성화. **release 는 `BuildConfig.DEBUG=false` 라 무음** — 배포 영향 0.
  → 이 로그로 "캡처는 되지만 정적 이미지라 첫 프레임 후 FrameGate 가 동일 프레임을 막고, 이후 배경 전환 시 재인식" 흐름을 실측 확인.

**E2E 결과표 (앱이 스스로 캡처·인식·표시):**

| 표시 이미지 | 배틀형식 | 캡처 프레임 | 오버레이에 뜬 포켓몬(카드) | OCR 라인 | 매칭 root(editDist) | 정답 | 증거 |
|---|---|---|---|---|---|---|---|
| en_single_hippowdon | 싱글(en) | 640×360 | **Hippowdon** (땅) | `Hippovdos` | hippowdon (2) | ✅ | e2e_hippo_final.png |
| en_doubles_typhlosion_charizard | 더블(en) | 640×360 | **Typhlosion**(불꽃/고스트) + **Charizard**(불꽃/비행, Mega) — **카드 2장** | `Typhlosion` / `Charizard` | typhlosion (0) / charizard (0) | ✅✅ | e2e_e2e_doubles.png |
| ko_single_gyarados | 싱글(ko) | 640×360 | **갸라도스**(물/비행) | `가라도스` | gyarados (1) | ✅ | e2e_e2e_gyarados.png |

- **3/3 시나리오(카드 총 4장) 전부 정답.** 더블 화면에서 **오버레이 카드가 실제로 2장** 뜨는 것 확인(ROI#0/#1 각각 갱신).
- OCR 오인식(`Hippovdos`, 한글 `가라도스`)도 **fuzzy 매칭이 정확한 root 로 교정**(P9 실측과 일치) — E2E 경로에서도 재현.
- 소요시간: 캡처 세션 확보 후 배경 표시 시점부터 카드 갱신까지 수 초 내(정적 이미지 특성상 FrameGate 통과=배경 전환 직후 1프레임).
  OCR 호출 자체 지연은 P9 실측 avg 47ms(에뮬 소프트추론) 그대로 유효.

**의미**: 지금까지 하네스로만 입증한 "이미지→인식"을, **앱이 MediaProjection 으로 실제 화면을 스스로 캡처하는 완전한 런타임 경로**로
끝까지 검증. 오버레이 창(`TYPE_APPLICATION_OVERLAY`)이 캡처된 배틀 화면 위에 정확한 카드를 렌더함을 스크린샷으로 확인.

**에뮬 제약으로 남은 부분(정직 기록)**:
- 실제 게임(포챔스) 미설치 → **실배틀 애니메이션/실시간 프레임 흐름**(정적 이미지가 아닌 움직이는 화면에서의 FrameGate 거동)은
  실기기 잔여. 단, 정지 프레임 기준 인식·표시 경로는 완전 검증됨.
- 에뮬 소프트(swiftshader) 추론이라 실기기 지연/발열은 별도(P9 남은 항목과 동일).

### 작업 2 — K4 EULA/약관 조사 — 판정: **불명확(캐주얼) / 금지(공인 대회)**

WebSearch/WebFetch 로 포켓몬사(TPCi) 약관·Play! Pokémon 대회 규정 조사.

**근거(발췌·URL):**
- TPCi **Terms of Use** §5: *"Use, or facilitate the use of, any unauthorized third-party software (e.g. bots, mods, hacks, and scripts) … to modify or automate operation within the Service"* / §7 sole-discretion 계정 정지·해지 조항. https://www.pokemon.com/us/legal/terms-of-use
- **Play! Pokémon VG Rules** §2.7: *"No written or printed aids, including type charts, are permitted in the play space."* / §4.3: *"The use of external devices, such as a mobile app … is expressly forbidden."* https://www.pokemon.com/static-assets/content-assets/cms2/pdf/play-pokemon/rules/play-pokemon-vg-rules-formats-and-penalty-guidelines-en.pdf
- **Champions = 공식 VGC 플랫폼 전환**(2026): https://www.pokemon.com/us/news/play-pokemon-competitions-transition-to-pokemon-champions-on-april-and-may-2026
- Champions 전용 EULA 페이지(`web-view.app.pokemonchampions.jp/docs/terms/`)는 JS 렌더라 헤드리스 추출 불가 — Champions-verbatim 조항은 미확보(실브라우저 필요). 위 조항들은 TPCi 공통 약관 + Champions 에 적용되는 대회 규정.

**판정:**
- **캐주얼/래더 플레이 → 불명확(리스크 있음).** 본 앱은 메모리 읽기·입력 자동화·게임 변조가 없어 §5 의 핵심(bots/mods/hacks/scripts로 *modify/automate*)에는
  문자적으로 해당 안 될 여지가 크나, "unauthorized third-party software" 문구가 넓고 §7 재량 해지가 있어 **완전 안전이라 단정 불가**.
- **공인 대회(랭크전/Play! Pokémon/VGC) → 금지(명확).** §2.7 은 **종이 타입표조차** 금지 → 실시간 오버레이는 명백한 위반, 실격 사유.
- **결론**: "화면 공개 정보만 표시·무변조"는 최선의 법적 포지션이나, **대회 사용은 명백 금지 + 캐주얼도 회색지대**. 반드시 고지 필요.

**앱 반영**: `strings.xml`(ko/en)에 `onboarding_legal_*` 고지 추가 → MainActivity 온보딩 인트로 카드 아래 **경고 카드** 렌더.
문구 요지: "화면 공개 정보만 표시·무변조 / 단 TPCi 약관·대회 규정상 비인가 서드파티 SW 광범위 제한, 공인 대회 사용은 실격 가능 /
캐주얼·연습 전용, 공식·대회 시 앱 종료, 제재 위험 본인 책임."

### 코드 변경 요약
- `app/src/debug/AndroidManifest.xml`(신규): 디버그 전용 `SampleImageActivity`(가로·singleTask·자체 taskAffinity·excludeFromRecents).
- `app/src/debug/java/.../debug/SampleImageActivity.kt`(신규): field_samples 이미지 immersive 전체화면 표시(`onNewIntent` 로 배경 교체).
- `capture/RecognitionPipeline.kt`: `BuildConfig.DEBUG` 게이트 `diag` 로그(ROI별 OCR 라인/매칭) — release 무음.
- `app/build.gradle.kts`: `buildConfig = true` 활성화(진단 로그 게이트용).
- `res/values/strings.xml`·`values-en/strings.xml`: `onboarding_legal_title/body`(K4 고지) 추가.
- `ui/MainActivity.kt`: 인트로 아래 법적 고지 `InfoCard` 렌더.

### 빌드·테스트 (실제 실행)
- `:app:testDebugUnitTest --rerun-tasks` → **BUILD SUCCESSFUL, 91/91, failures 0**(신규 문자열/디버그액티비티는 테스트 무영향, 회귀 0).
- `:app:assembleDebug`(54.6MB, SampleImageActivity 포함) · `:app:assembleRelease`(R8, arm64, **16.5MB**, SampleImageActivity **부재 확인**) 성공.
- E2E: AVD 실구동으로 **앱 자체 캡처→OCR→오버레이 3/3 시나리오(카드 4장) 정답**.

### 남은 실기기 전용 항목
- 실배틀(움직이는 화면) FrameGate 거동·지연/발열 재확인 · K1 대전화면 직접 녹화 최종 확인 · 리전폼/닉네임 문자열 · 배포 전 Champions-verbatim EULA 원문 확인(실브라우저).

---

## P11 — 움직이는 배틀 영상 동적 E2E + 앱 브랜딩 ✅ 완료 (2026-07-05)

> P10 은 **정지 프레임**만 검증했다. P11 은 **화면이 바뀌는 동적 상황**(장면 전환·연출/이펙트 프레임)에서
> 파이프라인이 깜빡임/오인식 스팸 없이 안정적으로 카드를 갱신하는지 실측 검증했다.
> AVD `Kohana_QA_API_35`(1280×720 가로), 앱이 MediaProjection 으로 스스로 캡처하는 완전 경로.

### 작업 1 — 동적 배틀 E2E (핵심)

**배틀 영상 확보(정직 기록):**
- 저장소 기본 `yt-dlp`(pip 2023.11.16, Python 3.7) → 현재 YouTube에서 400/스토리보드-only 로 **실패**. Python 3.7 이 최신 yt-dlp 미지원.
- **`brew yt-dlp 2026.06.09` 으로 실제 다운로드 성공** — SOURCES.md 의 **DM Gaming(yWHjy1Pp3PU)** 배틀 영상을
  `--download-sections` 로 구간 확보(720p·16:9·59.94fps, 실재 확인). `field_samples/video/` 저장(출처 기록).
  - 이 영상에서 **실배틀 커맨드 프레임**(상대 Sylveon·Hippowdon 이름표) 추출 → `real_battle_frames/`.
- **에뮬 VideoView 재생 불가**: API 35 스코프드 스토리지로 앱 프로세스(mediaserver)가 `/sdcard` mp4 를 열지 못함(setDataSource 예외, codec 아님).
  → 태스크 허용 대안 **"정지/합성 프레임 슬라이드쇼"** 로 동적 거동 검증. 프레임을 `run-as` 로 앱 **내부 저장소**에 넣어
  `SampleImageActivity`(ImageView, `onNewIntent` 배경 교체)로 빠르게 전환. 실배틀 프레임 + 합성 시퀀스 모두 사용.
  - 합성 시퀀스(`synthetic_battle_compat.mp4` 원천 프레임): Hippowdon → **fadeblack 연출** → Typhlosion+Charizard(더블)
    → **dissolve 연출** → Typhlosion+Torkoal. **정답 타임라인을 내가 통제**하므로 깜빡임/전환/오인식을 정밀 측정 가능.

**동적 검증 결과 (앱 자체 캡처→OCR→오버레이, logcat `diag` 실측):**

| 관찰 항목 | 결과 |
|---|---|
| **OCR 실행 빈도(스로틀)** | 16.7s 동안 총 **18회 OCR = 1.08회/s(≈0.54회/ROI/s)**. FrameGate(min 700ms/ROI + 정지프레임 게이팅)로 과도호출 없음. 임계 조정 불필요 |
| **장면 전환 시 카드 갱신** | 더블 우측 슬롯 Charizard→**Torkoal** editDist 0 전환, 좌측 Typhlosion 은 **깜빡임 없이 유지**. 스크린샷 확인 |
| **연출/이펙트 프레임 안정성** | fadeblack/blur 프레임에서 OCR=`-`(빈값)→NoMatch→**직전 카드 유지**. 엉뚱한 카드로 뒤집힘 **0건**. 스크린샷 확인 |
| **메뉴 프레임 오탐** | `Battle Data`/`12,288 VP` 등 메뉴 텍스트 → 전부 NoMatch(카드 안 뜸). 오탐 0 |
| **실배틀 프레임(실영상)** | 실 DM Gaming 프레임 **Hippowdon editDist 0** 정확 인식 → "Hippowdon(땅)" 카드 렌더 |
| **더블 2카드** | Typhlosion(불꽃/고스트)+Charizard(불꽃/비행·Mega) **오버레이 카드 2장** 동시 렌더 확인 |

**🐛 발견 & 수정 — 저신뢰 전환 히스테리시스(오인식 억제 선제 방어):**
- 관찰 결과 이번 샘플에선 이펙트 프레임이 대부분 OCR=`-`(빈값)이라 오인식 스팸이 실측되진 않았다(안전).
  그러나 파이프라인 코드상 `PipelineDecider` 는 매칭 key 가 바뀌면 **무조건 즉시 카드 교체** → 실기기에서 이펙트/카메라 회전 프레임이
  **약매칭(editDistance 큰 fuzzy)으로 다른 포켓몬**에 걸리면 카드가 깜빡일 위험이 구조적으로 존재.
- **수정**: `PipelineDecider` 에 **신뢰도 기반 히스테리시스** 추가.
  - 고신뢰 전환(`editDistance ≤ 1`, 정상 이름표) → **즉시 교체**(실측 한글 1자 오인식 `갸→가` 등 정상 인식 즉시 반영 유지).
  - **약매칭으로 *다른 root* 전환** → **연속 2회 동일 관측 시에만** 교체(단발 오인식은 무시, 카드 유지).
  - 최초 카드 취득은 지연 없이 즉시 표시. 슬롯별 독립. → **깜빡임/오인식 스팸을 구조적으로 차단.**
- **유닛테스트 +8**(`PipelineDeciderHysteresisTest`): 최초취득 즉시표시 / 단발 오인식 유지 / 연속2회 전환 /
  고신뢰 즉시전환 / editDist1 즉시전환 / 흔들림 카운트리셋 / 정상복귀 시 대기취소 / 더블 슬롯 독립.

**정직한 한계(실기기 잔여):**
- 실 DM Gaming 프레임 중 **Sylveon** 은 이 특정 레터박스 캡처에서 이름표 y위치가 P10 캘리브레이션 ROI와 미세하게 어긋나 OCR=`-`(미인식).
  같은 영상 Hippowdon 은 editDist 0 정확. → **파이프라인 결함 아님, 레터박스별 ROI 위치 편차**(기존 실기기 잔여 항목). 미인식은 **NoMatch→카드유지**로 안전 열화(엉뚱카드 아님).
- 에뮬 VideoView 재생 불가로 **연속 프레임 스트림**(59.94fps 실시간) 자체는 미검증 — 슬라이드쇼(장면당 1.3~4s)로 대체. 실기기 실시간 프레임/발열은 잔여.

### 작업 2 — 앱 브랜딩

- **앱 아이콘(어댑티브)**: 기본 아이콘 → **오리지널 벡터 어댑티브 아이콘** 제작(포켓몬 저작물 무사용).
  - 모티브 = 앱 실제 오버레이 **"정보 카드 + 타입칩"** 실루엣(어두운 카드 #1A1A1A + 액센트 블루 이름줄 + 파랑/초록 타입칩).
  - `drawable/ic_launcher_background.xml`(액센트 블루 대각 그라디언트) + `drawable/ic_launcher_foreground.xml`(카드+칩) + `mipmap-anydpi-v26/ic_launcher(_round).xml`(+monochrome 레이어). 벡터라 전 밀도 자동. minSdk 26 = 어댑티브 상시 지원.
  - **에뮬 런처 스크린샷으로 렌더 확인**(원형 마스크 안에 카드+타입칩 모티브, 타 아이콘과 구별). release APK 에도 포함 확인(`res/BW.xml`=R8 리네임 어댑티브 아이콘).
- **앱 이름**: launcher label 정리 — 기본 `포챔스 서포터`, **en `PokeChamps Supporter`**(기존 `Pochamps`→`PokeChamps` 통일). 릴리스 badging 확인.

### 코드 변경 요약
- `capture/PipelineDecider.kt`: 저신뢰 전환 히스테리시스(`confirmSwitch`, `lastRootByRoi`/`pendingSwitchByRoi`, `CONFIDENT_EDIT_DISTANCE=1`/`SWITCH_CONFIRM_COUNT=2`). `reset()` 신규 상태 초기화.
- `test/PipelineDeciderHysteresisTest.kt`(신규, +8): 히스테리시스 검증.
- `debug/SampleImageActivity.kt`: **VideoView 영상 재생 경로 추가**(`--es vid`, 무음 루프, `Uri.fromFile`). 디버그 전용 유지.
- `res/drawable/ic_launcher_{background,foreground}.xml`·`res/mipmap-anydpi-v26/ic_launcher{,_round}.xml`·`res/values/ic_launcher_background.xml`(신규): 어댑티브 아이콘.
- `AndroidManifest.xml`: `android:icon`/`roundIcon` 지정.
- `res/values-en/strings.xml`: app_name/onboarding_title `Pochamps`→`PokeChamps Supporter`.
- `field_samples/video/`(신규): 실배틀 영상 2 + 실배틀 프레임 2 + 합성 배틀 영상 + SOURCES.md.

### 빌드·테스트 (실제 실행)
- `:app:testDebugUnitTest` → **BUILD SUCCESSFUL, 99/99, failures 0**(P10 91 + P11 히스테리시스 8).
- `:app:assembleDebug`(VideoView·아이콘 포함) · `:app:assembleRelease`(R8, arm64, **16.6MB**) 성공. **SampleImageActivity release 미포함 재확인**, diag release 무음(BuildConfig.DEBUG=false), 어댑티브 아이콘 release 포함.
- 동적 E2E: AVD 실구동 — **장면 전환 카드 갱신·연출 프레임 무깜빡임·메뉴 오탐 0·실배틀 프레임 정확 인식** 스크린샷/로그 확인.

### 남은 실기기 전용 항목
- 실기기 실배틀 **59.94fps 실시간 프레임 스트림**에서의 FrameGate/지연/발열(에뮬 VideoView 불가로 슬라이드쇼 대체함) · 레터박스별 ROI 위치 편차(Sylveon 케이스) 실기기 미세보정 · K1 대전화면 직접 녹화 최종 확인 · 리전폼/닉네임 문자열 · Champions-verbatim EULA 원문(실브라우저).

---

## P12 — 전체 코드베이스 적대적 리뷰 + ROI 강건화 ✅ 완료 (2026-07-05)

> P7 은 고위험 경로 2곳만 봤다. P12 는 `com/pochamps/supporter/` **전체**를 동시성/수명주기·에러경로·엣지데이터·
> Android 버전분기 렌즈로 정독하고, **실제 실패 시나리오를 구성할 수 있는 버그만** 수집·수정했다.
> 또 P11 이 남긴 "Sylveon 프레임 미인식(레터박스 y 편차 추정)"을 실측으로 규명하고 ROI 를 강건화했다.

### 작업 1 — 전체 코드베이스 적대적 리뷰

전 파일 정독(Explore 서브에이전트 광범위 스캔 + 핵심 경로 직접 정독으로 교차검증). 서브에이전트가 올린
후보 10건 중 **코드로 실제 트리거 가능한 것만** 채택하고, 방어코드(`?.`/`runCatching`/coerce)로 이미 안전하거나
데이터가 물리적으로 불가능한 후보(예: RGBA_8888 pixelStride=0, `candidatesOfRoot` 는 `?: emptyList()` 로 이미 안전)는
**정직히 기각**했다. 확정 발견 2건 + 수정, 각각 회귀 테스트.

**🐛 BUG-P12-1 — 슬롯 카드 교체 시 메가 선택 인덱스 누수(오표시).** [real-bug]
- 파일: `overlay/OverlayRenderer.kt` `updateSlot`(구 173행).
- **실패 시나리오**: 더블배틀 한 슬롯이 **Charizard** 인식 → 유저가 [메가 X] 토글(megaSel=0). 그 슬롯의 상대가
  **Gengar**(메가 1종 존재)로 교체됨 → `updateSlot` 이 `megaSelBySlot[slot]` 을 **null 일 때만** 초기화하므로
  0이 그대로 남음 → `effectiveCard` 가 `0 < gengarMegaForms.size(1)` = true → **유저가 토글한 적 없는데 Mega Gengar 카드가 표시**됨.
  (주석은 "key 바뀌면 초기화"라 되어 있었으나 코드가 null-check 만 함 — 주석/코드 불일치.) 메가 보유 73종 → 더블 슬롯 교체 시 흔히 재현.
- **수정**: 카드 갱신 시 `prevKey != data.key` 면 megaSel 을 base(-1)로 초기화. 순수 결정 로직을 `MegaSelection.resolveOnUpdate`
  (Android 무의존)로 추출해 JVM 테스트 가능화.
- **회귀 테스트(+3)** `MegaSelectionTest`: 최초표시=base / 같은포켓몬 선택보존 / **다른포켓몬 교체 시 base 초기화**.

**🐛 BUG-P12-2 — 데모 FGS `specialUse` 타입의 API 가드 누락(API 29~33 크래시).** [real-bug · Android 버전분기]
- 파일: `capture/CaptureService.kt` `startForegroundWithNotification`.
- **실패 시나리오**: 데모 경로가 `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE`(**API 34** 추가)를 `SDK_INT >= Q`(29)
  가드로만 사용. minSdk=26 이라 **API 29~33 실기기에서 "데모 카드" 탭 시**, 매니페스트에 (해당 API 레벨에서) 선언되지 않은
  FGS 타입이라 `IllegalArgumentException` 으로 크래시.
- **수정**: specialUse 는 `SDK_INT >= UPSIDE_DOWN_CAKE`(34)에서만 사용, 그 미만 데모는 `mediaProjection` 타입으로 시작
  (mediaProjection 의 "실 프로젝션 토큰 요구"는 API 34 에서 추가된 제약이라 29~33 에선 토큰 없이도 허용). 실캡처 경로는 무변경.

**기각한 후보(정직 기록)**: Handler onStop-after-destroy(전부 `?.`/`runCatching` 로 null-safe, 크래시 아님) ·
CaptureManager pixelStride=0(RGBA_8888 는 항상 4) · `candidatesOfRoot` 0후보(이미 `?: emptyList()`) ·
NameMatcher root 부재 시 NoMatch(malformed 데이터 전용, 정상 데이터로 트리거 불가) · SharedPreferences race(apply 비동기지만
"다음 세션부터 적용"이 설계 의도). → **스타일/이론적 결함이 아닌 실트리거 버그만 2건 채택.**

### 작업 2 — ROI 강건화 (실패 샘플 기반 실측)

**규명: P11 의 "Sylveon 레터박스 y 편차" 가설은 실측으로 반증됐다.**
- `field_samples/video/real_battle_frames/` 의 실패 Sylveon 프레임을 **실 파이프라인(RoiCropper→OcrEngine→matcher)** 에
  통과(에뮬 `OcrFieldTest#p12_diag_...`). **현재 SINGLE ROI 로도 Sylveon = 'Sylveon' editDist 0 정상 인식**(Hippowdon 과 동일).
  → 정지 프레임 기준 ROI 문제 아님. P11 미인식은 실캡처 경로의 다운스케일/전환 프레임 특성으로 추정(정지 프레임은 완전 인식).
- **오히려** ROI 세로밴드를 단순 확장하면 **악화**됨을 실측: 확장 밴드에 인접 UI `MOVE TIME 45`/`Battle Info` 가 들어오고,
  `OcrEngine.pickNameLine` 이 letter/digit 이 더 많은 UI 라인을 이름으로 **오선택** → NoMatch(Sylveon/Hippowdon 둘 다 MISS).

**대책(단순함 우선, 실측 기반): 다중 라인 매칭 `matchBest` + 그 위에서 세로밴드 확장.**
- `OcrEngine.recognizeAllLines` + `PokedexRepository.matchBest(lines)` 추가: 크롭의 **모든 OCR 라인**을 매칭기에 넣고
  **최소 editDistance 매칭 라인**을 채택. 사전 매칭이 UI 텍스트(`MOVE TIME 45` 등)를 자동으로 걸러주므로 ROI 를 넓혀도 안전.
  `RecognitionPipeline.processCrop` 을 이 경로로 전환(프로덕션·하네스 동일 경로).
- 이제 안전하므로 **ROI 세로밴드 확장**: SINGLE `bottom 0.22→0.30`, DOUBLES `bottom 0.17→0.24`
  (장면별 이름표 y 편차·레터박스 흡수). `RoiConfigTest` 상한 동반 갱신.
- **회귀 테스트(+5)** `MatchBestTest`: 인접 UI 함께 있어도 종족명 채택 / 순서무관 최소거리 / 전부 NoMatch / 빈 리스트 / 단일 라인 유지.

**ROI 재실측표 (에뮬 `Kohana_QA_API_35`, 실 파이프라인, 전=P11 단일라인·구ROI / 후=P12 matchBest·확장ROI):**

| 샘플 | 전(single pickNameLine + 구 ROI) | 후(matchBest + 확장 ROI) |
|---|---|---|
| en_single_hippowdon | OK (d0) | **OK (d0)** |
| en_single_hippowdon2 | OK (d0) | **OK (d0)** |
| en_doubles_typhlosion_charizard #0 | OK (d0) | **OK (d0)** |
| en_doubles_typhlosion_charizard #1 | OK (d0) | **OK (d0)** |
| en_doubles_typhlosion_torkoal #0 | OK (d0) | **OK (d0)** |
| en_doubles_typhlosion_torkoal #1 | OK (d0) | **OK (d0)** |
| ko_single_gyarados | OK (d1) | **OK (d1)** |
| **real_sylveon_battle (P11 미인식 케이스)** | — (신규) | **OK (d0)** ✅ |
| **real_hippowdon_moveselect** | — (신규) | **OK (d0)** ✅ |
| **합계** | 7/7 | **9/9 (100%)** |

- **기존 7샘플 100% 유지 + Sylveon/Hippowdon 실배틀 프레임 신규 통과.** 회귀(기존 악화) 0.
- **밴드 확장 강건성 실측**(`p12_diag_real_battle_letterbox_variants`): current/tallerBottom(0.30)/wideBand(0.98,0.30)/wideBandTall(1.0,0.34)
  **4개 밴드 전부** Sylveon·Hippowdon editDist≤1 OK. matchBest 전에는 확장 밴드 3/4 가 `MOVE TIME 45` 로 MISS 였음.
- OCR 호출 지연은 matchBest 추가로 불변(라인 매칭은 마이크로초; 지연은 순수 ML Kit 추론). 밴드 확장으로 OCR 빈도 예산 영향 없음.

### 코드 변경 요약
- `overlay/OverlayRenderer.kt`: `updateSlot` 메가선택 누수 수정 + `MegaSelection.resolveOnUpdate`(순수) 추출.
- `capture/CaptureService.kt`: 데모 FGS 타입 API34 가드(specialUse↔mediaProjection).
- `ocr/OcrEngine.kt`: `recognizeAllLines` 추가.
- `data/PokedexRepository.kt`: `matchBest(lines)` 추가(최소 editDistance 라인 채택).
- `capture/RecognitionPipeline.kt`: `processCrop` 을 recognizeAllLines→matchBest 경로로 전환.
- `capture/RoiConfig.kt`: SINGLE bottom 0.22→0.30, DOUBLES bottom 0.17→0.24(세로밴드 확장).
- 테스트: `MegaSelectionTest`(+3)·`MatchBestTest`(+5) 신규, `RoiConfigTest` 상한 갱신, `OcrFieldTest` 프로덕션 경로 미러링 + 실배틀 2샘플 추가 + P12 밴드 진단.
- `androidTest/assets/field_samples/`: real_sylveon_battle.jpg·real_hippowdon_moveselect.jpg 추가.

### 빌드·테스트 (실제 실행)
- `:app:testDebugUnitTest --rerun-tasks` → **BUILD SUCCESSFUL, 107/107, failures 0**(P11 99 + P12 8: MegaSelection 3 + MatchBest 5).
- `:app:connectedDebugAndroidTest`(에뮬) → OcrFieldTest **9/9(100%)**, P12 밴드 진단 전 밴드 OK.
- `:app:assembleRelease`(R8, arm64) → **16.6MB, SampleImageActivity release 미포함 재확인**. 클린.

### 남은 실기기 전용 항목(불변)
- 실기기 59.94fps 실시간 프레임 스트림 FrameGate/지연/발열 · K1 대전화면 직접 녹화 최종 확인 · 리전폼/닉네임 문자열 ·
  Champions-verbatim EULA 원문(실브라우저). (Sylveon "레터박스 y 편차"는 P12 에서 정지프레임 인식 규명 + matchBest·밴드확장으로 강건화 완료.)

---

## P13 — 원격 데이터 갱신(manifest 방식) + git 저장소 초기화 ✅ 완료 (2026-07-05)

> DESIGN.md 4-6 갱신 전략 구현: **정적 호스팅 + manifest 버전 체크**로 앱 재설치 없이 데이터만 교체.
> 서버 연산 0(정적 파일 서빙 + 온디바이스) → 서버비 0원 유지. 오프라인·실패 시 내장본 폴백 보장.
> 데이터가 자주 안 바뀌므로 **v0.1 은 수동 "데이터 업데이트" 버튼만**(자동 백그라운드 체크 미도입).

### 1) 호스팅 산출물 파이프라인 (`data/build_release.py`)
- JSON 3종을 **gzip(level 9)** 압축 + `manifest.json`(manifestSchema/dataVersion/generatedAt/files[])으로 패키징 → `data/dist/`.
  - `dataVersion` = 인자 미지정 시 오늘 날짜스탬프(YYYYMMDD), `--version` 으로 정수/문자열 명시 가능.
  - 파일별 `{name, url(gz), sha256(**해제 후 원본** 기준), size, gzipSize}`.
- 실측: pokedex 989KB→178KB, usage 1.80MB→150KB, candidate 172KB→34KB. **총 2.9MB → 361KB gz.**
- `data/dist/` 를 그대로 GitHub/Cloudflare Pages 에 올리면 됨(README 운영 절차 절 추가).

### 2) 앱 측 갱신 구현
- **`DbManifest`(순수 JVM)**: manifest 파싱(`ignoreUnknownKeys`) + `isNewer(remote, local)` 버전 비교
  (둘 다 정수면 정수 비교, 아니면 사전순 — 날짜스탬프도 사전순=시간순이라 안전; local=null 이면 갱신). 스키마/필수파일 검증.
- **`DbFiles`(순수 JVM, File 기반)**: `sha256Hex`/`gunzip`/원자교체(`promoteAtomically`: 임시디렉터리→rename,
  **`version.txt` 를 마지막에 써서 커밋 표식**으로 삼아 중간 실패 시 폴백 유지)/`readDownloadedJson`/`downloadedVersion`.
- **`DbUpdateManager`(Android)**: `HttpURLConnection`(신규 의존성 0, 코루틴 IO) 으로 manifest→비교→3종 gz 다운로드
  →해제→**sha256+size 검증**→원자교체. 예외를 밖으로 안 던지고 `Result`(Disabled/UpToDate/Updated/Failed)로 귀결→UI 문구.
  base URL 은 `BuildConfig.DATA_UPDATE_BASE_URL`(**기본 빈 문자열=갱신 비활성**, `-PdataUpdateBaseUrl` 오버라이드 가능).
- **`AssetsPokedexLoader` 확장**: `filesDir/db/` 유효본 있으면 **우선 로드**(파싱 성공해야 채택), 없거나 손상이면 **assets 폴백**.
- **INTERNET 권한** 추가(main manifest). 릴리스는 HTTPS 강제 유지, cleartext 는 **디버그 전용 network_security_config**(10.0.2.2)로만 E2E 허용.

### 3) UI (MainActivity 설정 섹션)
- "포켓몬 데이터 업데이트" 카드: **현재 버전 표시**("내장 데이터" / "다운로드본 (버전 X)") + **"데이터 업데이트" 버튼** +
  결과 문구(최신/완료/네트워크·형식·검증 실패). 문자열 ko/en 2언어. 버튼 중복탭 방지(checking 상태).

### 4) 테스트 (+18, 순수 JVM)
- `DbManifestTest`(8): 파싱/손상 null/알수없는키 무시/필수파일 누락/스키마 미지원/버전비교(정수·날짜스탬프·local null).
- `DbFilesTest`(7): sha256 알려진값/gzip 왕복/빈 디렉터리/원자교체 후 로드/부분파일 실패/version.txt 손상/재갱신 덮어쓰기.
- `DbUpdateFallbackTest`(3): 유효 다운로드본→실 Repository 로드(garchomp dex 445)/손상 다운로드본→파싱 null=폴백대상/없으면 null.

### 5) 에뮬레이터 E2E (실측 — AVD `Kohana_QA_API_35`)
- 로컬 서버: `python3 -m http.server 8765` 로 `data/dist/`(dataVersion=20260706) 서빙, 앱은 `http://10.0.2.2:8765/` 로 조회.
- **다운로드→로드 전환 실측**:
  1. 초기: `files/db` 없음(=내장본).
  2. "데이터 업데이트" 탭 → logcat `DbUpdateManager: 데이터 갱신 완료: null -> 20260706`,
     `files/db/` 에 3종(정확한 원본 바이트) + `version.txt=20260706` 생성. UI "Current: downloaded (version 20260706)" + "Updated…".
  3. **force-stop 후 재시작** → logcat `AssetsPokedexLoader: 다운로드본 로드(version=20260706)` = **다운로드본을 우선 로드**(내장본 아님) 확인.
  4. 재탭 → "Already up to date (version 20260706)" = 버전 비교 단락(재다운로드 없음) 확인.
- cleartext 차단(Android 9+)은 디버그 network_security_config 로 해소(릴리스 무영향).

### 6) git 저장소 초기화
- 프로젝트 루트 `git init` + `.gitignore`(android/build, .gradle, local.properties, *.apk, __pycache__, **field_samples/video/*.mp4** 대용량 영상, .DS_Store).
- 의미 단위 커밋 3개(data pipeline / android app / docs). **GitHub 원격 생성·push 는 사용자 확인 대상이라 미수행** — README 에 "GitHub Pages 로 dist/ 호스팅" 절 추가.

### 빌드·테스트 (실제 실행)
- `:app:testDebugUnitTest` → **BUILD SUCCESSFUL, 125/125, failures 0**(P12 107 + P13 18).
- `:app:assembleRelease`(R8, arm64) → **16.6MB, 클린**. INTERNET 권한 포함(aapt 확인), cleartext 디버그 전용(릴리스 HTTPS 강제).
- E2E: 다운로드→sha256 검증→원자교체→재시작 후 다운로드본 로드까지 실측 통과.

### 남은 운영 단계(사용자)
- **GitHub 원격 생성 + push**(미수행) → **Settings→Pages** 활성화 → `.../data/dist/` URL 을 `DATA_UPDATE_BASE_URL` 에 넣고 릴리스 빌드.
- 이후 데이터 갱신 = `build_release.py` 재실행 → `data/dist/` 커밋·push → Pages 반영 → 앱 "데이터 업데이트" 버튼.

---

## P14 — 필드테스트 지원 도구: 인앱 ROI 보정 + 진단 패널 ✅ 완료 (2026-07-05)

> **목적**: 실기기에서 ROI 가 어긋나거나 인식이 안 될 때, 사용자가 **adb 없이 폰에서 직접** 진단·보정.
> FIELD_TEST.md 의 "ROI 보정 = adb 수동 절차" 를 앱 내 UI 로 대체(adb 는 고급 대안으로 격하).

### 작업 1 — 인앱 ROI 보정 오버레이

- **`RoiEditLogic`(순수 JVM)**: 보정 오버레이의 좌표 변환 전부를 담는다(Android 의존성 0).
  - `move`(밴드 이동 — 크기 보존 + 화면 [0,1] 클램프), `resize`(모서리 핸들 — 반대변 앵커 고정 + `MIN_SIZE`(0.02) + 경계 클램프),
    `resizeBandCount`(싱글1↔더블2 탭 전환, 기존 밴드 보존), `defaultRois`(초기화). 픽셀 delta → 화면비율 rect.
- **`RoiCalibrationOverlay`(Compose 오버레이 창)**: 게임 위 **전체화면 반투명 편집기**.
  - 현재 RoiConfig 밴드를 색 테두리 사각형(싱글 1/더블 2)으로 표시 + **4모서리 리사이즈 핸들**. 안쪽 드래그=이동.
  - 하단 컨트롤 바: **싱글/더블 탭** + **초기화**(기본값 복원)/**저장**/**닫기**.
  - **터치 수신 위해 focusable+전체화면 창**(P5 IME 토글과 같은 패턴). 닫으면 창 제거로 게임 터치 원복. 카드 오버레이와 독립 창.
- **저장 즉시 반영**: `RecognitionPipeline` 의 `roiConfig`(고정) → **`roiConfigProvider: () -> RoiConfig`(공급자)** 로 변경.
  프레임마다 `PrefsRoiConfigStore.effective()` 를 다시 읽으므로, 보정 저장이 **다음 프레임부터** 새 ROI 로 반영(재구성 불필요).
- **진입**: `CaptureService.ACTION_CALIBRATE`(MediaProjection 불필요 — SYSTEM_ALERT_WINDOW 만). 설정 "이름 영역 보정" 버튼 → 서비스 인텐트.
  보정 전용으로 떠 있으면 닫을 때 서비스 종료(카드 오버레이/캡처 세션 없을 때).

### 작업 2 — 진단 패널 (adb 대체)

- **`DiagState`/`SlotDiag`/`OcrRateMeter`(순수 JVM)**: 진단 상태 모델 + 포매팅 + OCR 빈도 슬라이딩윈도우 계수기.
  - `SlotDiag.outcome`: **빈텍스트(EMPTY_TEXT)** / **미매칭텍스트(UNMATCHED_TEXT)** / **매칭(MATCHED)** 3분류 →
    인식 실패 시 원인(ROI 이탈·글자잘림 vs 표시명 불일치·닉네임)을 사용자가 바로 판단.
  - `formatSlot`(예 `S0 gyarados d1` / `S1 OCR:빈텍스트` / `S0 미매칭 "..."`), `formatLastSeen`("방금/n초 전/인식 없음"), `formatRate`("OCR n.n회/s"). 긴 텍스트 말줄임.
- **`RecognitionPipeline` 진단 콜백**: `onDiag: (DiagState) -> Unit` 추가(기본 no-op). 프레임마다 슬롯별 OCR 원문·매칭 root/editDistance·인식시각·OCR 빈도를 UI 로 넘긴다.
  기존 `diag` logcat 로그와 **동일 정보**를 UI 로 노출. release 에서도 동작(토글 기본 off — 문자열 조립뿐, 성능 영향 무시 가능).
- **오버레이 진단 스트립**(`DiagnosticStrip` @Compose): 진단 모드 on 시 카드 밑에 소형 패널. 슬롯별 한 줄(매칭=초록/빈텍스트=빨강/미매칭=주황) + 마지막 인식 경과 + OCR 회/s.
  카드가 없어도(빈텍스트 계속) 스트립은 뜨므로 원인 판단 가능.
- **설정 토글**: `AppSettings.diagnosticsEnabled`(기본 off). MainActivity 설정에 "진단 모드" Switch. 다음 "시작" 세션부터 반영(서비스가 `setDiagnosticsEnabled`).

### 테스트 (+28, 순수 JVM)
- `RoiEditLogicTest`(15): move(이동·크기보존·경계정지), resize(앵커고정·최소크기·경계), resizeBandCount(1↔2·보존), defaultRois, RoiConfig 직렬화 왕복 호환.
- `DiagStateTest`(13): outcome 3분류, formatSlot(매칭/빈/미매칭/말줄임), formatLastSeen, formatRate, OcrRateMeter(윈도우·만료·reset), 다중슬롯 정렬.

### 에뮬레이터 E2E (실측 — AVD `Kohana_QA_API_35`, 가로 1280x720)
- **보정 오버레이 렌더**: `ACTION_CALIBRATE` → 전체화면 반투명 편집기 표시 확인(스크린샷). 더블 기본값 밴드 2개(초록/파랑) + 4모서리 핸들 + 힌트 배너 + 싱글/더블 탭 + 초기화/저장/닫기 컨트롤 바.
  logcat: `RoiCalibrationOverlay.CalibrationRoot` 렌더, 오버레이 창이 `imeInputTarget`/`imeControlTarget`(=focusable 입력 수신) 확인.
- **진단 스트립(빈텍스트 원인 표시)**: 진단 on + 캡처(더블 기본 ROI) + 싱글배틀 갸라도스 화면 → 스트립 `Diag / OCR 0.7회/s / S0 OCR:빈텍스트(빨강) / S1 OCR:빈텍스트(빨강) / 인식 없음`.
  = 싱글배틀인데 더블 ROI 라 이름표 미포함 → **빈텍스트 원인을 사용자가 즉시 확인**(→ 보정 필요 판단).
- **저장→라이브 ROI 반영**: ROI 오버라이드를 **싱글 밴드**(`0.70,0.02,0.94,0.30`, 보정 저장과 동일 경로)로 교체 → 캡처 재시작 →
  logcat `diag roi#0 ocr=[가라도스, 100] match=Matched(root=gyarados, editDistance=1)`. 스트립 `S0 gyarados d1`(초록) + `방금 인식`, 오버레이 카드 "갸라도스"(물/비행) 표시.
  = **빈텍스트(더블 ROI) → 정확 인식(싱글 ROI)** 전환을 실 파이프라인에서 실증(라이브 ROI 반영 + 진단 원인분류 색전이 동시 확인).
- 디버그 전용 `CalibrationLauncherActivity`(설정 UI 스크롤 대신 adb `am start` 로 보정/진단 트리거 — 실제 앱과 동일 경로) 추가. **release 미포함 재확인**.

### 코드 변경 요약
- `capture/RoiEditLogic.kt`(신규, 순수): 드래그/리사이즈/밴드개수 좌표 변환.
- `capture/DiagState.kt`(신규, 순수): 진단 모델·포매팅·OCR 빈도 계수기.
- `overlay/RoiCalibrationOverlay.kt`(신규): 전체화면 보정 오버레이 창(focusable).
- `capture/RecognitionPipeline.kt`: `roiConfig`→`roiConfigProvider`(라이브 반영), `onDiag` 콜백 + 슬롯 진단 조립.
- `overlay/OverlayRenderer.kt`: `setDiagnosticsEnabled`/`updateDiag` + 스트립 렌더.
- `overlay/OverlayCard.kt`: `DiagnosticStrip` @Compose.
- `capture/CaptureService.kt`: `ACTION_CALIBRATE`+`showCalibrationOverlay`, 파이프라인 provider/onDiag 배선, 진단 토글 반영.
- `ui/MainActivity.kt`: 설정에 "이름 영역 보정" 버튼 + "진단 모드" 토글.
- `data/AppSettings.kt`: `diagnosticsEnabled`.
- strings(ko/en): 보정/진단/스트립 문구.
- 디버그 전용: `debug/CalibrationLauncherActivity.kt` + debug manifest.

### 빌드·테스트 (실제 실행)
- `:app:testDebugUnitTest` → **BUILD SUCCESSFUL, 153/153, failures 0**(P13 125 + P14 28: RoiEditLogic 15 + DiagState 13).
- `:app:assembleDebug`/`:app:assembleRelease`(R8, arm64) → 둘 다 성공. **release 16.6MB(불변), 디버그 활동(CalibrationLauncher/SampleImage) release 미포함**. 보정/진단 프로덕션 코드는 release 포함(R8 mapping 확인).
- 에뮬 실측: 보정 오버레이 열기·진단 스트립 빈텍스트→매칭 색전이·라이브 ROI 반영(갸라도스 인식) 스크린샷 증거.

### 남은 실기기 전용 항목(불변)
- 실기기 59.94fps 실시간 스트림·지연/발열 · K1 대전화면 직접 녹화 · 리전폼/닉네임 문자열 · Champions-verbatim EULA.
  (에뮬 드래그→저장 인터랙션은 SystemUI 부하로 탭 미전달 — 좌표 변환 로직은 JVM 15테스트로 완전 커버, 저장→반영은 실 파이프라인으로 실증.)

## P15 — 카드 고착 버그픽스 (FrameGate 하트비트, v0.1.1)
**리포트(실기기)**: 포켓몬 교체·새 게임에도 처음 나온 데이터가 계속 유지(카드 고착).
**근본원인**: FrameGate는 화면 변화 시에만 OCR 트리거 → PipelineDecider의 저신뢰(editDistance≥2 약매칭) 다른-root 전환은 연속 2회 관측 필요. 포켓몬 B로 바뀌면 FrameGate가 1회만 트리거되고 이후 B 이름표는 정지 상태라 재트리거 안 됨 → 2번째 관측이 안 와서 카드가 A에 영구 고착. (첫 포켓몬은 "최초취득"이라 즉시 표시되므로 증상이 "첫 데이터 고정"으로 나타남.)
**수정**: FrameGate 하트비트(DEFAULT_MAX_INTERVAL_MS=1500) — 화면 변화가 없어도 1.5s마다 강제 재스캔 → 보류된 전환 확정 + 임계값이 놓친 변화 회수. 히스테리시스의 오인식 억제는 유지(변화 프레임의 단발 약매칭은 여전히 억제, 정지 후 하트비트로 확정).
**검증**: 유닛 155/155(FrameGateTest +2: 정지화면 하트비트 통과 / 변화 즉시통과 공존). versionCode 3 / 0.1.1.
**미완**: 에뮬 E2E 재현(정지 A→정지 B 전환)은 위임 서브에이전트가 비정상 종료(프롬프트 인젝션 의심, 0 tool_uses)로 미수행 — 유닛 회귀 테스트가 교착 로직을 직접 커버하며, 실기기 리포터가 실환경에서 재확인 예정.

## P16 — 오버레이 UX 3종 개선 (카드 크기 · 가로 후보 flyout · 앱 종료, v0.1.2)

실기기 리포트 3건 대응. 스코프는 아래 3기능 + 검증/배포로 한정.

### 기능 1 — 오버레이 카드 크기 조절
- `data/AppSettings.overlayScale`(Float, 영속): 저장 시 `OverlayScale.snap` 으로 허용 단계(0.8/1.0/1.25/1.5)에 스냅 + [MIN,MAX] 클램프. 기본 1.0.
- `overlay/OverlayScale.kt`(신규, 순수 JVM): 스냅/클램프/라벨. NaN·무한대 → 기본값.
- **밀도 기반 스케일**(graphicsLayer 아님): `LocalOverlayScale` CompositionLocal + `Dp.scaled()`/`TextUnit.scaled()` 로 OverlayCard·시트·확장패널의 모든 dp/sp 를 곱함 → 잘림 없이 창(WRAP_CONTENT) 크기가 자연히 따라감.
- MainActivity 설정에 스케일 칩(80/100/125/150%). 저장 즉시 `ACTION_APPLY_SCALE`(startService, FGS 승격 안 함 — 미실행 시 즉시 stopSelf)로 실행 중 오버레이에 `setScale` 반영. 서비스 재시작/데모 재탭 시에도 `onStartCommand` 가 최신값 반영.

### 기능 2 — 가로에서 후보 시트를 옆으로 (버그픽스)
- **버그**: 가로에서 "바꾸기"(후보 선택) 시트가 아래로 펼쳐져 화면 세로를 꽉 채우고 잘림.
- `overlay/SheetLayout.kt`(신규, 순수 JVM): 시트 열림 배치·창위치 계산.
  - **가로** → 측면 flyout(SIDE). 카드 오른쪽에 시트 폭이 들어가면 RIGHT, 아니면 왼쪽 flip(LEFT). 커진 창(Row)이 화면을 넘으면 x/y clamp(넘치면 반대쪽/위쪽으로).
  - **세로** → 아래 전개(BELOW) 유지 + 커진 창 하단 넘침 시 위로 clamp.
  - `close`: 시트 닫히면 카드 크기 기준으로 화면 안 clamp.
- `OverlayRenderer`: 시트 열릴 때 orientation 판정 → `CompositionLocalProvider(LocalOverlayScale)` 아래 측면(Row: 방향에 따라 시트/카드 순서) vs 아래(Column) 배치. `repositionForSheet`/`clampWindowIntoScreen` 가 측정된 실제 창크기로 화면 안으로 당김. 측면 flyout 시 시트 리스트 최대 높이를 화면 높이 70%로 clamp(세로 잘림 방지). 후보 3+ 여도 시트 내부 스크롤로 커버.
- 확장 패널/메가 세그먼트/수동검색 시트 점검: 확장 패널은 기존 스크롤+자동축소 유지 + 창 위치 clamp 로 화면 밖 방지. 수동검색 시트도 동일 측면화(`SheetContent` 공통 경로, 검색은 IME 포커스 토글 유지).

### 기능 3 — 앱 종료 수단
- **FGS 상시 알림 "종료" 액션**: `buildNotification` 에 `Notification.Action`(→ `ACTION_STOP` PendingIntent). 탭 → stopSelf → onDestroy(캡처 중지·오버레이 제거·프로젝션 정리).
- **오버레이 카드 종료 진입점**: 주 카드 그립 바 **오래누르기**(`detectTapGestures.onLongPress`) + 작은 **✕ 버튼**(터치 영역만 clickable, P5 focusable 패턴 유지) → `onExit` → `exitAll`(stopSelf).
- MainActivity "중지" 는 기존대로 `stopIntent`(ACTION_STOP) 경유 동일 정리 경로. 종료 후 오버레이 창 0·활성 알림 0·서비스 없음 확인.

### 코드 변경 요약
- `overlay/OverlayScale.kt`(신규, 순수), `overlay/SheetLayout.kt`(신규, 순수).
- `overlay/OverlayCard.kt`: `LocalOverlayScale`+`scaled()` 전면 적용, `onExit`(그립 롱프레스+✕), 시트 `maxSheetHeight` 파라미터.
- `overlay/OverlayRenderer.kt`: `setScale`/스케일 상태·`onExit`·측면 flyout 배치(`CardStack`/`SheetContent`)·`repositionForSheet`/`clampWindowIntoScreen`.
- `capture/CaptureService.kt`: `exitAll`·알림 종료 액션·`ACTION_APPLY_SCALE`·`initialScale`/`onExit` 배선·onStartCommand 스케일 반영.
- `ui/MainActivity.kt`: 설정에 `OverlayScaleSelector`(칩) + 즉시 반영 인텐트.
- `data/AppSettings.kt`: `overlayScale`.
- strings(ko/en): 카드 크기 설정·알림 "종료"·✕.

### 빌드·테스트 (실제 실행)
- `:app:testDebugUnitTest` → **BUILD SUCCESSFUL, 172/172, failures 0**(P15 155 + P16 17: OverlayScaleTest 8 + SheetLayoutTest 9).
- `:app:assembleRelease`(R8, arm64) → 성공. **release 데이터 URL 불변**(kohana-dev.github.io/pochamps-supporter). **versionCode 4 / versionName 0.1.2**.
- 에뮬 실측(AVD Kohana_QA_API_35, 가로 1280x720):
  - (a) 스케일 80/100/150% 3단 렌더 — 밀도 기반이라 잘림 없이 카드/폰트/패딩·창 크기 함께 변화, **실행 중 즉시 반영**. (`field_samples/p16/scale_{80,100,150}.png`)
  - (b) arcanine 데모(윈디 2후보) 가로에서 "바꾸기" → 후보 시트가 **카드 오른쪽으로 flyout**, 두 후보 모두 세로 잘림 없이 표시. (`field_samples/p16/candidate_flyout_landscape.png`)
  - (c) 알림 "종료" 탭 → 오버레이 사라짐·상태바 알림 0·서비스 없음. (`field_samples/p16/notification_exit_action.png`, `after_exit_clean.png`)

### 남은 실기기 전용 항목(불변)
- 실기기 가로 회전 실시간 재배치·롱프레스 종료 촉감 · P15 이하 잔여 항목 동일.

## P17 — 캡처 건강 모니터: FLAG_SECURE 검은화면 / 프레임미수신 자동 감지 (K1 자동화, v0.1.3) ✅ 완료 (2026-07-05)

> DESIGN.md 1장 K1(최대 리스크)을 **"실기기 수동 확인"에서 "앱이 자동 감지+고지"로 상향**.
> 게임이 `FLAG_SECURE` 로 캡처를 막으면 MediaProjection 은 검은/균일 저휘도 프레임만 준다. 기존엔
> 앱이 조용히 아무 카드도 안 띄워 사용자가 원인을 몰랐다. 이제 자동 분류해 명확히 고지한다.

### 기능 — 캡처 건강 분류(순수 JVM) + 사용자 고지 UX
- `capture/CaptureHealth.kt`(신규, 순수 JVM): 프레임 휘도/도착시각/프레임카운트 → 3분류.
  - **NoFrames**: 캡처 시작 후(또는 마지막 프레임 이후) `noFramesMs`=**4s** 동안 프레임 0건 → 캡처 파이프 이상.
  - **BlackScreen**: 프레임 평균 휘도 ≤ `blackLumaThreshold`=**18**(0..255)가 `blackHoldMs`=**2.5s** **연속 지속** → FLAG_SECURE 강한 신호.
  - **Healthy**: 정상(밝음 + 프레임 갱신).
  - **오탐 억제**: 밝은 프레임 1장이라도 오면 어두운-구간 타이머 리셋 → 로딩/전환 순간의 짧은 검정 무시. 임계/지속시간 보수적(정상 대전 화면은 훨씬 밝음).
  - `onFrame`/`evaluate` 는 상태가 **바뀔 때만** 새 상태 반환(중복 억제). @Volatile current 읽기.
- 배선: `RecognitionPipeline.submitFrame`(경량 캡처 콜백, ~초당 3회)에서 프레임 전체 다운샘플 평균 휘도를 `CaptureHealth.onFrame` 에 공급.
  - **버그픽스(실측 발견)**: 처음엔 `processFrame` 에서 휘도를 쟀는데, OCR 이 프레임당 수 초 걸려 `processFrame` 이 드물게 돌아(그 주기가 4s 초과) **정상인데 NoFrames 오판**. → OCR 병목 **이전** 지점(submitFrame)으로 이동해 해결.
  - 서비스가 `HEALTH_POLL_MS`=1s 마다 `evaluateHealth(now)` 폴링 → 프레임이 아예 안 와도 NoFrames 판정.
- 사용자 고지 UX(`overlay/OverlayCard.kt` `CaptureHealthCard` + `OverlayRenderer.updateCaptureHealth`):
  - **BlackScreen** → 경고톤 카드 "화면 캡처가 차단됨 / 이 게임이 화면 캡처를 막는 것 같습니다(검은 화면). 여기서는 오버레이를 사용할 수 없습니다." (재시작 버튼 없음 — FLAG_SECURE 는 재시작해도 무용).
  - **NoFrames** → 정보톤 카드 "화면 프레임 없음 / 캡처 권한 확인·재시작" + **재시작 버튼**(P7 재동의 흐름 `onRestart` 재사용).
  - **Healthy 복귀** → 카드 자동 해제. 사용자 수동 닫기(오탐 대비)도 제공.
  - 캡처 중단(showCaptureStopped) 카드가 뜨면 건강 안내는 걷음(우선순위).
  - 알림 본문도 상태 반영(`buildNotification(text)` 파라미터화 → `notify` 재발행).
  - ko/en 문구(strings.xml + values-en) 4종.
- P14 진단 스트립 일관: `DiagState.health` + `formatHealth` → 스트립에 "캡처: 정상/검은화면(차단?)/프레임없음" 한 줄 추가(정상=회색, 이상=경고색).

### 테스트 (+12, 순수 JVM)
- `CaptureHealthTest`: NoFrames(유예/프레임끊김/복귀), BlackScreen(지속·경계·복귀), 짧은검정 오탐억제, 임계경계, 동일상태 중복억제, averageLuma, reset — 12개.

### 에뮬레이터 E2E (실측 — AVD `Kohana_QA_API_35`, 가로 1280x720)
- 연속 프레임이 필요해(정적 이미지는 에뮬 미러가 새 프레임을 안 줌) **루프 재생 영상**으로 구동: 배틀 영상(밝음)·검은 영상(ffmpeg color=black) 을 앱 내부 files 로 넣고 `SampleImageActivity --es vid` 로 재생.
- (a) **Healthy**: 배틀 영상 → 안내 카드 없음, 진단 스트립 "캡처: 정상". (`field_samples/p17/a_healthy_video.png`) — 부수로 갸라도스 실매칭(editDistance=1)도 확인.
- (b) **BlackScreen**: 검은 영상 → "Screen capture blocked" 경고톤 카드(재시작 버튼 없음). (`field_samples/p17/b_blackscreen.png`)
- (c) **복귀**: 검정→배틀 영상 → 안내 카드 자동 해제, 진단 스트립 "캡처: 정상" 복귀. (`field_samples/p17/c_recovered.png`)
- (d) **NoFrames**: 프레임이 끊긴 구간에서 "No screen frames / Restart" 카드가 실제로 렌더됨을 첫 런 로그로 확인. 에뮬에서 미러가 마지막 프레임을 계속 밀어 깔끔한 격리가 어려워, 로직은 유닛테스트(경계·복귀)로 입증(태스크 허용 범위).

### 빌드·테스트 (실제 실행)
- `:app:testDebugUnitTest` → **BUILD SUCCESSFUL, 184/184, failures 0**(P16 172 + P17 12: CaptureHealthTest).
- `:app:assembleRelease`(R8, arm64) → 성공. **release 데이터 URL 불변**(kohana-dev.github.io/pochamps-supporter). **versionCode 5 / versionName 0.1.3**.

### 코드 변경 요약
- `capture/CaptureHealth.kt`(신규, 순수), `capture/RecognitionPipeline.kt`(submitFrame 휘도 공급·evaluateHealth·onHealth·reset), `capture/CaptureService.kt`(onHealth 배선·건강 워치독 폴링·알림 본문 갱신), `capture/DiagState.kt`(health 필드·formatHealth).
- `overlay/OverlayRenderer.kt`(updateCaptureHealth·건강 카드 우선 렌더·중단 시 걷기), `overlay/OverlayCard.kt`(CaptureHealthCard·진단 스트립 건강 줄).
- strings(ko/en): 캡처 차단/프레임없음 4종.
- build.gradle.kts: versionCode 5 / 0.1.3.

### 남은 실기기 전용 항목(불변)
- 실기기에서 실제 FLAG_SECURE 게임(포챔스 정식판)으로 BlackScreen 자동감지 최종 확인. P16 이하 잔여 항목 동일.

## P20 — 싱글/더블 배틀 형식 토글 (ROI + 사용률 동시 전환, v0.1.4) ✅ 완료 (2026-07-05)

> **실기기 리포트 대응**: 앱이 **항상 더블로 하드코딩**돼 있어 싱글 배틀이 사실상 작동 불가였다.
> `CaptureService` 가 `format=DOUBLES` 고정(2곳), `RoiConfig.default()`=더블 2밴드만 사용.
> → 싱글에서 (1)ROI 위치 어긋나 인식 실패, (2)사용률이 더블 메타로 오표시. **수동 토글로 형식(ROI+사용률)을 함께 전환**한다.

### 기능 — 싱글/더블 토글
1. **`AppSettings.battleFormat`**(SINGLES/DOUBLES, 기본 DOUBLES) 영속. slug 저장, 깨진 값 안전 폴백. (`BattleFormat` enum 재사용 — data 패키지.)
2. **형식별 ROI 스왑**: `RoiConfig.activeDefault(format)` 추가 → 싱글=1밴드(`DEFAULT_LANDSCAPE_SINGLE`) / 더블=2밴드(`DEFAULT_LANDSCAPE_DOUBLES`). 파이프라인 `roiConfigProvider = { roiStore.effective(captureFormat) }` — 프레임마다 최신 형식의 ROI 를 읽어 토글 즉시 반영.
3. **형식별 ROI 오버라이드 분리(P14 보정)**: 싱글/더블은 밴드 수가 달라 오버라이드 공유 시 깨짐 → `RoiConfigStore.effective(format)` + 저장 키 분리(`roi_override_single`/`roi_override_doubles`). 오버라이드 없으면 그 형식 기본값 반환. 구버전 단일 키(`roi_config`)는 더블로 1회 마이그레이션. 무인자 API 는 더블 위임(하위호환). ROI 보정 UI/리셋도 **현재 형식**의 것만 편집/리셋(다른 형식 보정 보존).
4. **사용률 형식 추종**: `RecognitionPipeline.format` → `formatProvider: () -> BattleFormat`. 카드 조립(`OverlayCardData.fromRepository(format=…)`)이 매 갱신 시점 최신 형식을 읽어 "주요 기술" 사용률이 싱글 메타 vs 더블 메타로 바뀜.
5. **UX — 빠른 토글**:
   - **오버레이 상단 싱글/더블 세그먼트**(`OverlayCard.FormatToggle`) — 대전마다 즉시 전환. 세그먼트만 터치를 받고 그 밖은 게임으로 통과(창=카드/`FLAG_NOT_FOCUSABLE` 전략 보존). 실캡처 세션(`captureActive`)에서만 노출(데모도 UI 검증 위해 노출).
   - **MainActivity 설정**에 배틀 형식 선택 칩(싱글/더블) 추가.
   - **토글 즉시 반영**: `CaptureService.onFormatToggled` → 설정 저장 + `overlay.setFormat` + 슬롯 정리(`pruneSlotsAbove(format.maxSlotIndex)` — 더블→싱글 시 슬롯1 카드 제거) + `pipeline.resetForFormatChange()`(Decider/FrameGate 리셋으로 형식 전환 고착·핀 누수 방지). 다음 프레임부터 새 ROI/사용률.
   - ko/en 문구(형식 선택/토글) 4종.
6. **자동 감지는 이번 스코프 아님**(수동 토글만). 향후 자동 감지 확장 지점 주석을 `onFormatToggled`/`FormatToggle` 에 남김.

### 테스트 (+9, 순수 JVM → 193)
- `RoiConfigTest`(+4): `activeDefault` 형식별 밴드 수, `effective(format)` 형식별 기본/오버라이드 **독립**(싱글 저장이 더블에 누수 안 됨, 형식별 리셋), 무인자 하위호환(더블 위임).
- `BattleFormatTest`(신규, +5): 형식별 슬롯 수/유지 최대 슬롯 인덱스, 형식별 활성 ROI 밴드 수, slug, 더블→싱글 슬롯1 제거 대상 계산, 형식 전환 Decider 리셋(핀 해제 후 정상 갱신).

### 에뮬레이터 E2E (실측 — AVD `Kohana_QA_API_35`)
- **인스트루먼트 테스트** `OcrFieldTest.p20_singles_doubles_형식전환_실측` (실 ML Kit OCR):
  - (a) **싱글 활성 ROI(1밴드)** → `en_single_hippowdon.jpg`=hippowdon OK, `ko_single_gyarados.jpg`=가라도스→gyarados OK.
  - (b) **더블 활성 ROI(2밴드)** → `en_doubles_typhlosion_charizard.jpg`=typhlosion+charizard 2종 인식.
  - (c) **사용률 형식 차이(garchomp)**: doubles=[Dragon Claw, Rock Slide, Earthquake, Protect] vs singles=[Earthquake, Outrage, Stealth Rock, Rock Tomb] — 다름 확인.
- **오버레이 실측 스크린샷**(`field_samples/p20/`): 설정 형식 선택(`a_settings_format_selector.png`), 데모 오버레이 **더블 토글** 시 한카리아스 기술=[드래곤클로 89 / 스톤샤워 84 / 지진 78 / 방어 73](`b_overlay_doubles_moves.png`), **싱글 토글** 시 같은 포켓몬 기술=[지진 99 / 역린 49 / 스텔스록 47 / 암석봉인 36](`c_overlay_singles_moves.png`). **같은 포켓몬·같은 토글, 사용률만 형식 따라 전환**됨을 시각 입증.

### 빌드·테스트 (실제 실행)
- `:app:testDebugUnitTest` → **BUILD SUCCESSFUL, 193/193, failures 0**(P17 184 + P20 9).
- `:app:assembleRelease`(R8, arm64) → 성공. **release 데이터 URL 불변**(kohana-dev.github.io/pochamps-supporter). **versionCode 6 / versionName 0.1.4**.
- `:app:connectedDebugAndroidTest`(P20 E2E) → 통과.

### 코드 변경 요약
- `data/AppSettings.kt`(`battleFormat` 영속), `data/Usage.kt`(`BattleFormat.slotCount`/`maxSlotIndex`).
- `capture/RoiConfig.kt`(`activeDefault(format)` + `RoiConfigStore` 형식별 effective/save/clear + 하위호환 위임), `capture/PrefsRoiConfigStore.kt`(형식별 키 분리 + 구키 마이그레이션), `capture/RecognitionPipeline.kt`(`formatProvider`·`resetForFormatChange`), `capture/CaptureService.kt`(`captureFormat`·`onFormatToggled`·형식별 ROI/사용률 provider·보정 형식 스코프·데모 형식 반영·재발행).
- `overlay/OverlayRenderer.kt`(형식 토글 상태·`setFormat`·`setCaptureActive`·`pruneSlotsAbove`·`FormatToggle` 렌더), `overlay/OverlayCard.kt`(`FormatToggle` 컴포저블).
- `ui/MainActivity.kt`(배틀 형식 선택 칩·ROI 리셋 형식 스코프).
- strings(ko/en): 형식 선택/토글 4종.
- build.gradle.kts: versionCode 6 / 0.1.4.

### 남은 실기기 전용 항목(불변)
- 실기기 실 대전에서 싱글/더블 토글 최종 확인(특히 싱글 이름표 위치·더블 2밴드 정합). 자동 형식 감지는 향후(P20 스코프 밖). P17 이하 잔여 항목 동일.
