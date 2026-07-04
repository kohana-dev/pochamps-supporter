# field_samples/video 출처 (P11 동적 E2E)

> **로컬 검증 전용 — 재배포 금지.** 원저작권은 각 영상 제작자 / 주식회사 포켓몬 / GAME FREAK.
> 움직이는 배틀 화면에서의 파이프라인 거동(FrameGate 스로틀·깜빡임·오인식) 검증 목적으로만 사용.

수집일: 2026-07-05 · 도구: yt-dlp 2026.06.09(brew) + ffmpeg

## 파일

| 파일 | 내용 | 출처 |
|---|---|---|
| `battle_dmgaming_yWHjy1Pp3PU.mp4` | DM Gaming 모바일 리뷰 00:00–03:00(720p·16:9·59.94fps). 인트로/메뉴/대화 위주 | DM Gaming, https://www.youtube.com/watch?v=yWHjy1Pp3PU |
| `battle2_yWHjy1Pp3PU.mp4` | 같은 영상 06:00–08:00. **실제 배틀 프레임 포함**(Sylveon·Hippowdon 이름표) | 〃 |
| `real_battle_frames/real_sylveon_battle.jpg` | 실배틀 커맨드 화면, 상대 **Sylveon**(우상단) | battle2 t≈25s 프레임 |
| `real_battle_frames/real_hippowdon_moveselect.jpg` | 실배틀 기술선택 화면, 상대 **Hippowdon**(우상단) | battle2 t≈35s 프레임 |
| `synthetic_battle_compat.mp4` | **합성 배틀 영상**(H.264 baseline, 13s): Hippowdon → (fadeblack) → Typhlosion+Charizard(더블) → (dissolve) → Typhlosion+Torkoal. field_samples 검증완료 프레임 + xfade 연출 프레임으로 구성 | 로컬 합성(ffmpeg) |

## 다운로드 노트 (정직 기록)
- 저장소 기본 `yt-dlp`(pip, 2023.11.16, Python 3.7)는 현재 YouTube에서 400/스토리보드-only 로 **실패**. Python 3.7 이 최신 yt-dlp 미지원.
- `brew` 의 `yt-dlp 2026.06.09` 으로 **실제 다운로드 성공**(영상 실재 확인). `--download-sections`로 구간만 받음.
- **에뮬레이터 VideoView 재생 실패**: API 35 스코프드 스토리지로 앱 프로세스(mediaserver)가 `/sdcard` mp4 를 열지 못함(setDataSource 예외).
  → 정지 이미지/합성 프레임을 **run-as 로 앱 내부 저장소에 넣어 `SampleImageActivity`(ImageView)로 순차 표시하는 슬라이드쇼**로 동적 거동을 검증(태스크 허용 대안).
  실배틀 프레임(위 2장)과 합성 시퀀스 프레임 모두로 검증 완료.
