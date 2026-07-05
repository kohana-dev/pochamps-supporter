package com.pochamps.supporter.ui

/**
 * 오픈소스 라이선스 정적 목록 (P34) — 앱이 의존하는 주요 라이브러리.
 *
 * 자동 생성 도구(OSS Licenses Gradle plugin 등)를 새로 도입하지 않고, 실제 의존성
 * (app/build.gradle.kts)에 맞춰 손으로 관리하는 간단 목록 + 라이선스 전문 링크로 처리한다
 * (신규 SDK/의존성 0 원칙 유지). 대부분 Apache-2.0 이며, 라이선스 전문은 화면에서 링크로 연다.
 */
object Licenses {

    /** 라이선스 종류(전문 링크는 문자열 리소스에서 연결). */
    enum class License(val label: String) {
        APACHE_2_0("Apache License 2.0"),
    }

    /** 라이브러리 한 건. */
    data class Entry(
        val name: String,
        val owner: String,
        val license: License,
    )

    /** 화면에 표시할 항목(app/build.gradle.kts 의존성과 일치). */
    val ENTRIES: List<Entry> = listOf(
        Entry("Google ML Kit — Text Recognition (Korean/Japanese/Chinese/Latin, on-device)", "Google LLC", License.APACHE_2_0),
        Entry("Kotlin (standard library, coroutines)", "JetBrains s.r.o. / Kotlin Foundation", License.APACHE_2_0),
        Entry("kotlinx.serialization", "JetBrains s.r.o.", License.APACHE_2_0),
        Entry("AndroidX Core / Activity / Lifecycle / SavedState", "The Android Open Source Project", License.APACHE_2_0),
        Entry("Jetpack Compose (UI, Material 3)", "The Android Open Source Project", License.APACHE_2_0),
    )
}
