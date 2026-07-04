# field_samples 출처 (SOURCES)

> P8(실게임 화면 기반 K1~K3 사전 검증)에서 웹의 실게임 자료로 수집한 배틀 화면 샘플.
> **저작권상 로컬 검증 전용** — 재배포 금지. 원저작권은 각 영상 제작자 / 주식회사 포켓몬 / GAME FREAK.
> ROI/OCR 좌표 측정과 온디바이스 OCR 실측 목적으로만 사용.

수집일: 2026-07-05 · 도구: yt-dlp(최신 standalone) + ffmpeg 프레임 추출

## 샘플 목록

| 파일 | 내용 | 언어/UI | 플랫폼 | 배틀형식 | 출처 |
|---|---|---|---|---|---|
| 01_single_hippowdon_meowscarada_raw.jpg | 커맨드 단계, 상대 Hippowdon(우상단), 내 Meowscarada(좌하단) | 영어 | **모바일 raw 캡처**(페이스캠 없음, 레터박스) | 싱글 | GameXplain? → 아래 DM Gaming |
| 02_single_hippowdon_b_raw.jpg | 같은 배틀 다른 프레임 | 영어 | 모바일 raw | 싱글 | DM Gaming |
| 03_doubles_typhlosion_charizard.jpg | 커맨드 단계, 상대 Typhlosion+Charizard(우상단 2개) | 영어 | 모바일(페이스캠 합성) | 더블 | GameXplain |
| 04_doubles_typhlosion_torkoal.jpg | 상대 Typhlosion+Torkoal | 영어 | 모바일(페이스캠) | 더블 | GameXplain |
| 05_doubles_typhlosion_charizard_b.jpg | 더블 다른 프레임 | 영어 | 모바일(페이스캠) | 더블 | GameXplain |
| 06_dialogue_english_ui_raw.jpg | 로비 대화(레터박스=모바일 raw 확인용) | 영어 | 모바일 raw | - | DM Gaming |
| 07_ko_single_gyarados_switch.jpg | 상대 갸라도스(우상단), 메시지 "상대 갸라도스에게…" | **한국어** | 스위치(캡처보드, K1 증거 아님) | 싱글 | 포랑 |
| 08_ko_battle_switch.jpg | 한국어 배틀 프레임 | 한국어 | 스위치 | - | 포랑 |
| crop_single_plate_hippowdon.png | 싱글 이름표 확대(Hippowdon) | 영어 | 모바일 | - | DM Gaming |
| crop_doubles_plates.png | 더블 이름표 2개 확대(Typhlosion/Charizard) | 영어 | 모바일 | - | GameXplain |
| crop_ko_plate_gyarados.png | 한국어 이름표 확대(갸라도스) | 한국어 | 스위치 | - | 포랑 |

## 원본 영상 URL

- **DM Gaming** — "Should You Play...Pokémon Champions? (Mobile Review)"
  https://www.youtube.com/watch?v=yWHjy1Pp3PU (업로드 2026-06-18, 모바일 launch 직후)
  → **모바일 raw 화면 캡처**(페이스캠 없음). K1(화면녹화 가능) + K2(레터박스/좌표) 핵심 근거.
- **GameXplain** — "Pokemon Champions is Out on Mobile! - Full Tour & Battle"
  https://www.youtube.com/watch?v=QnOUEEd-oSA (업로드 2026-06-17, **모바일 출시 당일**)
  → 모바일 배틀을 화면 캡처해 스트리밍(페이스캠 합성). K1 강한 증거 + K2 더블 레이아웃.
- **포랑** — "한카리아스와 메가아쿠스타.. 너무 강하다앗!! [포켓몬 챔피언스]"
  https://www.youtube.com/watch?v=T1mmelGgyF8 (업로드 2026-04-10, 2560×1440)
  → **스위치 캡처보드**(모바일 아님) → **K1 증거로는 쓰지 않음**. 한국어 이름표 폰트/레이아웃 확인(K2/K3)용으로만 사용.

## K2 측정 요약 (게임 뷰포트 비율, ffmpeg+PIL 픽셀 측정)

이름표 = [종족 아이콘 + 종족명 텍스트 + 성별기호] / HP바 / HP%. **상단 우측**에 위치.
좌하단은 "내" 포켓몬(상대 아님).

| 소스 | 형식 | 이름표 박스 x | y |
|---|---|---|---|
| DM raw(모바일) | 싱글 | 0.72–0.89 (텍스트 0.75–0.88) | 0.05–0.14 |
| GameXplain | 더블 좌 | 0.595–0.755 | 0.03–0.14 |
| GameXplain | 더블 우 | 0.802–0.995 | 0.04–0.14 |
| 포랑(스위치) | 싱글(KO) | ~0.71–0.90 | 0.02–0.16 |

- 모바일 raw 캡처의 **게임 뷰포트 종횡비 ≈ 2.17:1 (≈19.5:9)** — 화면 폭 꽉 채우고 상/하 레터박스.
  실기기 MediaProjection 은 기기 네이티브 해상도(대개 20:9 landscape)를 캡처 → 뷰포트 비율 ≈ 프레임 비율.
- **표시명 형식(K2 확정)**: 폼 접두어/닉네임 없는 **base 종족명 단독**(관측 샘플 범위 내). 리전폼 문자열은
  샘플에 리전폼 등장이 없어 미확인(실기기 잔여). 닉네임 노출 사례도 샘플엔 없음.
