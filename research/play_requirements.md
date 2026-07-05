# Google Play 출시 요건 조사 (2025~2026) — A2 산출물

## 1. 민감 권한 선언 (SYSTEM_ALERT_WINDOW / MediaProjection)
- Play Console "Permissions Declaration Form" — 민감 권한 요청 시 릴리스 과정에서 노출, 확장 심사(수 주) 대상.
- SYSTEM_ALERT_WINDOW 는 악용 가능성 때문에 민감 권한 취급(사용자 설정 수동 허용).
- (추정) 이 권한이 선언 양식 대상 목록에 명시 포함되는지는 콘솔 업로드 시 확인 필요.
- https://support.google.com/googleplay/android-developer/answer/9214102

## 2. FGS 유형 선언 (MEDIA_PROJECTION / SPECIAL_USE)
- target 34+ 는 Play Console "App content" 에서 FGS 유형 선언 필수: (a) 기능 설명 (b) 지연/중단 시 사용자 영향 (c) **기능 트리거 사용자 흐름 데모 영상 링크** 제출.
- TYPE_MEDIA_PROJECTION 표준 문구 예: "Project media to non-primary display or external devices using the MediaProjection APIs, including streaming."
- SPECIAL_USE 는 자유 서술 justification 필요, 리뷰 대상.
- https://support.google.com/googleplay/android-developer/answer/13392821

## 3. Target SDK
- 2025.8.31 기준 신규/업데이트는 API 35(Android 15) 이상. 제출 직전 최신 공지 재확인 필요.
- https://support.google.com/googleplay/android-developer/answer/11926878

## 4. Data Safety 폼
- "수집" = 기기 밖 전송. **온디바이스 처리·전송 0이면 "No data collected" 선언 가능** — 우리 앱 해당.
- https://support.google.com/googleplay/android-developer/answer/10787469

## 5. 개인정보처리방침
- 민감 권한 앱은 Play Console + 앱 내부 모두 링크 필수. GitHub Pages 호스팅 실사용 다수(공식 금지 없음, uptime 유지 필요).

## 6. 콘텐츠 등급(IARC)
- 설문 → 자동 등급. 등급 없으면 게시 불가.
- https://support.google.com/googleplay/android-developer/answer/9898843

## 7. 신규 개인 계정 테스트 요건 ★
- 2023.11.13 이후 개인 계정: 프로덕션 전 **12명 옵트인 테스터 × 14일 연속** 비공개 테스트 필수(2024.12.11에 20→12명 완화). **조직 계정은 면제.**
- https://support.google.com/googleplay/android-developer/answer/14151465

## 8. 앱 서명
- 신규 앱은 AAB + Play App Signing(사실상 필수). upload key 별도.
- https://support.google.com/googleplay/android-developer/answer/9842756

## 실무 시사점
- 민감 요소 2종(overlay+projection) → 확장 심사 감안해 일정 여유.
- **FGS 데모 영상 사전 촬영 필요.**
- 개인 계정이면 12명×14일 먼저. (조직 계정 전환 시 면제)
