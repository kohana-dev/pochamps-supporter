package com.pochamps.supporter.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pochamps.supporter.R
import com.pochamps.supporter.capture.PrefsRoiConfigStore
import com.pochamps.supporter.data.AppSettings
import com.pochamps.supporter.data.DbUpdateManager
import com.pochamps.supporter.data.LocaleUtils
import com.pochamps.supporter.ui.theme.PochampsTheme
import kotlinx.coroutines.launch

/**
 * 앱 진입점 · 운영급 UI(P33 재설계).
 *
 * 화면 구성:
 *  - 홈(HomeScreen): 브랜드 헤더 → 캡처 상태 카드 + 시작/중지 CTA → (온보딩 미완료 시) 단계 체크리스트
 *    → 미리보기(데모) 섹션 → 설정 진입.
 *  - 설정(SettingsScreen): [표시]/[인식]/[데이터]/[고급]/[정보] 그룹.
 *
 * 서비스·오버레이·파이프라인 코드는 일절 건드리지 않는다(회귀 방지). 시작/중지/데모/재시작(P7)/
 * POST_NOTIFICATIONS 요청/AppSettings 연동은 기존 동작을 그대로 유지한다.
 *
 * Android 15 순서: 오버레이 먼저 → FGS 시작. 실제 오버레이 show()/FGS 시작은 CaptureService 가
 * onStartCommand 안에서 순서대로 처리한다(기존 규정 불변).
 */
class MainActivity : ComponentActivity() {

    // 표시 언어(displayLang) 로케일을 base context 에 적용(P19) — 모든 리소스 조회가 표시 언어로 해석.
    override fun attachBaseContext(newBase: Context) {
        val lang = AppSettings(newBase).displayLang
        super.attachBaseContext(LocaleUtils.wrap(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PochampsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot()
                }
            }
        }
    }
}

/** 앱 내 화면(가벼운 상태 전환 — Navigation 미도입, 기존 구조에 맞는 최소 구현). */
private enum class Screen { HOME, SETTINGS }

@Composable
private fun AppRoot() {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    // 설정 화면에서 시스템 뒤로가기는 액티비티를 종료하지 않고 홈으로 되돌린다(예측 가능한 뒤로가기).
    androidx.activity.compose.BackHandler(enabled = screen == Screen.SETTINGS) {
        screen = Screen.HOME
    }
    when (screen) {
        Screen.HOME -> HomeScreen(onOpenSettings = { screen = Screen.SETTINGS })
        Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.HOME })
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  홈 화면
// ══════════════════════════════════════════════════════════════════════════

@Composable
private fun HomeScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notifGranted by remember { mutableStateOf(context.hasNotificationPermission()) }
    var battleAck by rememberSaveable { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }

    // 캡처 중단 후 오버레이 "재시작"(REORDER_TO_FRONT)은 onCreate 를 재실행하지 않으므로,
    // 액티비티가 다시 보일(ON_RESUME) 때 running=false 로 되돌려 "시작" 버튼(재동의 경로)을 복원한다(P7).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
                notifGranted = context.hasNotificationPermission()
                running = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // POST_NOTIFICATIONS 런타임 권한(Android 13+). 없으면 '캡처 중' 알림이 안 뜬다(FGS 자체는 동작).
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifGranted = granted || !context.notificationPermissionRequired() }

    // MediaProjection 동의 다이얼로그 결과 수신 런처.
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val intent = com.pochamps.supporter.capture.CaptureService
                .startIntent(context, result.resultCode, result.data!!)
            ContextCompat.startForegroundService(context, intent)
            running = true
        }
    }

    val onboarding = OnboardingLogic.compute(
        overlayGranted = overlayGranted,
        notificationRequired = context.notificationPermissionRequired(),
        notificationGranted = notifGranted,
        battleNamesAcknowledged = battleAck,
    )

    fun startCapture() {
        context.requestNotificationPermissionIfNeeded(notifLauncher)
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
    fun startDemo() {
        context.requestNotificationPermissionIfNeeded(notifLauncher)
        ContextCompat.startForegroundService(
            context,
            com.pochamps.supporter.capture.CaptureService.demoIntent(context),
        )
        running = true
    }
    fun stopCapture() {
        context.startService(com.pochamps.supporter.capture.CaptureService.stopIntent(context))
        running = false
    }

    val scrollTopInset = WindowInsets.statusBars.asPaddingValues()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 20.dp, end = 20.dp,
                top = 16.dp + scrollTopInset.calculateTopPadding(),
                bottom = 32.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── 브랜드 헤더 + 설정 진입 ──
        BrandHeader(onOpenSettings = onOpenSettings)

        // ── 상태 카드 + 주 CTA(가장 눈에 띄게) ──
        StatusCard(
            running = running,
            canStart = onboarding.allReady,
            onStart = { startCapture() },
            onStop = { stopCapture() },
        )

        // ── 온보딩 체크리스트(미완료 시) ──
        AnimatedVisibility(visible = !onboarding.allReady && !running) {
            OnboardingChecklistCard(
                state = onboarding,
                onOpenOverlaySettings = { context.openOverlaySettings() },
                onRecheckOverlay = { overlayGranted = Settings.canDrawOverlays(context) },
                onRequestNotification = { context.requestNotificationPermissionIfNeeded(notifLauncher) },
                onAckBattleNames = { battleAck = true },
                onStart = { startCapture() },
            )
        }

        // ── 미리보기(데모) 섹션 ──
        PreviewCard(
            running = running,
            enabled = overlayGranted,
            onDemo = { startDemo() },
        )

        // ── 비공식 고지(홈 하단, 축약형) ──
        UnofficialNoticeShort(onOpenSettings = onOpenSettings)
    }
}

@Composable
private fun BrandHeader(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(modifier = Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = stringResource(R.string.settings_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 브랜드 마크(P33) — 런처 아이콘 모티브(어두운 카드 + 이름줄 + 타입칩 2개)를 Compose 로 소형 렌더.
 * 아이콘 리소스(adaptive-icon XML)는 painterResource 로 못 읽으므로 직접 그린다(회귀 유발 없음).
 */
@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    val navyTop = com.pochamps.supporter.ui.theme.NavyRaised
    val navyBottom = com.pochamps.supporter.ui.theme.NavyBase
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(listOf(navyTop, navyBottom))
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 이름줄(액센트 블루)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(com.pochamps.supporter.ui.theme.BrandBlue),
            )
            // 타입칩 2개(파랑/그린)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .width(13.dp).height(7.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(com.pochamps.supporter.ui.theme.BrandBlueDeep),
                )
                Box(
                    modifier = Modifier
                        .width(13.dp).height(7.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(com.pochamps.supporter.ui.theme.BrandGreen),
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    running: Boolean,
    canStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val statusColor = if (running) com.pochamps.supporter.ui.theme.StatusRunningGreen
    else com.pochamps.supporter.ui.theme.StatusIdleGray
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (running) R.string.home_status_running else R.string.home_status_idle
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    if (running) R.string.home_status_running_body else R.string.home_status_idle_body
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            // 주 CTA — 화면에서 가장 두드러지게(큰 채움 버튼).
            if (!running) {
                Button(
                    onClick = onStart,
                    enabled = canStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.home_cta_start),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (!canStart) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.home_cta_start_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            } else {
                Button(
                    onClick = onStop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = cs.errorContainer,
                        contentColor = cs.onErrorContainer,
                    ),
                ) {
                    // 중지 아이콘(작은 채움 사각형 — material-icons-core 에 Stop 이 없어 직접 그림).
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(cs.onErrorContainer),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.home_cta_stop),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingChecklistCard(
    state: OnboardingState,
    onOpenOverlaySettings: () -> Unit,
    onRecheckOverlay: () -> Unit,
    onRequestNotification: () -> Unit,
    onAckBattleNames: () -> Unit,
    onStart: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.home_setup_title),
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.home_setup_desc),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            // ① 오버레이 권한
            ChecklistStep(
                index = 1,
                title = stringResource(R.string.step_overlay_title),
                body = stringResource(R.string.step_overlay_body),
                done = state.stepState(OnboardingStep.OVERLAY).done,
                active = state.stepState(OnboardingStep.OVERLAY).active,
            ) {
                OutlinedButton(onClick = onOpenOverlaySettings) {
                    Text(stringResource(R.string.step_overlay_open))
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onRecheckOverlay) {
                    Text(stringResource(R.string.step_overlay_recheck))
                }
            }

            // ② 알림 권한
            ChecklistStep(
                index = 2,
                title = stringResource(R.string.step_notif_title),
                body = stringResource(R.string.step_notif_body),
                done = state.stepState(OnboardingStep.NOTIFICATION).done,
                active = state.stepState(OnboardingStep.NOTIFICATION).active,
            ) {
                OutlinedButton(onClick = onRequestNotification) {
                    Text(stringResource(R.string.step_notif_grant))
                }
            }

            // ③ 게임 배틀명 표시 안내
            ChecklistStep(
                index = 3,
                title = stringResource(R.string.step_battle_title),
                body = stringResource(R.string.step_battle_body),
                done = state.stepState(OnboardingStep.BATTLE_NAMES).done,
                active = state.stepState(OnboardingStep.BATTLE_NAMES).active,
            ) {
                FilledTonalButton(onClick = onAckBattleNames) {
                    Text(stringResource(R.string.step_battle_ack))
                }
            }

            // ④ 시작
            ChecklistStep(
                index = 4,
                title = stringResource(R.string.step_start_title),
                body = stringResource(R.string.step_start_body),
                done = false,
                active = state.stepState(OnboardingStep.START).active,
                isLast = true,
            ) {
                Button(
                    onClick = onStart,
                    enabled = state.stepState(OnboardingStep.START).active,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.home_cta_start))
                }
            }
        }
    }
}

/**
 * 단계 한 줄: 좌측 상태 아이콘(완료 체크 / 미완료 원) + 번호·제목·설명 + (활성 시)액션.
 * 완료 단계는 액션을 숨기고 체크만 남긴다. 미완료·비활성 단계는 흐리게(순차 안내).
 */
@Composable
private fun ChecklistStep(
    index: Int,
    title: String,
    body: String,
    done: Boolean,
    active: Boolean,
    isLast: Boolean = false,
    action: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val dim = !done && !active
    Row(modifier = Modifier.fillMaxWidth()) {
        // 상태 아이콘: 완료=채움 체크, 미완료=빈 원(테두리). Box 로 그려 아이콘 의존 최소화.
        val ringColor = if (active) cs.primary else cs.outline
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = com.pochamps.supporter.ui.theme.StatusRunningGreen,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .border(2.dp, ringColor, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "$index. $title",
                style = MaterialTheme.typography.titleSmall,
                color = if (dim) cs.onSurfaceVariant else cs.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            // 활성 단계만 액션 노출(순차 안내 — 앞 단계 끝나야 활성).
            if (active) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { action() }
            }
            if (!isLast) Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun PreviewCard(running: Boolean, enabled: Boolean, onDemo: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.home_preview_title),
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    if (running) R.string.home_preview_running_desc else R.string.home_preview_desc
                ),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onDemo,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (running) R.string.home_preview_next else R.string.home_preview_button
                    )
                )
            }
            if (!enabled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.step_overlay_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UnofficialNoticeShort(onOpenSettings: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.unofficial_notice),
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.home_open_settings))
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  설정 화면
// ══════════════════════════════════════════════════════════════════════════

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scrollTopInset = WindowInsets.statusBars.asPaddingValues()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 20.dp, end = 20.dp,
                top = 8.dp + scrollTopInset.calculateTopPadding(),
                bottom = 32.dp,
            ),
    ) {
        // 상단 바
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.settings_back),
                    tint = cs.onBackground,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                color = cs.onBackground,
            )
        }
        Spacer(Modifier.height(8.dp))

        // [표시] 언어 · 카드 크기
        SettingsGroup(title = stringResource(R.string.settings_group_display)) {
            DisplayLangSetting()
            GroupDivider()
            OverlayScaleSelector()
        }
        Spacer(Modifier.height(16.dp))

        // [인식] 배틀 형식 · ROI 보정 · 리셋
        SettingsGroup(title = stringResource(R.string.settings_group_recognition)) {
            BattleFormatSelector()
            GroupDivider()
            RoiSection()
        }
        Spacer(Modifier.height(16.dp))

        // [데이터] 업데이트 · 버전
        SettingsGroup(title = stringResource(R.string.settings_group_data)) {
            DataUpdateSection()
        }
        Spacer(Modifier.height(16.dp))

        // [고급] 진단 모드
        SettingsGroup(title = stringResource(R.string.settings_group_advanced)) {
            DiagnosticsToggle()
        }
        Spacer(Modifier.height(16.dp))

        // [정보] 앱 버전 · 비공식 고지 · (P34 라이선스/정책 자리)
        SettingsGroup(title = stringResource(R.string.settings_group_about)) {
            AboutSection()
        }
    }
}

/** 설정 그룹 카드: 제목 + 내용 컨테이너(그룹 시각 분리). */
@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = cs.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun GroupDivider() {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(16.dp))
}

/** [표시] 앱 표시 언어(P19) — 변경 시 액티비티 재생성으로 새 로케일 즉시 반영. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DisplayLangSetting() {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    var displayLang by remember { mutableStateOf(settings.displayLang) }

    Text(stringResource(R.string.settings_display_lang_title), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.settings_display_lang_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppSettings.LANGUAGE_LABELS.forEach { (code, label) ->
            FilterChip(
                selected = displayLang == code,
                onClick = {
                    if (code != displayLang) {
                        settings.displayLang = code
                        displayLang = code
                        (context as? android.app.Activity)?.recreate()
                    }
                },
                label = { Text(label) },
            )
        }
    }
}

/** [인식] ROI 보정 진입 + 현재 형식 ROI 리셋(P14/P20). */
@Composable
private fun RoiSection() {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    var roiResetDone by remember { mutableStateOf(false) }

    Text(stringResource(R.string.settings_roi_title), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.settings_roi_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Button(
        enabled = Settings.canDrawOverlays(context),
        onClick = {
            ContextCompat.startForegroundService(
                context,
                com.pochamps.supporter.capture.CaptureService.calibrateIntent(context),
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.settings_roi_calibrate))
    }
    if (!Settings.canDrawOverlays(context)) {
        Text(
            stringResource(R.string.settings_roi_calibrate_need_overlay),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = {
        PrefsRoiConfigStore(context).clear(settings.battleFormat)
        roiResetDone = true
    }) {
        Text(
            if (roiResetDone) stringResource(R.string.settings_roi_reset_done)
            else stringResource(R.string.settings_roi_reset)
        )
    }
}

/**
 * [표시] 오버레이 카드 크기(P16). 스케일 단계(80/100/125/150%) 칩. 저장 즉시 실행 중 오버레이에 반영.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun OverlayScaleSelector() {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    var scale by remember { mutableStateOf(settings.overlayScale) }

    Text(stringResource(R.string.settings_scale_title), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.settings_scale_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.pochamps.supporter.overlay.OverlayScale.STEPS.forEach { step ->
            FilterChip(
                selected = kotlin.math.abs(scale - step) < 0.01f,
                onClick = {
                    settings.overlayScale = step
                    scale = step
                    context.startService(
                        com.pochamps.supporter.capture.CaptureService.applyScaleIntent(context),
                    )
                },
                label = { Text(com.pochamps.supporter.overlay.OverlayScale.label(step)) },
            )
        }
    }
}

/**
 * [인식] 배틀 형식 선택(P20, 싱글/더블). 인식 영역/사용률 메타가 함께 바뀐다.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BattleFormatSelector() {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    var format by remember { mutableStateOf(settings.battleFormat) }

    Text(stringResource(R.string.settings_format_title), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.settings_format_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = format == com.pochamps.supporter.data.BattleFormat.SINGLES,
            onClick = {
                settings.battleFormat = com.pochamps.supporter.data.BattleFormat.SINGLES
                format = com.pochamps.supporter.data.BattleFormat.SINGLES
            },
            label = { Text(stringResource(R.string.format_single)) },
        )
        FilterChip(
            selected = format == com.pochamps.supporter.data.BattleFormat.DOUBLES,
            onClick = {
                settings.battleFormat = com.pochamps.supporter.data.BattleFormat.DOUBLES
                format = com.pochamps.supporter.data.BattleFormat.DOUBLES
            },
            label = { Text(stringResource(R.string.format_doubles)) },
        )
    }
}

/** [고급] 진단 모드 토글(P14). 다음 실행 세션부터 반영. */
@Composable
private fun DiagnosticsToggle() {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    var enabled by remember { mutableStateOf(settings.diagnosticsEnabled) }

    Text(stringResource(R.string.settings_diag_title), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.settings_diag_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (enabled) stringResource(R.string.settings_diag_on)
            else stringResource(R.string.settings_diag_off),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = enabled,
            onCheckedChange = {
                settings.diagnosticsEnabled = it
                enabled = it
            },
        )
    }
}

/** [데이터] 포켓몬 데이터 원격 갱신(P13) + 현재 버전 표시. */
@Composable
private fun DataUpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { DbUpdateManager(context.applicationContext) }
    var version by remember { mutableStateOf(manager.currentVersion()) }
    var checking by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    val versionLabel = version?.let { stringResource(R.string.settings_data_version_downloaded, it) }
        ?: stringResource(R.string.settings_data_version_bundled)

    val strChecking = stringResource(R.string.settings_data_checking)
    val strUpdate = stringResource(R.string.settings_data_update_button)
    val sDisabled = stringResource(R.string.settings_data_disabled)
    val sFailNet = stringResource(R.string.settings_data_fail_network)
    val sFailManifest = stringResource(R.string.settings_data_fail_manifest)
    val sFailSchema = stringResource(R.string.settings_data_fail_schema)
    val sFailCorrupt = stringResource(R.string.settings_data_fail_corrupt)

    Text(stringResource(R.string.settings_data_title), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.settings_data_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        versionLabel,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(8.dp))
    Button(
        enabled = !checking,
        onClick = {
            checking = true
            statusMsg = null
            scope.launch {
                val result = manager.checkAndUpdate()
                statusMsg = when (result) {
                    is DbUpdateManager.Result.Disabled -> sDisabled
                    is DbUpdateManager.Result.UpToDate ->
                        context.getString(R.string.settings_data_uptodate, result.version)
                    is DbUpdateManager.Result.Updated -> {
                        version = result.to
                        context.getString(R.string.settings_data_updated, result.to)
                    }
                    is DbUpdateManager.Result.Failed -> when (result.reason) {
                        DbUpdateManager.REASON_NETWORK -> sFailNet
                        DbUpdateManager.REASON_MANIFEST -> sFailManifest
                        DbUpdateManager.REASON_SCHEMA -> sFailSchema
                        else -> sFailCorrupt
                    }
                }
                checking = false
            }
        },
    ) {
        Text(if (checking) strChecking else strUpdate)
    }
    statusMsg?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** [정보] 앱 버전 · 비공식 고지 · (P34에서 라이선스/정책 링크가 들어갈 자리). */
@Composable
private fun AboutSection() {
    val cs = MaterialTheme.colorScheme
    Text(stringResource(R.string.settings_about_version_title), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(
            R.string.settings_about_version_value,
            com.pochamps.supporter.BuildConfig.APP_VERSION_NAME,
            com.pochamps.supporter.BuildConfig.APP_VERSION_CODE,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = cs.onSurface,
    )
    Spacer(Modifier.height(14.dp))

    // 비공식 팬메이드 고지(research/ip_risk.md — 상표 지시적 사용, 무관 명시).
    Text(stringResource(R.string.settings_about_unofficial_title), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.unofficial_notice_full),
        style = MaterialTheme.typography.bodySmall,
        color = cs.onSurfaceVariant,
    )
    Spacer(Modifier.height(14.dp))

    // P34 자리표시: 개인정보처리방침 / 오픈소스 라이선스(현재 준비 중 안내).
    Text(stringResource(R.string.settings_about_legal_title), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.settings_about_legal_placeholder),
        style = MaterialTheme.typography.bodySmall,
        color = cs.onSurfaceVariant,
    )
}

// ── 권한 헬퍼 ──────────────────────────────────────────────────────────────

/** Android 13+ 에서 POST_NOTIFICATIONS 런타임 권한이 필요한지. */
private fun Context.notificationPermissionRequired(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** 알림 권한 허용 여부(Android 12 이하는 항상 true — 런타임 권한 없음). */
private fun Context.hasNotificationPermission(): Boolean {
    if (!notificationPermissionRequired()) return true
    return ContextCompat.checkSelfPermission(
        this, android.Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}

/** 없으면 런타임 요청(Android 13+). 이전 버전은 no-op. */
private fun Context.requestNotificationPermissionIfNeeded(
    launcher: androidx.activity.result.ActivityResultLauncher<String>,
) {
    if (!notificationPermissionRequired()) return
    if (!hasNotificationPermission()) launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
}

/** '다른 앱 위에 표시' 시스템 설정 화면으로 이동. */
private fun Context.openOverlaySettings() {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:$packageName"),
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    startActivity(intent)
}
