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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pochamps.supporter.R
import com.pochamps.supporter.capture.PrefsRoiConfigStore
import com.pochamps.supporter.data.AppSettings
import com.pochamps.supporter.data.DbUpdateManager
import kotlinx.coroutines.launch

/**
 * 앱 진입점 · 권한 온보딩(DESIGN.md 6장 흐름).
 *
 * 흐름:
 *  (1) 앱 소개 + "화면에 보이는 정보만 표시" 고지
 *  (2) 오버레이 권한(SYSTEM_ALERT_WINDOW) 체크 + 설정 이동
 *  (3) 게임 내 "배틀명 표시 ON" 안내(K2 대응)
 *  (4) "시작" → 오버레이 표시 → FGS 시작 → MediaProjection 동의 다이얼로그
 *  (5) 실행 중 상태 + 중지 버튼
 *
 * Android 15 순서: 오버레이 먼저 → FGS 시작. 실제 오버레이 show()/FGS 시작은 CaptureService 가
 * onStartCommand 안에서 "오버레이 먼저 → startForeground" 순으로 처리한다(2장 규정).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OnboardingScreen()
                }
            }
        }
    }
}

@Composable
private fun OnboardingScreen() {
    val context = LocalContext.current

    // "다른 앱 위에 표시" 권한 상태. 설정에서 돌아올 때 재확인해야 하므로 함수로 조회.
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var running by remember { mutableStateOf(false) }

    // 캡처 중단 후 오버레이의 "재시작" 버튼은 이 액티비티를 다시 앞으로 가져오지만(REORDER_TO_FRONT),
    // onCreate 를 재실행하지 않으므로 Compose 의 `running` 이 true 로 남아 "시작" 버튼이 사라진 채
    // 재동의(createScreenCaptureIntent) 를 다시 받을 수 없는 막다른 상태가 된다.
    // → 액티비티가 다시 보일(ON_RESUME) 때 running 을 false 로 되돌려 "시작" 버튼을 복원한다.
    //   (재시작 경로는 항상 서비스를 stopSelf 한 뒤 액티비티를 띄우므로, 복귀 시점엔 캡처 세션이 없다.
    //    설령 서비스가 살아 있어도 "시작"을 다시 누르면 새 세션이 시작될 뿐 무해하다.)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
                running = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // POST_NOTIFICATIONS 런타임 권한(Android 13+). 없으면 "캡처 중" 알림이 안 뜬다(FGS 자체는 동작).
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 허용/거부 모두 진행 — 알림은 부가 기능. */ }

    // MediaProjection 동의 다이얼로그 결과 수신 런처.
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            // 동의 완료 → 결과를 담아 FGS 시작(서비스가 오버레이 먼저 → FGS → 세션 확보).
            val intent = com.pochamps.supporter.capture.CaptureService
                .startIntent(context, result.resultCode, result.data!!)
            ContextCompat.startForegroundService(context, intent)
            running = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // (1) 소개 + 고지
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall)
        InfoCard(
            title = stringResource(R.string.onboarding_intro_title),
            body = stringResource(R.string.onboarding_intro_body),
        )
        // 법적 고지(K4 EULA 조사): 캐주얼 전용·공인 대회 사용 실격 위험 경고.
        InfoCard(
            title = stringResource(R.string.onboarding_legal_title),
            body = stringResource(R.string.onboarding_legal_body),
        )

        // (2) 오버레이 권한
        Text(stringResource(R.string.onboarding_step_overlay), style = MaterialTheme.typography.titleMedium)
        Text(
            if (overlayGranted) stringResource(R.string.onboarding_overlay_granted)
            else stringResource(R.string.onboarding_overlay_needed),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!overlayGranted) {
            OutlinedButton(onClick = { context.openOverlaySettings() }) {
                Text(stringResource(R.string.onboarding_overlay_open))
            }
            OutlinedButton(onClick = { overlayGranted = Settings.canDrawOverlays(context) }) {
                Text(stringResource(R.string.onboarding_overlay_recheck))
            }
        }

        // (3) 게임 설정 안내
        Text(stringResource(R.string.onboarding_step_game), style = MaterialTheme.typography.titleMedium)
        InfoCard(
            title = stringResource(R.string.onboarding_game_title),
            body = stringResource(R.string.onboarding_game_body),
        )

        // (4)/(5) 시작 / 실행 중
        Text(stringResource(R.string.onboarding_step_run), style = MaterialTheme.typography.titleMedium)
        if (!running) {
            Button(
                onClick = {
                    // Android 13+ 알림 권한을 먼저 요청(상태바 '캡처 중' 칩 보장).
                    context.requestNotificationPermissionIfNeeded(notifLauncher)
                    // MediaProjection 동의 다이얼로그를 띄운다.
                    // (오버레이 표시 + FGS 시작은 동의 완료 후 서비스가 순서대로 처리.)
                    val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager
                    projectionLauncher.launch(mpm.createScreenCaptureIntent())
                },
                enabled = overlayGranted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_start))
            }
            if (!overlayGranted) {
                Text(
                    stringResource(R.string.onboarding_need_overlay_first),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // 데모 버튼: MediaProjection 없이 오버레이 카드만 띄워 UI 를 검증(실기기 확인용).
            OutlinedButton(
                onClick = {
                    context.requestNotificationPermissionIfNeeded(notifLauncher)
                    ContextCompat.startForegroundService(
                        context,
                        com.pochamps.supporter.capture.CaptureService.demoIntent(context),
                    )
                    running = true
                },
                enabled = overlayGranted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_demo))
            }
        } else {
            InfoCard(
                title = stringResource(R.string.onboarding_running_title),
                body = stringResource(R.string.onboarding_running_body),
            )
            // 데모 순환: 실행 중에도 "데모 카드"를 다시 눌러 데모 대상(한카리아스↔윈디 등)을 순환한다.
            // 서비스가 살아 있는 동안 demoIntent 를 반복 전송하면 CaptureService 가 다음 대상으로 넘어간다.
            OutlinedButton(
                onClick = {
                    ContextCompat.startForegroundService(
                        context,
                        com.pochamps.supporter.capture.CaptureService.demoIntent(context),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_demo))
            }
            Button(
                onClick = {
                    context.startService(
                        com.pochamps.supporter.capture.CaptureService.stopIntent(context)
                    )
                    running = false
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_stop))
            }
        }

        // (6) 설정: 게임 언어 선택 + ROI 기본값 리셋(고급).
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleMedium)
        SettingsSection()
    }
}

/**
 * 설정 섹션(DESIGN.md 5장): 게임 언어(9언어 → OcrEngine/표시 언어 연동) + ROI 기본값 리셋.
 * 언어 변경은 다음 "시작" 세션부터 적용된다(서비스가 onStartCommand 에서 로드).
 */
@OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)
@Composable
private fun SettingsSection() {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    var lang by remember { mutableStateOf(settings.language) }
    var roiResetDone by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_language_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_language_desc),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppSettings.LANGUAGE_LABELS.forEach { (code, label) ->
                    FilterChip(
                        selected = lang == code,
                        onClick = {
                            settings.language = code
                            lang = code
                        },
                        label = { Text(label) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.settings_roi_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_roi_desc),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            // P14: 인앱 ROI 보정 오버레이 진입. 오버레이 권한 필요(보정 창을 게임 위에 띄움).
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
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                PrefsRoiConfigStore(context).clear()
                roiResetDone = true
            }) {
                Text(
                    if (roiResetDone) stringResource(R.string.settings_roi_reset_done)
                    else stringResource(R.string.settings_roi_reset)
                )
            }

            Spacer(Modifier.height(16.dp))
            DiagnosticsToggle()

            Spacer(Modifier.height(16.dp))
            DataUpdateSection()
        }
    }
}

/**
 * 진단 모드 토글(P14 필드테스트 지원). 켜면 오버레이에 소형 진단 스트립이 표시된다
 * (마지막 OCR 원문·매칭·인식 시각·OCR 빈도). 다음 "시작" 세션부터 반영(서비스가 로드).
 */
@Composable
private fun DiagnosticsToggle() {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    var enabled by remember { mutableStateOf(settings.diagnosticsEnabled) }

    Text(stringResource(R.string.settings_diag_title), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.settings_diag_desc), style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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

/**
 * 포켓몬 데이터 원격 갱신 섹션(P13, DESIGN.md 4-6).
 * 수동 "데이터 업데이트" 버튼 + 현재 버전(내장/다운로드) 표시. v0.1 은 수동 버튼만(자동 체크 없음).
 */
@Composable
private fun DataUpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { DbUpdateManager(context.applicationContext) }
    var version by remember { mutableStateOf(manager.currentVersion()) }
    var checking by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    // 문구는 순수 데이터(Result) → 리소스 매핑. Composable 밖 IO 는 코루틴에서.
    val versionLabel = version?.let { stringResource(R.string.settings_data_version_downloaded, it) }
        ?: stringResource(R.string.settings_data_version_bundled)

    val strChecking = stringResource(R.string.settings_data_checking)
    val strUpdate = stringResource(R.string.settings_data_update_button)
    // 결과별 문구를 미리 확보(stringResource 는 Composable 안에서만 호출 가능).
    val sDisabled = stringResource(R.string.settings_data_disabled)
    val sFailNet = stringResource(R.string.settings_data_fail_network)
    val sFailManifest = stringResource(R.string.settings_data_fail_manifest)
    val sFailSchema = stringResource(R.string.settings_data_fail_schema)
    val sFailCorrupt = stringResource(R.string.settings_data_fail_corrupt)

    Text(stringResource(R.string.settings_data_title), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.settings_data_desc), style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(6.dp))
    Text(versionLabel, style = MaterialTheme.typography.bodySmall)
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
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
}

/** Android 13+ 에서 POST_NOTIFICATIONS 가 없으면 런타임 요청. 이전 버전은 no-op. */
private fun Context.requestNotificationPermissionIfNeeded(
    launcher: androidx.activity.result.ActivityResultLauncher<String>,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val granted = ContextCompat.checkSelfPermission(
        this, android.Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** '다른 앱 위에 표시' 시스템 설정 화면으로 이동. */
private fun Context.openOverlaySettings() {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:$packageName"),
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    startActivity(intent)
}
