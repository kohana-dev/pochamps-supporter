package com.pochamps.supporter.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 브랜드 팔레트(P33) — 런처 아이콘과 오버레이 카드에서 그대로 가져온 색.
 *
 * 아이콘 배경 대각 그라디언트: #2B3B5E → #16203A (딥 네이비).
 * 아이콘 액센트/이름줄:       #8AB4F8 (액센트 블루) — 앱의 시그니처 색.
 * 타입칩:                     #4F86D8(파랑) / #6DAE4A(그린) — 타입 상성 표시 모티브.
 * 오버레이 카드 본체:          #1A1A1A (다크 서페이스).
 *
 * 게임 유틸리티 톤: 어두운 네이비 베이스 + 선명한 블루 액센트. 다크가 기본,
 * 라이트는 밝은 화면/접근성 대응으로 함께 정의(한 세트로 설계 — dark-mode-pairing).
 */

// ── 브랜드 원색 ───────────────────────────────────────────────────────────
val BrandBlue = Color(0xFF8AB4F8)      // 액센트 블루(아이콘 이름줄)
val BrandBlueDeep = Color(0xFF4F86D8)  // 진한 파랑 타입칩
val BrandGreen = Color(0xFF6DAE4A)     // 그린 타입칩(성공/완료 액센트)
val NavyBase = Color(0xFF16203A)       // 딥 네이비(그라디언트 끝)
val NavyRaised = Color(0xFF2B3B5E)     // 네이비(그라디언트 시작 — 표면 상승)

// ── 다크 스킴(기본) ───────────────────────────────────────────────────────
// 배경은 순검정이 아니라 브랜드 네이비 계열로 낮춰 게임 유틸리티 톤 유지.
val DarkPrimary = BrandBlue
val DarkOnPrimary = Color(0xFF0A1220)        // 밝은 파랑 위 진한 네이비 텍스트(대비 확보)
val DarkPrimaryContainer = Color(0xFF274063) // 파랑 컨테이너(버튼/칩 배경)
val DarkOnPrimaryContainer = Color(0xFFD6E3FF)

val DarkSecondary = BrandGreen
val DarkOnSecondary = Color(0xFF07230A)
val DarkSecondaryContainer = Color(0xFF2E4A22)
val DarkOnSecondaryContainer = Color(0xFFCDEEBE)

val DarkBackground = Color(0xFF0E1526)       // 네이비 기반 배경(#16203A 보다 더 낮춤)
val DarkOnBackground = Color(0xFFE3E6ED)
val DarkSurface = Color(0xFF141C30)          // 화면 표면
val DarkOnSurface = Color(0xFFE3E6ED)
val DarkSurfaceVariant = Color(0xFF1E2A44)   // 카드/그룹 표면(살짝 상승)
val DarkOnSurfaceVariant = Color(0xFFAFBAD0) // 보조 텍스트
val DarkSurfaceContainer = Color(0xFF19233B) // 카드 컨테이너
val DarkOutline = Color(0xFF3A486A)          // 구분선/보더
val DarkOutlineVariant = Color(0xFF283552)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

// ── 라이트 스킴(대응) ─────────────────────────────────────────────────────
val LightPrimary = Color(0xFF2C5AA8)         // 라이트에선 블루를 진하게(대비 4.5:1↑)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFD6E3FF)
val LightOnPrimaryContainer = Color(0xFF001B3D)

val LightSecondary = Color(0xFF3F6E27)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFC0F0A0)
val LightOnSecondaryContainer = Color(0xFF0A2100)

val LightBackground = Color(0xFFFBFCFF)
val LightOnBackground = Color(0xFF1A1C20)
val LightSurface = Color(0xFFFBFCFF)
val LightOnSurface = Color(0xFF1A1C20)
val LightSurfaceVariant = Color(0xFFE1E7F2)
val LightOnSurfaceVariant = Color(0xFF44474F)
val LightSurfaceContainer = Color(0xFFEEF1F8)
val LightOutline = Color(0xFF74777F)
val LightOutlineVariant = Color(0xFFC4C7CF)

val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

// ── 상태 액센트(테마 무관 — 상태 카드에서 직접 사용) ──────────────────────
val StatusRunningGreen = BrandGreen          // 실행 중
val StatusIdleGray = Color(0xFF8A93A6)       // 중지/대기
