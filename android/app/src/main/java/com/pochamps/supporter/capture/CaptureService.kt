package com.pochamps.supporter.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import com.pochamps.supporter.data.AppSettings
import com.pochamps.supporter.data.AssetsPokedexLoader
import com.pochamps.supporter.data.BattleFormat
import com.pochamps.supporter.data.PokedexRepository
import com.pochamps.supporter.ocr.OcrEngine
import com.pochamps.supporter.overlay.CandidateChoice
import com.pochamps.supporter.overlay.OverlayCardData
import com.pochamps.supporter.overlay.OverlayRenderer
import com.pochamps.supporter.overlay.PrefsOverlayPositionStore
import com.pochamps.supporter.overlay.RoiCalibrationOverlay
import com.pochamps.supporter.overlay.SearchChoice
import com.pochamps.supporter.overlay.SlotUiMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.concurrent.thread

/**
 * MediaProjection 캡처용 포그라운드 서비스([1] CaptureManager 역할 겸함).
 *
 * ## Android 최신 규정(DESIGN.md 2장) 준수
 *  - foregroundServiceType="mediaProjection" (AndroidManifest 선언, Android 14+ 필수).
 *  - Android 15: SYSTEM_ALERT_WINDOW 앱이 이 FGS 를 시작하려면 **보이는 오버레이가 먼저** 떠 있어야 함
 *    → onStartCommand 안에서 오버레이를 먼저 show() 한 뒤 startForeground() 를 호출한다.
 *  - MediaProjection Intent 는 1회성(캐싱 불가). 앱 재시작 시 재동의.
 *  - 세션 유지: null-surface VirtualDisplay 로 프로젝션을 살려 둔다. 실제 프레임 처리(ImageReader)는 P3.
 *  - onStop 콜백: 화면 잠금/사용자 중단 시 프로젝션이 죽으므로 콜백에서 서비스도 정리.
 *
 * ## P3: 실프레임 파이프라인
 *  MediaProjection 세션 확보 후 CaptureManager(ImageReader setSurface)로 프레임을 받아
 *  RecognitionPipeline([2]~[7])으로 오버레이 카드를 실시간 갱신한다.
 *
 * ## 데모(검증용, P3 에서도 유지)
 *  ACTION_DEMO 로 시작하면 MediaProjection 없이 garchomp(한카리아스) 카드를 실데이터로 띄운다(실기기 UI 검증용).
 */
class CaptureService : Service() {

    private var overlay: OverlayRenderer? = null
    private var calibrationOverlay: RoiCalibrationOverlay? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var repository: PokedexRepository? = null

    // P3 파이프라인 구성요소.
    private var captureManager: CaptureManager? = null
    private var pipeline: RecognitionPipeline? = null
    private var ocrEngine: OcrEngine? = null
    private val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 게임/표시 언어(설정에서 로드, 기본 "ko"). onStartCommand 진입 시 갱신. */
    private var captureLang: String = AppSettings.DEFAULT_LANG

    /**
     * 프로젝션이 죽을 때(화면 잠금/사용자 중단) 호출. (Android 15 QPR1+/16 규정)
     *
     * 즉시 stopSelf 하는 대신, 파이프라인/세션만 정리하고 오버레이에는
     * "캡처 중단됨 + 재시작" 상태 카드를 남긴다(DESIGN.md 5장). 재시작 탭 → MainActivity 재실행.
     */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection onStop — 세션 종료, 중단 카드 표시")
            mainHandler.post { handleCaptureStopped() }
        }
    }

    /** 미인식 안내 배너용: 마지막으로 카드를 갱신한 시각(uptimeMs). 0=아직 없음. */
    @Volatile private var lastRecognitionAt: Long = 0L

    /** 파이프라인 시작 시각(uptimeMs) — 미인식 경과 판정 기준. */
    @Volatile private var pipelineStartedAt: Long = 0L

    /** 미인식 안내 배너를 이미 띄웠는지(세션당 1회). */
    private var battleNamesHintPosted = false

    /** 데모 버튼 연타 시 순환할 데모 대상 인덱스(0..DEMO_CYCLE.size-1). */
    private var demoIndex = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CALIBRATE -> {
                // ROI 보정 오버레이(P14). MediaProjection 불필요 — SYSTEM_ALERT_WINDOW 만.
                // 캡처 세션이 이미 떠 있으면 그 위에, 아니면 보정 전용으로 FGS+오버레이만 유지.
                if (projection == null && captureManager == null) {
                    startForegroundWithNotification(isDemo = true) // specialUse(토큰 없음) 타입으로 유지.
                }
                showCalibrationOverlay()
                return START_NOT_STICKY
            }
        }
        val isDemo = intent?.action == ACTION_DEMO

        // 설정에서 언어 로드(오버레이/파이프라인 조립 전에 확정).
        captureLang = AppSettings(this).language

        // 1) 오버레이를 **먼저** 띄운다(Android 15 순서 규정).
        ensureOverlayShown()

        // 2) FGS 시작(알림 채널 + 상시 알림).
        //    데모 경로는 MediaProjection 토큰이 없으므로 mediaProjection 타입으로 시작하면
        //    Android 14+ 에서 SecurityException(크래시) → specialUse 타입으로 시작한다.
        startForegroundWithNotification(isDemo = isDemo)

        if (isDemo) {
            // 데모 경로: MediaProjection 없이 garchomp 카드만 띄움(실기기 UI 검증용).
            showDemoCard()
            return START_NOT_STICKY
        }

        // 3) MediaProjection 동의 결과가 함께 오면 프로젝션 세션을 확보하고 파이프라인을 시작한다.
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else
                @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode != Int.MIN_VALUE && resultData != null) {
            acquireProjection(resultCode, resultData)
        }

        return START_NOT_STICKY
    }

    // --- 오버레이 ---

    private fun ensureOverlayShown() {
        if (overlay != null) return
        val renderer = OverlayRenderer(
            context = this,
            positionStore = PrefsOverlayPositionStore(this),
            // 후보 선택 확정 → 파이프라인이 기억 + 카드 갱신.
            onChooseCandidate = { slot, root, key ->
                pipeline?.chooseCandidate(slot, root, key)
            },
            // 수동 검색 선택 → 슬롯 핀(파이프라인이 덮어쓰지 않게).
            onPinSlot = { slot, key -> pipeline?.pinSlot(slot, key) },
            onUnpinSlot = { slot -> pipeline?.unpinSlot(slot) },
            // 후보 리스트(root → 타입칩+사용률). 사용률 최상위=추천.
            candidateProvider = { root -> candidatesForRoot(root) },
            // 이름 부분일치 검색.
            searchProvider = { q -> searchHits(q) },
            // 캡처 중단 카드의 "재시작" → 앱(MainActivity) 재실행 후 현재 서비스 정리.
            onRestart = { restartCapture() },
        )
        renderer.show()
        // 진단 모드(P14) 설정 반영 — 켜져 있으면 진단 스트립 표시.
        renderer.setDiagnosticsEnabled(AppSettings(this).diagnosticsEnabled)
        overlay = renderer
    }

    /** 후보 선택 시트용: root 의 후보를 타입칩+사용률과 함께. 사용률 최상위=추천. */
    private fun candidatesForRoot(root: String): List<CandidateChoice> {
        val repo = repository ?: return emptyList()
        val cands = repo.candidatesOfRoot(root)
        val topRank = cands.maxOfOrNull { it.usage_rank ?: -1.0 } ?: -1.0
        return cands.map { c ->
            val chips = c.types.map { slug ->
                OverlayCardData.TypeChip(
                    label = repo.typeName(slug, captureLang) ?: slug,
                    colorHex = repo.typeColor(slug),
                )
            }
            CandidateChoice(
                key = c.key,
                name = c.names.get(captureLang) ?: c.key,
                typeChips = chips,
                usagePct = c.usage_rank,
                recommended = (c.usage_rank ?: -1.0) == topRank && topRank >= 0.0,
            )
        }
    }

    /** 수동 검색 시트용: 이름 부분일치. */
    private fun searchHits(query: String): List<SearchChoice> {
        val repo = repository ?: return emptyList()
        return repo.searchByName(query, captureLang, limit = 20)
            .map { SearchChoice(it.key, it.name) }
    }

    /**
     * 데모용: 실데이터 카드를 오버레이에 표시(MediaProjection 없이 UI 검증).
     * 데모 버튼을 연타하면 [DEMO_CYCLE] 을 순환한다:
     *  - garchomp(한카리아스): 메가 토글 검증(can_mega).
     *  - arcanine(윈디): 후보 시트 검증(root="arcanine" → 윈디/히스이윈디 2후보).
     * Repository I/O 는 백그라운드에서.
     */
    private fun showDemoCard() {
        val target = DEMO_CYCLE[demoIndex % DEMO_CYCLE.size]
        demoIndex++
        thread(name = "pochamps-demo-load") {
            val repo = repository ?: runCatching { AssetsPokedexLoader.load(this) }
                .onFailure { Log.e(TAG, "Repository 로드 실패", it) }
                .getOrNull() ?: return@thread
            repository = repo
            val card = OverlayCardData.fromRepository(
                repo = repo,
                key = target.key,
                lang = captureLang,
                format = BattleFormat.DOUBLES,
            ) ?: return@thread
            mainHandler.post {
                // 후보가 있는 데모면 root/hasMoreCandidates 메타를 실어 "바꾸기" 진입점을 노출.
                overlay?.updateSlot(
                    0, card,
                    SlotUiMeta(
                        root = target.root,
                        hasMoreCandidates = target.root != null &&
                            candidatesForRoot(target.root).size > 1,
                        pinned = false,
                    ),
                )
            }
        }
    }

    /**
     * ROI 보정 오버레이 표시(P14). 저장된 오버라이드(없으면 기본값)로 편집을 시작하고,
     * 저장 시 [PrefsRoiConfigStore] 에 넣는다 → 파이프라인이 다음 프레임부터 새 ROI 사용.
     */
    private fun showCalibrationOverlay() {
        if (calibrationOverlay != null) return
        val store = PrefsRoiConfigStore(this)
        val calib = RoiCalibrationOverlay(
            context = this,
            initial = store.effective(),
            onSave = { cfg -> store.save(cfg) },
            onClose = {
                calibrationOverlay?.dismiss()
                calibrationOverlay = null
                // 보정 전용으로 떠 있었고(캡처 세션·카드 오버레이 모두 없음) 서비스 종료.
                if (projection == null && captureManager == null && overlay == null) stopSelf()
            },
        )
        calib.show()
        calibrationOverlay = calib
    }

    // --- MediaProjection 세션 ---

    /**
     * 동의 result 로 MediaProjection 을 획득하고, null-surface VirtualDisplay 로 세션을 유지한다.
     * 실제 프레임 처리(ImageReader 연결)는 P3. 여기선 세션 확보/콜백 등록까지만.
     */
    private fun acquireProjection(resultCode: Int, resultData: Intent) {
        if (projection != null) return
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, resultData) ?: run {
            Log.e(TAG, "getMediaProjection 실패(null)")
            return
        }
        // 콜백은 반드시 VirtualDisplay 생성 전에 등록해야 한다(Android 14+ 요구).
        mp.registerCallback(projectionCallback, mainHandler)
        projection = mp

        val metrics: DisplayMetrics = resources.displayMetrics
        // null surface 로 VirtualDisplay 를 만들어 세션만 살려 둔다(오버헤드 0).
        // P3 에서 ImageReader.surface 로 setSurface() 하여 프레임을 받는다.
        val vd = mp.createVirtualDisplay(
            "pochamps-capture",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            /* surface = */ null,
            /* callback = */ null,
            mainHandler,
        )
        virtualDisplay = vd
        Log.i(TAG, "MediaProjection 세션 확보(null-surface VirtualDisplay 유지)")

        // P3: 파이프라인 시작(Repository 로드는 백그라운드, 이후 CaptureManager 로 프레임 수신).
        startPipeline(vd, metrics)
    }

    /**
     * P3 실프레임 파이프라인 기동.
     * Repository 로드(I/O) 후 CaptureManager 로 ImageReader 를 VirtualDisplay 에 붙이고,
     * RecognitionPipeline 워커를 시작한다. 캡처 콜백은 파이프라인에 프레임만 밀어넣는다(경량).
     */
    private fun startPipeline(vd: VirtualDisplay, metrics: DisplayMetrics) {
        thread(name = "pochamps-pipeline-init") {
            val repo = repository ?: runCatching { AssetsPokedexLoader.load(this) }
                .onFailure { Log.e(TAG, "Repository 로드 실패", it) }
                .getOrNull() ?: return@thread
            repository = repo

            val ocr = OcrEngine(language = captureLang).also { ocrEngine = it }
            // ROI 는 프레임마다 store 를 다시 읽는다(인앱 보정 저장 즉시 반영, P14).
            val roiStore = PrefsRoiConfigStore(this)

            val pipe = RecognitionPipeline(
                scope = pipelineScope,
                repository = repo,
                ocr = ocr,
                roiConfigProvider = { roiStore.effective() },
                lang = captureLang,
                format = BattleFormat.DOUBLES,
                onCardUpdate = { slot, card, meta ->
                    // 인식 성공 시각 기록(미인식 안내 배너 판정용).
                    lastRecognitionAt = android.os.SystemClock.uptimeMillis()
                    // 오버레이 갱신은 반드시 메인 스레드에서. 파이프라인 SlotMeta → UI SlotUiMeta.
                    mainHandler.post {
                        overlay?.updateSlot(
                            slot, card,
                            SlotUiMeta(
                                root = meta.root,
                                hasMoreCandidates = meta.hasMoreCandidates,
                                pinned = meta.pinned,
                            ),
                        )
                    }
                },
                // 진단 스냅샷(P14) → 오버레이 진단 스트립(메인 스레드). 진단 모드 off 여도 저장만.
                onDiag = { state ->
                    mainHandler.post { overlay?.updateDiag(state) }
                },
            )
            pipe.start()
            pipeline = pipe

            // 캡처 콜백 전용 핸들러(메인 루퍼 사용 — 콜백은 가볍고 즉시 채널로 넘김).
            val manager = CaptureManager(
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
                densityDpi = metrics.densityDpi,
                callbackHandler = mainHandler,
            ).also { captureManager = it }

            manager.start(vd) { bitmap, ts -> pipe.submitFrame(bitmap, ts) }
            pipelineStartedAt = android.os.SystemClock.uptimeMillis()
            mainHandler.post { scheduleBattleNamesWatchdog() }
            Log.i(TAG, "파이프라인 시작 완료")
        }
    }

    /**
     * 미인식 안내 배너 워치독(DESIGN.md 5장 이름 미표시).
     * 파이프라인 시작 후 [NO_MATCH_HINT_MS] 동안 단 한 번도 인식 성공이 없으면
     * "배틀명 표시 ON" 배너를 1회 띄운다. 인식이 있으면 자동 취소.
     */
    private fun scheduleBattleNamesWatchdog() {
        if (battleNamesHintPosted) return
        mainHandler.postDelayed({
            if (battleNamesHintPosted) return@postDelayed
            // 시작 후 아직 한 번도 인식 성공이 없으면 배너 노출.
            if (lastRecognitionAt < pipelineStartedAt) {
                battleNamesHintPosted = true
                overlay?.showBattleNamesHintOnce()
            }
        }, NO_MATCH_HINT_MS)
    }

    // --- 알림/FGS ---

    /**
     * FGS 시작. [isDemo] 이면 MediaProjection 토큰이 없으므로 mediaProjection 타입을 쓸 수 없다
     * (Android 14+ SecurityException). 데모는 specialUse 타입으로 시작한다.
     * 실캡처 경로는 mediaProjection 타입(동의 후 토큰 확보 예정)으로 시작한다.
     */
    private fun startForegroundWithNotification(isDemo: Boolean) {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 데모(토큰 없음)는 specialUse 타입으로 시작하고 싶지만, specialUse FGS 타입은 API 34+ 에서만
            // 유효하다(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE = API 34). API 29~33 에서 이 값을
            // 넘기면 매니페스트에 선언되지 않은 타입이라 IllegalArgumentException 으로 크래시한다.
            //   → API 34+ 만 specialUse, 그 미만 데모는 mediaProjection 타입으로 시작한다.
            //     (mediaProjection 타입의 "실 프로젝션 토큰 요구"는 API 34 에서 추가된 제약이라
            //      API 29~33 에선 토큰 없이도 mediaProjection 타입 FGS 시작이 허용된다.)
            val type = when {
                !isDemo -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(com.pochamps.supporter.R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW, // 소리/헤드업 없이 상태바 칩만.
        ).apply {
            description = getString(com.pochamps.supporter.R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle(getString(com.pochamps.supporter.R.string.notif_title))
            .setContentText(getString(com.pochamps.supporter.R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    /**
     * 캡처 중단 처리(onStop 콜백에서 메인 스레드로 호출).
     * 파이프라인/세션은 정리하되, 오버레이는 살려 "캡처 중단됨" 상태 카드를 남긴다.
     * FGS 는 계속 유지(오버레이 표시용) — 유저가 재시작하거나 앱에서 중지할 때까지.
     */
    private fun handleCaptureStopped() {
        // 프레임/파이프라인/세션 정리(오버레이는 유지).
        runCatching { captureManager?.stop() }
        captureManager = null
        runCatching { pipeline?.stop() }
        pipeline = null
        runCatching { ocrEngine?.close() }
        ocrEngine = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        projection?.let {
            runCatching { it.unregisterCallback(projectionCallback) }
            runCatching { it.stop() }
        }
        projection = null
        overlay?.showCaptureStopped()
    }

    /** 중단 카드의 "재시작" → MainActivity 를 다시 띄워 재동의를 받고, 현재 서비스는 정리. */
    private fun restartCapture() {
        val intent = Intent(this, Class.forName("com.pochamps.supporter.ui.MainActivity")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        runCatching { startActivity(intent) }
        stopSelf()
    }

    override fun onDestroy() {
        // P3 파이프라인 정리(캡처 중지 → surface detach → 워커 취소 → OCR 자원 해제).
        runCatching { captureManager?.stop() }
        captureManager = null
        runCatching { pipeline?.stop() }
        pipeline = null
        runCatching { ocrEngine?.close() }
        ocrEngine = null
        runCatching { pipelineScope.cancel() }

        // 세션/창 정리(누수 방지).
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        projection?.let {
            runCatching { it.unregisterCallback(projectionCallback) }
            runCatching { it.stop() }
        }
        projection = null
        overlay?.destroy()
        overlay = null
        calibrationOverlay?.dismiss()
        calibrationOverlay = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "capture_channel"
        private const val NOTIF_ID = 1001

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val ACTION_STOP = "com.pochamps.supporter.action.STOP"
        const val ACTION_DEMO = "com.pochamps.supporter.action.DEMO"
        const val ACTION_CALIBRATE = "com.pochamps.supporter.action.CALIBRATE"

        /** 데모 순환 대상 한 종(표시 key + 후보 시트용 root). root=null 이면 단일 후보. */
        private data class DemoTarget(val key: String, val root: String?)

        /**
         * 데모 버튼 연타 시 순환할 대상(더블 포맷). 언어는 설정값(captureLang).
         *  - garchomp(한카리아스): 메가 토글 검증.
         *  - arcanine(윈디): 후보 시트 검증(root "arcanine" → 윈디/히스이윈디).
         */
        private val DEMO_CYCLE = listOf(
            DemoTarget(key = "garchomp", root = null),
            DemoTarget(key = "arcanine", root = "arcanine"),
        )

        /** 이 시간(ms) 동안 인식 성공이 한 번도 없으면 "배틀명 표시 ON" 배너 1회 노출. */
        private const val NO_MATCH_HINT_MS = 20_000L

        /** MediaProjection 동의 결과를 담아 서비스를 시작하는 Intent. */
        fun startIntent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, CaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }

        /** 데모 카드 표시용 Intent(MediaProjection 없이 오버레이 UI 검증). */
        fun demoIntent(context: Context): Intent =
            Intent(context, CaptureService::class.java).apply { action = ACTION_DEMO }

        /** ROI 보정 오버레이 표시 Intent(P14, MediaProjection 불필요). */
        fun calibrateIntent(context: Context): Intent =
            Intent(context, CaptureService::class.java).apply { action = ACTION_CALIBRATE }

        /** 서비스 중지 Intent. */
        fun stopIntent(context: Context): Intent =
            Intent(context, CaptureService::class.java).apply { action = ACTION_STOP }
    }
}
