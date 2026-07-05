# IP/법적 리스크 조사 — A2 산출물 (2026-07-05, 인라인 조사)

## 판정: Play 출시 리스크 = **중(관리 가능)** — 단 아래 가이드 엄수 시

## 결정적 생존 선례 (우리와 동일 아키텍처)
- **Calcy IV** — 포켓몬 GO 화면을 **오버레이+화면캡처+OCR**로 분석하는 앱. 수년째 Play/App Store 생존, 게임 파일·네트워크 비개입("purely a screen recorder and overlay")이 ToS 준수 근거. https://play.google.com/store/apps/details?id=tesmath.calcy
- **Poke Genie** — 스크린샷/오버레이 기반 IV 계산기, Play 생존(수백만 설치). 로그인·게임 개입 없음. https://play.google.com/store/apps/details?id=com.cjin.pokegenie.standard
- 공통 생존 요인: ① 화면에 공개된 정보만 사용 ② 게임 클라이언트/서버 비개입 ③ 공식 아트워크 미사용 ④ 이름에 "Pokémon" 완전명 미사용("Calcy", "Poke Genie").

## 삭제/차단된 패턴 (반례)
- 2011년 안드로이드 포켓덱스 앱 대량 DMCA — 공식 아트워크/스프라이트 사용 + "Pokédex" 상표성 명칭이 원인. https://nolanlawson.com/2011/05/11/nintendo-used-takedown-its-super-effective/
- 팬게임(Uranium 등)·팬게임 툴(Essentials)·팬게임 배포 사이트(Relic Castle 2024) — **게임 콘텐츠 자체를 재현/배포**하는 경우 예외 없이 제거.
- 원칙: 닌텐도는 "게임을 대체·재현"하거나 "공식 에셋을 쓰는" 것에 공격적, **사실 데이터 기반 도우미 도구**에는 역사적으로 관용적.

## 안전 가이드 (엄수)
1. **네이밍**: "Pokémon"/"포켓몬" 완전명을 앱 이름에 넣지 말 것. 현재 "포챔스 서포터/PokeChamps Supporter"는 Poke Genie 패턴과 유사해 수용 가능 범위(추정). 더 안전하게는 "Champs Supporter" 계열도 후보.
2. **에셋**: 공식 아트워크·스프라이트·로고 절대 미사용(현재 오리지널 아이콘 — 유지). 포켓볼 모사 금지(이미 준수).
3. **문구**: 스토어 설명·앱 내에 "비공식 팬메이드 도구, Nintendo/Creatures/GAME FREAK/The Pokémon Company와 무관, 모든 관련 상표는 각 소유자 소유" 고지 필수. 상표는 지시적 사용(설명 목적)만.
4. **데이터**: 종족값·타입·사용률 등 **사실 정보는 저작권 비대상** — 우리 DB는 안전. 단 공식 텍스트(도감 설명문 등) 통짜 복제 금지(현재 미포함 — 유지).
5. **기능 경계**: 화면 공개 정보만, 메모리·네트워크 비개입, 자동입력 없음(현재 준수) — Calcy IV 생존 논리 그대로.

## 결론
- **사이드로드(GitHub Releases) 우선 배포 = 리스크 최소**로 시작, Play는 위 가이드 적용 + 12명×14일 테스트 거쳐 진행(선례상 통과 가능성 높음, 단 게임사 재량 리스크 잔존).
