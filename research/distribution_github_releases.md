# GitHub Releases APK 배포 관행 조사 — A2 산출물 (배포 전략)

## 병행 배포 사례 (확인)
- **Signal**: F-Droid 미등록(자체 서명 방침), GitHub Releases에 APK 첨부 + 자체 업데이트 체크 내장. https://github.com/signalapp/Signal-Android/releases
- **NewPipe**: F-Droid + GitHub Releases 병행. 단 **서명키가 달라 상호 업데이트 불가**(공식 FAQ 명시) — 채널 전환 시 재설치. https://newpipe.net/FAQ/install/
- **Fossify**: F-Droid + GitHub Releases 병행 확인.
- 반례 **AntennaPod**: 리소스 부족 이유로 GitHub APK 배포 공식 거부(Play+F-Droid만).

## 설치 마찰 (확인)
- Android 8+: 앱별 "출처 불명 앱 설치" 권한 필요.
- **Play Protect 하드 블록 권한**: 인터넷 사이드로드 앱이 RECEIVE_SMS/READ_SMS/NOTIFICATION_LISTENER/ACCESSIBILITY 요청 시 "Install Anyway" 없이 자동 차단. → **우리 앱은 이 4개를 안 쓰므로 경고만 뜨고 설치 가능** (개별 검증은 추정). https://developers.google.com/android/play-protect/warning-dev-guidance

## 업데이트 경로
- Signal 방식: APK에 자체 업데이트 체크 내장 (우리 P13 manifest 구조와 유사 — **앱 데이터 갱신은 이미 있음, APK 자체 갱신 체크만 추가하면 동일 패턴**).
- **Obtainium**: GitHub Releases 폴링해 업데이트 알림(6h 주기) — 우리 저장소가 public이므로 지금도 사용 가능.
- AppUpdater 라이브러리(GitHub/Play/F-Droid 소스 지원).

## 시사점
- 사이드로드 배포 시: GitHub Releases에 서명 APK 첨부 + README에 Obtainium 안내 + (선택) 앱 내 APK 업데이트 체크(자체 manifest에 versionCode 포함— P13 인프라 재사용).
- 서명키 일관성 유지(채널 간 키 분리 문제 회피 — 단일 채널이면 무관).

## Obtainium 상세 (추가 조사)
- GitHub Releases API 폴링(6h 기본), Android 12+ 완전 자동 업데이트. GPL-3.0, 18k+ stars, 활발(2026-06 v1.5.2).
- **"Add to Obtainium" 배지/딥링크**를 README에 넣으면 원탭 등록: `obtainium://add/<releases-url>`.
- 한계(정직): Obtainium 자체가 사이드로드 필요 + 기술 유저 전용이라는 평가가 HN/PrivacyGuides에서 반복 — **일반 유저 도달엔 부적합**, 파워유저 보조 채널로만.
- 시사점: GitHub Releases에 서명 APK + README에 Obtainium 배지 + 앱 내 자체 업데이트 체크(P13 manifest 확장)가 사이드로드 3종 세트.
