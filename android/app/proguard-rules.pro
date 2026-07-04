# ====================================================================
# 포챔스 서포터 — R8/ProGuard keep 규칙 (릴리스 minify 대응)
# ====================================================================

# ------------------------------------------------------------------
# kotlinx-serialization
#   @Serializable 클래스의 직렬화 메타데이터/합성 serializer 를 보존해야
#   난독화 후에도 JSON 파싱이 동작한다.
# ------------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,*Annotation*,InnerClasses,EnclosingMethod

# 직렬화 대상(우리 데이터 모델)의 Companion / serializer() 보존.
-keepclassmembers class com.pochamps.supporter.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.pochamps.supporter.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# @Serializable 이 붙은 클래스와 그 합성 $serializer 전부 보존(안전망).
-keep,includedescriptorclasses class com.pochamps.supporter.**$$serializer { *; }
-keepclassmembers class com.pochamps.supporter.** {
    *** Companion;
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# kotlinx-serialization 런타임 자체가 요구하는 규칙(라이브러리 consumer rules 보완).
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** *;
}
-dontnote kotlinx.serialization.**

# ------------------------------------------------------------------
# ML Kit Text Recognition (on-device, 번들 모델)
#   내부적으로 리플렉션/네이티브 브릿지를 사용하므로 GMS/ML Kit 패키지를 보존.
#   (대부분 라이브러리 consumer rules 가 처리하지만 명시로 안전 확보.)
# ------------------------------------------------------------------
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# ------------------------------------------------------------------
# Kotlin 코루틴 — 내부 서비스 로더/필드 접근 보존(라이브러리 규칙 보완).
# ------------------------------------------------------------------
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ------------------------------------------------------------------
# Compose 는 AGP/라이브러리 consumer rules 로 충분(별도 keep 불필요).
# 앱 진입점(액티비티/서비스)은 매니페스트 참조로 자동 보존됨.
# ------------------------------------------------------------------
