# 실전 테스트 가이드 (FIELD_TEST)

> 대상: 포챔스 서포터 앱 v0.1.0 · 실기기(안드로이드) 검증 절차
> 코드/유닛테스트/APK 산출은 완료됨. **여기 적힌 항목은 실기기에서만 판정 가능**하다.
> 근거 문서: [DESIGN.md](DESIGN.md) 1장(선검증 K1~K4) · 5장(상태별 UX) · 6장(온보딩)

> ## ⚡ P8 갱신 — 웹 실게임 자료로 선검증 대폭 진척 (2026-07-05)
> 실기기 없이 웹의 **모바일판 화면녹화**로 K1/K2 를 앞당겨 검증했다(PROGRESS P8 · field_samples/SOURCES.md).
> - **K1 = GO(유력)**: 모바일 배틀 화면녹화 영상이 유튜브에 온전히 존재 = 캡처 차단 안 함의 강한 증거.
>   → 실기기에서는 **대전 화면 직접 녹화 5분 최종 확인만** 하면 된다(전면 차단 가능성은 사실상 배제).
> - **K2 = 확정**: 이름표 = **base 종족명**("Hippowdon"/"갸라도스" 등), candidate_index 와 일치(교정 불필요).
>   **ROI 는 우상단 배치로 코드 교정 완료**(`RoiConfig` DEFAULT_LANDSCAPE_DOUBLES/SINGLE). 실기기 미세조정만 남음.
>   ※ 미확인: 리전폼("윈디" vs "히스이 윈디") 문자열, 닉네임 노출 여부(샘플에 등장 없음).
> - **K3 = P9 에서 에뮬 실측 통과(7/7=100%, 지연 avg 47ms)**: bundled ML Kit 전환으로 런타임 다운로드가 사라져
>   AVD 에서 `OcrFieldTest` 가 돌았다. **모델 다운로드 항목은 더 이상 없음**(모델이 APK 에 동봉됨).
>   실기기에선 **지연/발열만 재확인**하면 된다(인식률은 이미 100% 확인).

> ## ⚡ P9 갱신 — bundled 전환으로 K3 에뮬 실측 통과 (2026-07-05)
> - ML Kit 을 **bundled(`com.google.mlkit:text-recognition*`)** 로 전환 = 모델 APK 동봉, **최초 다운로드 불필요**.
>   → 에뮬레이터에서도 즉시 동작. `OcrFieldTest` 실측 **7/7(100%)**, 지연 **avg 47ms / p50 28ms**(목표 100~400ms 여유).
> - 대가로 APK 가 커짐: **release 2.2MB → 44.4MB, debug 12MB → 54.6MB**(ABI별 OCR `.so` 4벌 ≈ 41MB).
>   배포 전 **arm64-v8a 단일 ABI split** 으로 release ~17MB 로 축소 예정(PROGRESS P9 (2)절).
> - hippowdon2 프레임이 SINGLE ROI 하단 클리핑으로 오인식되던 것을 **ROI bottom 0.17→0.22 로 교정**(→7/7).

> ## ⚡ P10 갱신 — 전체 파이프라인 E2E(에뮬) 통과 + K4 EULA 판정 (2026-07-05)
> - **E2E**: 하네스(이미지 직접 주입)를 넘어, **앱이 MediaProjection 으로 실제 화면을 스스로 캡처→OCR→매칭→오버레이 갱신**하는
>   완전 경로를 AVD 에서 검증. field_samples 를 가로 전체화면으로 띄우고(디버그 전용 `SampleImageActivity`, AVD `wm size 1280x720`+가로회전으로
>   16:9 종횡비 정합) 앱이 그 화면을 캡처해 카드 갱신. **3/3 시나리오 정답**: en_single Hippowdon / en_doubles Typhlosion+Charizard(**카드 2장**) / ko_single 갸라도스.
>   OCR 오인식(`Hippovdos`/한글 `가라도스`)도 fuzzy 로 정확 root 교정. 진단은 `logcat -s RecognitionPipeline`(debug 빌드 `diag` 로그).
> - **실기기 잔여**: **움직이는 실배틀 화면**에서의 FrameGate 거동(정지 프레임은 완전 검증). 지연/발열은 K3 항목과 함께.
> - **K4 EULA 판정**: **캐주얼=불명확(리스크), 공인 대회(랭크전/Play! Pokémon VGC)=금지(§2.7 종이 타입표조차 금지)**.
>   → 앱 온보딩에 경고 고지 반영(`onboarding_legal_*`). 상세는 아래 (g)절.
>
> ## ⚡ P11 갱신 — 동적 배틀 E2E 통과 + 히스테리시스 + 브랜딩 (2026-07-05)
> - **동적 E2E(에뮬)**: 장면이 바뀌는 상황(장면전환·연출/이펙트 프레임)을 슬라이드쇼로 재현해 검증(에뮬 VideoView 재생 불가로 정지/합성 프레임 슬라이드쇼 사용).
>   결과: **장면전환 시 카드 갱신(더블 우측 Charizard→Torkoal, 좌측 Typhlosion 무깜빡임 유지)** · **연출/blur 프레임은 OCR 빈값→직전 카드 유지(엉뚱카드 0)** ·
>   **메뉴 프레임 오탐 0** · **실 DM Gaming 배틀 프레임 Hippowdon editDist 0 정확**. **OCR 빈도 ≈1.08회/s(0.54/ROI/s)** — FrameGate 스로틀 적정(임계 조정 불필요).
> - **오인식 억제 수정**: `PipelineDecider` 저신뢰 전환 히스테리시스(고신뢰 editDist≤1 즉시 / 약매칭 다른포켓몬은 연속2회 확인). 유닛테스트 +8(총 **99/99**).
> - **실기기 잔여(정직)**: 에뮬 VideoView 재생 불가로 **59.94fps 실시간 프레임 스트림** 자체는 미검증(슬라이드쇼 대체) → 실기기 실배틀 재확인.
>   레터박스별 이름표 y 편차(실 Sylveon 프레임 미인식, 같은 영상 Hippowdon 은 정확) → 실기기 ROI 미세보정. 미인식은 카드유지로 안전 열화.
> - **브랜딩**: 어댑티브 앱 아이콘(오버레이 카드+타입칩 모티브, 포켓몬 저작물 무사용) · en 라벨 `PokeChamps Supporter`. 에뮬 런처 렌더 확인·release 포함.
>
> ## ⚡ P12 갱신 — 전체 코드베이스 적대적 리뷰 + ROI 강건화 (2026-07-05)
> - **적대적 리뷰(전 파일)**: 실트리거 버그 **2건** 발견·수정. (1) 더블배틀 슬롯 포켓몬 교체 시 **메가 선택 인덱스 누수**로
>   토글한 적 없는 새 포켓몬의 메가 카드 오표시 → key 변경 시 base 초기화. (2) 데모 FGS `specialUse` 타입 **API34 가드 누락**
>   (API 29~33 실기기에서 데모 탭 시 크래시) → API34+ 만 specialUse. 나머지 후보(Handler race 등)는 이미 null-safe 라 정직히 기각.
> - **ROI 강건화(실측 규명)**: P11 의 "Sylveon 레터박스 y 편차" 가설은 **반증**됨 — 실 파이프라인에서 현재 ROI 로도 Sylveon editDist 0 정상 인식.
>   단순 밴드 확장은 인접 UI(`MOVE TIME 45`)를 이름으로 오선택해 **악화**. 대책: **다중 라인 매칭(matchBest)** 도입(사전 매칭이 UI 텍스트를
>   걸러줌) → 그 위에서 **세로밴드 안전 확장**(SINGLE bottom 0.22→0.30, DOUBLES 0.17→0.24). 장면별 y 편차 흡수.
> - **ROI 재실측(에뮬)**: 기존 7샘플 **100% 유지 + 실배틀 Sylveon/Hippowdon 프레임 신규 통과 = 9/9(100%)**. 밴드 4종 전부 OK(확장 강건성 실증).
> - **테스트**: 유닛 **107/107**(P11 99 + P12 8). release(R8, arm64) **16.6MB** 클린.
> - **실기기 잔여**: 실시간 59.94fps 스트림·지연/발열·리전폼/닉네임·Champions-verbatim EULA(불변). (Sylveon ROI 이슈는 P12 강건화로 해소.)

작업 우선순위(실기기):
1. **K1 최종 확인 — 5분.** (웹 자료로 유력 GO. 대전 화면 직접 녹화로 확정만.) 만에 하나 전면 차단이면 즉시 중단.
2. **실배틀 E2E 재확인** — 에뮬은 정지 이미지로만 검증. 실기기 실배틀에서 움직이는 화면·FrameGate·지연/발열 재측정.
3. K2 미세조정(ROI 픽셀·리전폼/닉네임 문자열) → 4. 온보딩/UI 흐름 → 5. 트러블슈팅.

준비물: 안드로이드 실기기(가급적 Android 14/15), 포켓몬 챔피언스 설치, USB 케이블(adb), 포챔스 대전(친선/랭크).

---

## (a) APK 설치

release APK 경로(빌드 후):
```
android/app/build/outputs/apk/release/app-release.apk   (~44.4MB, 디버그 키 서명; bundled ML Kit·전 ABI)
```
> ⓘ P9 bundled 전환으로 크기가 커졌다(모델 동봉). 배포 전 arm64-v8a 단일 ABI 로 좁히면 ~17MB.
> v0.1.0 릴리스는 **디버그 키로 서명**되어 있어 사이드로드/실기기 테스트가 바로 된다.
> (Play 업로드용 정식 서명키 교체는 배포 단계에서.)

### adb 로 설치 (권장)
```bash
# 기기 USB 디버깅 ON → 케이블 연결 → 인식 확인
adb devices
# 설치(재설치면 -r)
adb install -r android/app/build/outputs/apk/release/app-release.apk
# (기존 디버그 APK 가 다른 서명으로 깔려 있으면 먼저 제거)
adb uninstall com.pochamps.supporter
```

### 직접 설치
APK 를 기기로 복사(드라이브/USB) → 파일앱에서 탭 → "출처를 알 수 없는 앱" 허용 → 설치.

### 빌드부터 다시
```bash
cd android
./gradlew :app:assembleRelease      # release (minify, ~44.4MB; bundled ML Kit)
./gradlew :app:assembleDebug        # debug   (~54.6MB, 로그 편함)
```
> **인식률/좌표 실측 단계에서는 debug APK 를 쓰는 게 편하다**(로그·재빌드 빠름). release 는 최종 확인용.

---

## (b) K1 — FLAG_SECURE 캡처 차단 검증 ★최우선(5분)★

**목적**: 포챔스가 화면 캡처를 막는가(`FLAG_SECURE`). 막으면 이 앱은 성립 불가(우회 불가, 루팅 제외).
**이 앱 없이도 판정 가능** — 코드 한 줄 없이 5분.

절차:
1. 포챔스를 실행해 **실제 대전 화면**(상대 포켓몬이 보이는 화면)까지 진입.
2. 기기 기본 **화면 녹화**(빠른 설정 타일) 또는 아무 스크린샷을 대전 화면에서 찍는다.
3. 녹화 영상/스크린샷을 열어 확인:
   - **대전 화면이 정상적으로 보이면 → K1 PASS.** 본 앱 진행 가치 있음.
   - **검은 화면/캡처 차단 문구가 뜨면 → K1 FAIL.** `FLAG_SECURE` 사용 = **프로젝트 중단**.
4. (교차 확인) 본 앱으로도 검증: 앱 설치 → "시작(화면 캡처)" → 대전 화면에서
   ```bash
   adb logcat -s CaptureManager RecognitionPipeline
   ```
   프레임이 계속 검게(전부 동일/검정) 들어오면 FLAG_SECURE 의심.

> 판정이 애매하면(일부 화면만 검정 등) 대전 중 여러 화면에서 반복 확인. 로비만 캡처되고
> 대전 화면만 검정이면 그것도 FAIL 취급.

---

## (c) K2 — 표시명 확정 + ROI 좌표 보정

**목적**: (1) 배틀 화면에 뜨는 상대 이름 **문자열**이 우리 DB 표시명과 맞는지, (2) 이름 텍스트의
**화면상 위치(ROI)** 를 기기 해상도에 맞게 보정.

현재 ROI 기본값은 추정치다(`RoiConfig.DEFAULT_LANDSCAPE_DOUBLES`, 가로 더블배틀 상단 좌/우 2곳).
실좌표는 스크린샷으로 확정한다.

### 절차
1. 대전 화면 스크린샷을 여러 장 찍는다(더블배틀, 상대 2마리 이름이 보이게).
   ```bash
   adb exec-out screencap -p > shot.png
   ```
2. 스크린샷을 이미지 편집기로 열어 **상대 이름 텍스트를 감싸는 사각형**의 픽셀 좌표를 잰다.
   각 ROI(좌/우 이름)마다: `left, top, right, bottom` (픽셀).
3. 화면 전체 크기(`W = 가로픽셀`, `H = 세로픽셀`)로 **비율(0~1)** 로 환산:
   ```
   x      = left   / W
   y      = top    / H
   width  = (right - left)   / W
   height = (bottom - top)   / H
   ```
4. 이 비율값으로 ROI 를 보정한다. 현재는 **드래그 편집 UI 가 없으므로**(스코프 밖),
   기본값 상수를 코드에서 고치거나 SharedPreferences 오버라이드로 넣는다:
   - **코드 수정**: `android/.../capture/RoiConfig.kt` 의 `DEFAULT_LANDSCAPE_DOUBLES` 의
     `RoiRect(x, y, width, height)` 두 개를 위 계산값으로 교체 → 재빌드.
   - **런타임 오버라이드**(재빌드 없이): `PrefsRoiConfigStore` 직렬화 포맷으로 저장.
     (adb 로 SharedPreferences 주입하거나, 후속 ROI 편집 UI 에서 저장.)
5. 재실행 → 이름 위에 카드가 정확히 뜨는지 확인. 어긋나면 3~4 반복.

### 표시명 문자열 확정
- 스크린샷의 **정확한 이름 텍스트**(예: "윈디" vs "히스이 윈디", "Arcanine" vs "Hisuian Arcanine")를
  기록한다. 리전폼/폼 변화가 base 이름으로만 뜨는지 확인.
- 현재 DB 표시명은 op.gg 이름을 프록시로 사용 중 → 실화면과 다르면
  `data/candidate_index.json` 의 lookup 키를 실제 문자열로 교정(파이프라인은 [4-1] 재실행).
- **닉네임 설정 시** 종족명 대신 닉네임이 뜨는지도 확인(뜨면 수동 검색 fallback 대상).

---

## (d) K3 — OCR 인식률/지연 실측

**목적**: ML Kit 온디바이스 OCR 이 실용적 정확도/지연인지 측정.

> ✅ **P9 에뮬 실측 통과**: bundled 전환으로 모델이 APK 에 동봉되어 **다운로드 불필요** → AVD 에서 실측됨.
> `OcrFieldTest` on `Kohana_QA_API_35`: **7/7(100%)**, 지연 **avg 47ms·p50 28ms·max 119ms(초회)**.
> (~~과거 unbundled 는 GMS Zapp 모듈 미배포로 에뮬 실측 불가였음 — bundled 전환으로 해소.~~)
> **실기기에선 지연/발열만 재확인** — 에뮬은 swiftshader 소프트 추론이라 지연이 실기기와 다를 수 있다.

### 방법 A — `OcrFieldTest` 계측 하네스(권장, 자동 표 출력)
`field_samples/` 실배틀 5장을 실 파이프라인에 통과시키는 하네스. 에뮬/실기기 연결 후:
```bash
cd android
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pochamps.supporter.OcrFieldTest
adb logcat -s OcrFieldTest      # 이미지별 인식라인/매칭root/지연ms/판정 표
```
- 테스트 3개: `k3_ocr_실측_field_samples`(기본 경로), `k3_ocr_전처리_grayscale_contrast`(전처리 비교),
  `k3_diag_hippowdon2_roi_variants`(ROI 진단 격자). 특정 1개만: 위 `class=...#메서드명`.
- **P9 실측 결론**: 기본(전처리 없음) 경로가 7/7. GRAYSCALE_CONTRAST 는 이 샘플에선 개선 없음(실기기 저대비 대비 옵션).
  hippowdon2 오인식은 ROI 하단 클리핑이 원인이라 **ROI bottom 확장**으로 해결(전처리 아님).
정확도가 낮으면 아래 "튜닝 순서"대로 조정. (샘플이 부족하면 실배틀 스크린샷을 assets 에 추가.)

### 방법 B — 실배틀 logcat

### logcat 태그
debug APK 로 실행 후:
```bash
adb logcat -s CaptureService CaptureManager RecognitionPipeline OcrEngine
```
관찰 포인트:
- `CaptureService`: "파이프라인 시작 완료" → 세션/파이프라인 기동.
- `CaptureManager`: 프레임 수신 흐름(다운스케일/스로틀).
- `RecognitionPipeline`: "프레임 처리 실패" 경고가 잦으면 크롭/OCR 문제.
- `OcrEngine`: 추출된 라인.

### 실측 방법
1. 대전 화면에서 상대 포켓몬 여러 종을 노출시키며 **인식된 이름**과 **정답**을 대조 → 정확도(%).
2. OCR 호출당 지연(ms)을 로그 타임스탬프로 집계. **목표 100~400ms**(DESIGN.md 3장).
3. 정확도가 낮으면 튜닝 순서:
   - **ROI 여백(특히 하단)을 넓혀 글자 잘림 방지** — P9 실측에서 가장 효과 큰 튜닝(hippowdon2 클리핑 사례).
   - `RoiCropper` 업스케일 배율 상향(2x → 3x) — 작은 폰트 개선.
   - `OcrEngine(..., Preprocess.GRAYSCALE_CONTRAST)` — 저대비/이탤릭 프레임 대비(P9 샘플엔 무효였으나 실기기 프레임 대비).
   - `FrameGate` `diffThreshold`/최소 인터벌 조정(과트리거/미트리거).
   - 언어 설정이 게임 언어와 일치하는지(설정 → 게임 언어) — recognizer 스크립트가 바뀐다.

### 발열/전력 확인
- 이름이 안 바뀌는 동안 OCR 이 **안 돌아야** 한다(`FrameGate` 가 변화 없을 때 트리거 안 함).
  로그에 유휴 시 OCR 호출이 없으면 정상.

---

## (e) 온보딩 & UI 흐름 체크리스트

### 온보딩(DESIGN.md 6장)
- [ ] 최초 실행 → "화면에 보이는 정보만 표시" 고지 카드 노출.
- [ ] 오버레이 권한 미허용 상태에서 "권한 설정 열기" → 시스템 설정 이동 → 허용 → "다시 확인" 시 `허용됨 ✓`.
- [ ] "배틀명 표시 ON" 게임 설정 안내 카드 노출.
- [ ] 오버레이 권한 없으면 "시작" 버튼 비활성, 안내 문구 표시.
- [ ] "시작(화면 캡처)" → POST_NOTIFICATIONS 요청(Android 13+) → MediaProjection 동의 다이얼로그.
- [ ] 동의 후: 상태바 "캡처 중" 알림 + 오버레이가 게임 위에 뜸.
- [ ] "데모 카드(캡처 없이)" → MediaProjection 없이 한카리아스 카드가 뜸(UI 1차 확인).

### 오버레이 UI (P2~P4)
- [ ] 오버레이가 `TYPE_APPLICATION_OVERLAY` 로 게임 위에 실제로 뜬다.
- [ ] **터치 통과**: 카드 밖을 탭하면 게임에 전달된다(소형 창 전략).
- [ ] **드래그**: 상단 그립으로 이동 + 앱 재시작 후 위치 복원.
- [ ] **3단계 탭 순환**: 칩 → 카드 → 확장 → 칩, 슬롯별 독립.
- [ ] **확장 패널**: 종족값/방어 상성/전체 기술, 스크롤, 8초 무조작 자동 축소(조작 시 리셋).
- [ ] **더블배틀 2카드**: ROI 2곳 인식 시 카드 2장 세로 스택.
- [ ] **후보 선택**: "바꾸기 ▸" → 후보 타입칩+사용률+추천배지, 선택 후 같은 표시명 유지.
- [ ] **메가 세그먼트**: [기본][메가]/[메가 X][메가 Y] 전환 시 타입·특성·종족값 스왑, 기술은 그대로.
- [ ] **수동 검색(IME)**: "인식 실패 🔍" 탭 → 검색 시트 → **키보드로 입력 가능**(아래 IME 항목 필독) → 선택 시 핀 고정 → "📌 고정 해제" 로 복귀.
- [ ] **캡처 중단 상태**(P5): 화면 잠금 시 "캡처 중단됨 + ▶ 재시작" 카드 → "재시작" 탭 시 앱 재열림.
- [ ] **미인식 안내 배너**(P5): 시작 후 ~20초간 한 번도 인식 없으면 "배틀명 표시 ON" 배너 1회 → "닫기" 로 사라짐, 인식 성공 시 자동 사라짐.

---

## (f) 알려진 제약 / 트러블슈팅

### IME 포커스 (P5 수정 반영)
- 오버레이 창은 평소 `FLAG_NOT_FOCUSABLE`(게임 키 포커스 보존). 수동 검색 시트가 열리면
  이 플래그를 **잠시 제거**해 키보드 입력을 받고, 닫으면 복원한다(`OverlayRenderer.setFocusable`).
- **확인 포인트**: 검색 시트에서 소프트 키보드가 뜨고 글자가 입력되는가. 안 되면:
  - 로그로 `updateViewLayout` 예외 여부 확인.
  - 일부 기기/런처는 오버레이 창 IME 를 제약할 수 있음 → 그 경우 검색을 MainActivity(일반 창)로
    분리하는 대안 고려(현재는 오버레이 내 입력이 기본).

### Android 14+ 재동의
- MediaProjection 토큰은 **1회성**(캐싱 불가). 앱 재시작/캡처 중단(화면잠금) 후 **다시 동의**해야 한다.
- 그래서 "캡처 중단됨" 카드의 "재시작" 은 앱을 다시 열어 동의 다이얼로그를 새로 띄운다(정상 동작).
  - 재시작은 기존 토큰을 재사용하지 **않는다**(재사용 시 Android 14+ 에서 SecurityException).
    `restartCapture()` 가 서비스를 `stopSelf()` 하고 MainActivity 를 앞으로 가져오면, 액티비티가
    `ON_RESUME` 에서 "시작" 버튼을 복원해(P7 수정) 유저가 `createScreenCaptureIntent` 로 재동의한다.
    화면잠금 자동중단·상태바 칩 중단·유저 중단 모든 경로가 이 재동의 흐름을 탄다.

### FGS specialUse — Play 심사 주의(배포 단계)
- 데모 경로(MediaProjection 토큰 없이 오버레이 UI 만 검증)는 `FOREGROUND_SERVICE_SPECIAL_USE` 타입으로
  FGS 를 시작한다(매니페스트 `foregroundServiceType="mediaProjection|specialUse"` +
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE="overlay_ui_demo_without_capture"`).
- **Google Play 정책상 `specialUse` FGS 는 콘솔에 사용 사유 선언·심사가 필요**하다. 배포 시 앱 콘텐츠 →
  포그라운드 서비스 항목에 "MediaProjection 동의 없이 오버레이 UI 를 미리보기(데모)하기 위한 단기 FGS" 취지로
  기재해야 한다. 심사 통과가 어려우면 데모 경로를 릴리즈 빌드에서 제외하고 `specialUse` 타입/권한/속성을
  매니페스트에서 제거하는 것도 대안(실캡처 경로는 `mediaProjection` 타입만 사용하므로 무관).

### Android 15 순서
- SYSTEM_ALERT_WINDOW 앱이 mediaProjection FGS 를 시작하려면 **보이는 오버레이가 먼저** 떠 있어야 함.
  서비스가 `onStartCommand` 에서 "오버레이 show() → startForeground(MEDIA_PROJECTION)" 순으로 처리한다.
  `ForegroundServiceStartNotAllowedException` 이 뜨면 이 순서/오버레이 권한을 의심.
- BOOT_COMPLETED 자동 시작 불가 → **매 세션 사용자 수동 시작**.

### 상태바 "캡처 중" 알림
- POST_NOTIFICATIONS(Android 13+) 거부 시 알림이 안 뜬다(FGS 자체는 동작). 온보딩에서 요청함.
- Android 15 QPR1+/16 은 캡처 중 상태바 칩을 상시 노출(정책).

### 터치/드래그
- 시트/확장으로 창이 커진 상태에서도 창 밖 게임 터치가 통과하는지, 화면경계 클램프가 정상인지 확인.

### Image→Bitmap 정합
- 일부 GPU 는 `rowStride` 패딩이 있어 이미지가 우측으로 밀릴 수 있다. 패딩 크롭이 되는지
  (카드에 엉뚱한 이름/깨진 크롭) 확인. 문제 시 `CaptureManager` 의 패딩 처리 로그 확인.

### 약관/안티치트
- 경쟁 VGC 타이틀이므로 **"화면에 공개된 정보 표시"에만** 한정. 메모리 훅/자동입력 없음.
- **K4 판정(P10 완료)**: 아래 (g)절 참조. **공인 대회는 명백 금지, 캐주얼도 회색지대** → 앱은 **캐주얼 전용으로 포지셔닝**하고 온보딩에 경고 고지.

---

## (g) K4 — EULA / 대회 규정 조사 (P10, 2026-07-05)

WebSearch/WebFetch 로 포켓몬사(TPCi) 약관·Play! Pokémon 대회 규정에서 외부 보조 도구/오버레이/화면 캡처 조항 조사.

### 근거 (발췌 + URL)
| 출처 | 조항(발췌) | URL |
|---|---|---|
| TPCi **Terms of Use** §5 | "Use, or facilitate the use of, any **unauthorized third-party software** (e.g. bots, mods, hacks, and scripts) … to **modify or automate** operation within the Service" | https://www.pokemon.com/us/legal/terms-of-use |
| TPCi **Terms of Use** §7 | 위반 시 "**sole discretion**" 계정 정지·해지 권한 | 〃 |
| **Play! Pokémon VG Rules** §2.7 | "**No written or printed aids, including type charts, are permitted in the play space.**" | https://www.pokemon.com/static-assets/content-assets/cms2/pdf/play-pokemon/rules/play-pokemon-vg-rules-formats-and-penalty-guidelines-en.pdf |
| **Play! Pokémon VG Rules** §4.3 | "The use of external devices, such as **a mobile app** … is expressly forbidden." | 〃 |
| Champions=공식 VGC 플랫폼 전환(2026) | Champions 배틀 = 공인 대회 플랫폼 | https://www.pokemon.com/us/news/play-pokemon-competitions-transition-to-pokemon-champions-on-april-and-may-2026 |

> ⚠️ Champions **전용** EULA(`web-view.app.pokemonchampions.jp/docs/terms/`)는 JS 렌더라 헤드리스 추출 불가 →
> Champions-verbatim 조항은 미확보(배포 전 실브라우저로 원문 확인 권장). 위는 Champions 계정에 적용되는 TPCi 공통 약관 + Champions 에 적용되는 대회 규정.

### 판정
- **캐주얼/래더 → 불명확(리스크).** 본 앱은 메모리 읽기·입력 자동화·변조가 없어 §5 핵심(*modify/automate*)에는 문자적으로 해당 안 될 여지가 크나,
  "unauthorized third-party software" 문구가 넓고 §7 재량 해지가 있어 **완전 안전이라 단정 불가**.
- **공인 대회(랭크전/Play! Pokémon/VGC) → 금지(명확).** §2.7 은 **종이 타입표조차** 금지 → 실시간 오버레이는 명백 위반·실격 사유.
- **결론**: 캐주얼·연습 전용으로 포지셔닝, 공식/대회 대전 시 앱 종료 안내. 앱 온보딩에 경고 고지 반영(`onboarding_legal_*`).

---

## 판정 요약 시트

| 항목 | P8/P9 상태 | 실기기 판정 | 메모 |
|---|---|---|---|
| K1 FLAG_SECURE | **GO(유력)** — 모바일 화면녹화 영상 존재 | PASS / FAIL | 대전 화면 직접 녹화로 최종 확인만. FAIL(전면차단)이면 중단 |
| K2 표시명 문자열 | **확정** — base 종족명, DB와 일치 | | 리전폼("윈디"vs"히스이 윈디")·닉네임만 확인 |
| K2 ROI 좌표 | **코드 교정 완료**(우상단 배치 + P9 싱글 하단 확장) | | 실기기 픽셀 미세조정만 |
| K3 인식 정확도(%) | **P12 에뮬 실측 9/9(100%)**(P9 7 + 실배틀 Sylveon/Hippowdon 2) | | 에뮬에서 확인됨. 실기기 추가 샘플만 |
| K3 지연(ms) | **P9 에뮬 avg 47ms·p50 28ms** | | 목표 100~400ms 여유. 실기기 지연/발열 재확인 |
| **E2E 실캡처 경로** | **P10 정지 3/3 + P11 동적 통과** | | P11: 장면전환 카드갱신·연출프레임 무깜빡임·메뉴 오탐0·실배틀 프레임 정확. 실기기 59.94fps 실시간 스트림만 잔여 |
| **동적 오인식 억제** | **P11 히스테리시스 구현+테스트(8)** | | 약매칭 다른포켓몬 전환은 연속2회 확인, 고신뢰(≤editDist1) 즉시. 깜빡임/오인식 구조 차단 |
| **ROI 강건화(다중라인 매칭)** | **P12 matchBest + 세로밴드 확장, 재실측 9/9** | | 인접 UI(`MOVE TIME`)를 사전매칭이 걸러 밴드 확장 안전. Sylveon 실배틀 프레임 editDist 0 |
| **코드 리뷰(전체)** | **P12 실트리거 버그 2건 수정**(메가선택 누수·데모 FGS API34 가드) | | 회귀테스트 8. 이론적 결함은 기각 |
| K4 EULA/규정 | **판정 완료(캐주얼 불명확·대회 금지), 앱 고지 반영** | | (g)절 참조. Champions-verbatim EULA 원문만 실브라우저 확인 잔여 |
| **앱 아이콘/이름** | **P11 어댑티브 아이콘 + en 라벨 정리** | | 카드+타입칩 모티브(오리지널). en `PokeChamps Supporter`. 런처 렌더·release 포함 확인 |
| APK ABI 축소(배포전) | **P9.1 완료(release 16.6MB, arm64 단일)** | | Play 배포 시 AAB 전환 |
| 온보딩 흐름 | | | |
| 오버레이/터치/드래그 | | | |
| IME 검색 입력 | | | P5 포커스 토글 확인 |
