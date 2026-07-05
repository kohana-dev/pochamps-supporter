package com.pochamps.supporter.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.pochamps.supporter.R
import com.pochamps.supporter.capture.RoiConfig
import com.pochamps.supporter.capture.RoiEditLogic
import com.pochamps.supporter.capture.RoiRect

/**
 * 인앱 ROI 보정 오버레이(P14). 게임 화면 위에 **전체화면 반투명 편집기**를 띄워,
 * 사용자가 상대 이름표 위치에 맞춰 밴드(사각형)를 드래그/리사이즈해 저장하게 한다.
 *
 * ## 터치 모델(P5 IME 토글과 같은 패턴)
 *  이 오버레이는 조작을 받아야 하므로 **focusable + 전체화면** 창으로 띄운다.
 *  닫으면 창을 제거해 게임 터치를 원복(카드 오버레이와 독립된 별도 창).
 *
 * ## 저장/반영
 *  "저장" → [RoiConfig] 조립 → [onSave](→ PrefsRoiConfigStore.save). 파이프라인은 프레임마다
 *  store 를 다시 읽으므로(roiConfigProvider) 다음 프레임부터 새 ROI 를 사용한다.
 */
class RoiCalibrationOverlay(
    private val context: Context,
    private val initial: RoiConfig,
    private val onSave: (RoiConfig) -> Unit,
    private val onClose: () -> Unit,
) : LifecycleOwner, SavedStateRegistryOwner {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private var composeView: ComposeView? = null
    private var added = false

    init {
        savedStateController.performRestore(null)
    }

    fun show() {
        if (added) return
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@RoiCalibrationOverlay)
            setViewTreeSavedStateRegistryOwner(this@RoiCalibrationOverlay)
            setContent { CalibrationRoot() }
        }
        composeView = view
        windowManager.addView(view, buildLayoutParams())
        added = true
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** 창 제거(닫기/저장 후). */
    fun dismiss() {
        if (!added) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            return
        }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        composeView?.let { runCatching { windowManager.removeView(it) } }
        composeView = null
        added = false
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        // 전체화면 + focusable(드래그/버튼 터치 수신). 플래그 없음 = 포커스/터치 획득.
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            0,
            PixelFormat.TRANSLUCENT,
        )
    }

    @Composable
    private fun CalibrationRoot() {
        val rois = remember { mutableStateListOf<RoiRect>().apply { addAll(initial.rois) } }
        var isDoubles by remember { mutableStateOf(initial.rois.size >= 2) }
        var canvas by remember { mutableStateOf(IntSize.Zero) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // P23: 스크림을 대폭 완화(0x99→0x14). 아래 게임 이름표가 선명히 보여야
                //  그 위로 박스를 정확히 정렬할 수 있다. 아주 옅은 딤만 남겨 편집 모드임을 표시.
                .background(Color(0x14_000000))
                .onSizeChanged { canvas = it },
        ) {
            // 밴드들(픽셀 위치로 변환해 배치, 드래그/리사이즈).
            if (canvas.width > 0 && canvas.height > 0) {
                rois.forEachIndexed { idx, rect ->
                    RoiBand(
                        index = idx,
                        rect = rect,
                        canvas = canvas,
                        onMove = { dx, dy ->
                            rois[idx] = RoiEditLogic.move(rois[idx], dx, dy, canvas.width, canvas.height)
                        },
                        onResize = { handle, dx, dy ->
                            rois[idx] = RoiEditLogic.resize(rois[idx], handle, dx, dy, canvas.width, canvas.height)
                        },
                    )
                }
            }

            // 상단 안내.
            Text(
                stringResource(R.string.calib_hint),
                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp, start = 16.dp, end = 16.dp)
                    .background(Color(0xCC_000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )

            // 하단 컨트롤 바.
            ControlBar(
                isDoubles = isDoubles,
                onSelectFormat = { doubles ->
                    isDoubles = doubles
                    val count = if (doubles) 2 else 1
                    val next = RoiEditLogic.resizeBandCount(rois.toList(), count)
                    rois.clear(); rois.addAll(next)
                },
                onReset = {
                    val count = if (isDoubles) 2 else 1
                    val next = RoiEditLogic.defaultRois(count)
                    rois.clear(); rois.addAll(next)
                },
                onSave = {
                    onSave(RoiConfig(rois.toList()))
                    dismiss(); onClose()
                },
                onClose = { dismiss(); onClose() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** 한 밴드(사각형) — 테두리 + 라벨 + 이동 드래그 + 4모서리 리사이즈 핸들. */
@Composable
private fun RoiBand(
    index: Int,
    rect: RoiRect,
    canvas: IntSize,
    onMove: (dx: Float, dy: Float) -> Unit,
    onResize: (RoiEditLogic.Handle, dx: Float, dy: Float) -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val leftPx = rect.left * canvas.width
    val topPx = rect.top * canvas.height
    val wPx = (rect.right - rect.left) * canvas.width
    val hPx = (rect.bottom - rect.top) * canvas.height
    val bandColor = if (index == 0) Color(0xFF_66BB6A) else Color(0xFF_8AB4F8)

    Box(
        modifier = Modifier
            .offset(
                x = with(density) { leftPx.toInt().toDp() },
                y = with(density) { topPx.toInt().toDp() },
            )
            .size(
                width = with(density) { wPx.toInt().coerceAtLeast(1).toDp() },
                height = with(density) { hPx.toInt().coerceAtLeast(1).toDp() },
            )
            // P23: 스크림을 걷어낸 대신, 박스 채움은 아주 옅게(이름표 가림 최소) + 테두리는
            //  굵고 진하게(3dp) 대비를 높여 박스 위치를 또렷이 식별.
            .background(Color(0x14_FFFFFF))
            .border(3.dp, bandColor, RoundedCornerShape(2.dp))
            .pointerInput(index, canvas) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onMove(drag.x, drag.y)
                }
            },
    ) {
        Text(
            "${index + 1}",
            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .background(bandColor, RoundedCornerShape(bottomEnd = 4.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
        // 4모서리 리사이즈 핸들.
        HandleDot(Alignment.TopStart, bandColor) { dx, dy -> onResize(RoiEditLogic.Handle.TOP_LEFT, dx, dy) }
        HandleDot(Alignment.TopEnd, bandColor) { dx, dy -> onResize(RoiEditLogic.Handle.TOP_RIGHT, dx, dy) }
        HandleDot(Alignment.BottomStart, bandColor) { dx, dy -> onResize(RoiEditLogic.Handle.BOTTOM_LEFT, dx, dy) }
        HandleDot(Alignment.BottomEnd, bandColor) { dx, dy -> onResize(RoiEditLogic.Handle.BOTTOM_RIGHT, dx, dy) }
    }
}

/** 모서리 리사이즈 핸들(작은 원). 드래그 delta 를 부모로 넘긴다. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.HandleDot(
    align: Alignment,
    color: Color,
    onDrag: (dx: Float, dy: Float) -> Unit,
) {
    Box(
        modifier = Modifier
            .align(align)
            .size(28.dp)
            .background(color, androidx.compose.foundation.shape.CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            },
    )
}

/** 하단 컨트롤 바: 싱글/더블 탭 + 초기화/저장/닫기. */
@Composable
private fun ControlBar(
    isDoubles: Boolean,
    onSelectFormat: (doubles: Boolean) -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xE6_1A1A1A))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabButton(stringResource(R.string.calib_single), selected = !isDoubles) { onSelectFormat(false) }
            TabButton(stringResource(R.string.calib_doubles), selected = isDoubles) { onSelectFormat(true) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(stringResource(R.string.calib_reset), Color(0xFF_546E7A), onReset)
            ActionButton(stringResource(R.string.calib_save), Color(0xFF_2E7D32), onSave)
            ActionButton(stringResource(R.string.calib_close), Color(0xFF_5D4037), onClose)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = if (selected) Color.White else Color(0xFF_B0B0B0),
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier
            .weight(1f)
            .background(
                if (selected) Color(0xFF_37474F) else Color(0x33_FFFFFF),
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ActionButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier
            .weight(1f)
            .background(color, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}
