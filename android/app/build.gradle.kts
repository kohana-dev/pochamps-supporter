plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.pochamps.supporter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pochamps.supporter"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.1.3" // 캡처 건강 모니터: FLAG_SECURE 검은화면 / 프레임미수신 자동 감지(P17)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 원격 데이터 갱신 base URL (DESIGN.md 4-6, P13). manifest.json 이 놓인 정적 호스팅 폴더.
        // 빈 문자열이면 갱신 비활성(내장 assets 만 사용) → 오프라인·미배포 안전.
        // 배포 시 GitHub/Cloudflare Pages 의 dist/ URL 로 교체(끝에 / 포함).
        //   예: "https://<user>.github.io/<repo>/data/dist/"
        // -PdataUpdateBaseUrl=... 로 오버라이드 가능(E2E 로컬 서버 테스트용: http://10.0.2.2:PORT/).
        val dataUpdateBaseUrl = (project.findProperty("dataUpdateBaseUrl") as String?)
            ?: "https://kohana-dev.github.io/pochamps-supporter/data/dist/"
        buildConfigField("String", "DATA_UPDATE_BASE_URL", "\"$dataUpdateBaseUrl\"")
    }

    buildTypes {
        release {
            // R8: 코드 축소/난독화 + 미사용 리소스 제거.
            // keep 규칙은 proguard-rules.pro 참조(kotlinx-serialization / ML Kit).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // v0.1.0: 정식 릴리스 키가 없으므로 디버그 키로 서명(사이드로드 배포/실기기 테스트용).
            //         Play 업로드 시엔 별도 upload key 로 교체할 것.
            signingConfig = signingConfigs.getByName("debug")
            // 사이드로드 대상은 실사용 폰(arm64)뿐이므로 ML Kit 번들 네이티브(.so)를
            // arm64-v8a 하나로 축소 (release APK 44MB → ~17MB).
            // Play 배포 시엔 이 필터를 제거하고 AAB(자동 ABI 분할)로 전환할 것.
            ndk { abiFilters += "arm64-v8a" }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.DEBUG 게이트(P10 파이프라인 E2E 진단 로그)용.
    }

    // JSON 원본은 압축 유지(assets 로드 시 스트림으로 파싱). 필요 시 noCompress 조정.
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // --- 데이터/직렬화 (P1 핵심) ---
    implementation(libs.kotlinx.serialization.json)

    // --- 앱 셸 / Compose ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    // 오버레이 ComposeView 에 ViewTreeSavedStateRegistryOwner 를 부여하기 위해 명시적 의존.
    implementation(libs.androidx.savedstate)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    // --- OCR (P3, P9) — 스크립트별 ML Kit "번들(bundled)" recognizer(com.google.mlkit:text-recognition*).
    //     모델을 APK 에 동봉해 런타임 다운로드 없이 즉시 동작(에뮬레이터 GMS 다운로드 실패 회피). ---
    implementation(libs.mlkit.text.korean)
    implementation(libs.mlkit.text.japanese)
    implementation(libs.mlkit.text.chinese)
    implementation(libs.mlkit.text.latin)

    // --- 코루틴 (P3 파이프라인) ---
    implementation(libs.kotlinx.coroutines.android)

    // --- 유닛 테스트 (순수 JVM) ---
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // --- 계측 테스트 (P8 K3 OCR 실측 하네스; 에뮬레이터/실기기에서 ML Kit 실동작) ---
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
