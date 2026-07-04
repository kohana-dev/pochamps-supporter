package com.pochamps.supporter.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.SystemClock
import android.util.Log

/**
 * [1] CaptureManager — null-surface VirtualDisplay 에 ImageReader 를 붙여 프레임을 받는다.
 *
 * ## 역할
 *  - 이미 확보된 [VirtualDisplay](CaptureService 의 null-surface 세션)에 ImageReader.surface 를 setSurface() 로 연결.
 *  - 다운스케일 캡처(화면의 1/2 등)로 연산/메모리 절감.
 *  - ImageReader.OnImageAvailableListener 에서 Image→Bitmap 변환(rowStride/padding 처리).
 *  - 프레임 콜백 스로틀(초당 최대 N회) — 최신 프레임만 필요하므로 오래된 프레임은 버린다.
 *
 * ## 스레딩
 *  ImageReader 콜백은 [callbackHandler] 스레드에서 온다(경량 유지). Bitmap 변환까지만 하고,
 *  무거운 FrameGate/OCR 은 상위 파이프라인이 별도 Dispatcher 에서 처리한다.
 *  콜백은 "가장 최근 이미지만" 취득(acquireLatestImage)하여 백프레셔를 자연히 흘려보낸다.
 *
 * @param downscale 캡처 축소 비율(0<..<=1). 0.5 면 화면의 절반 해상도로 캡처.
 * @param minFrameIntervalMs 프레임 콜백 최소 간격(ms). 초당 최대 (1000/이 값) 회. 기본 350ms≈초당 ~3회.
 */
class CaptureManager(
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val densityDpi: Int,
    private val callbackHandler: Handler,
    private val downscale: Float = DEFAULT_DOWNSCALE,
    private val minFrameIntervalMs: Long = DEFAULT_MIN_FRAME_INTERVAL_MS,
) {
    /** 다운스케일된 캡처 폭/높이(2의 배수로 정렬 — 인코더/리더 호환성). */
    private val captureWidth: Int = (screenWidth * downscale).toInt().coerceAtLeast(2) and 1.inv()
    private val captureHeight: Int = (screenHeight * downscale).toInt().coerceAtLeast(2) and 1.inv()

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    /** 스로틀: 마지막 프레임 처리 시각. */
    @Volatile private var lastFrameAtMs: Long = 0L

    /** 재사용 Bitmap(패딩 포함 폭). 매 프레임 새로 만들지 않기 위해 캐시. */
    private var reusableBitmap: Bitmap? = null

    /** 프레임 콜백. Bitmap 은 콜백 반환 후 재사용될 수 있으므로, 상위에서 필요하면 즉시 소비/복사할 것. */
    private var onFrame: ((Bitmap, Long) -> Unit)? = null

    /**
     * 캡처 시작: null-surface VirtualDisplay 에 ImageReader 를 붙인다.
     *
     * @param mediaProjectionVirtualDisplay CaptureService 가 만든 VirtualDisplay(setSurface 로 연결).
     * @param onFrame 다운스케일 Bitmap + 캡처 시각(ms) 콜백(스로틀 적용됨).
     */
    fun start(
        mediaProjectionVirtualDisplay: VirtualDisplay,
        onFrame: (Bitmap, Long) -> Unit,
    ) {
        this.virtualDisplay = mediaProjectionVirtualDisplay
        this.onFrame = onFrame

        val reader = ImageReader.newInstance(
            captureWidth, captureHeight, PixelFormat.RGBA_8888, MAX_IMAGES,
        )
        reader.setOnImageAvailableListener({ r -> onImageAvailable(r) }, callbackHandler)
        imageReader = reader

        // null-surface 세션에 실제 surface 를 붙여 프레임 수신 시작.
        mediaProjectionVirtualDisplay.resize(captureWidth, captureHeight, densityDpi)
        mediaProjectionVirtualDisplay.surface = reader.surface
        Log.i(TAG, "캡처 시작: ${captureWidth}x$captureHeight (downscale=$downscale)")
    }

    /** 캡처 중지: surface 를 떼어(=null) 세션은 유지하되 프레임 수신만 멈춘다. */
    fun stop() {
        runCatching { virtualDisplay?.surface = null }
        runCatching { imageReader?.close() }
        imageReader = null
        reusableBitmap?.recycle()
        reusableBitmap = null
        onFrame = null
        Log.i(TAG, "캡처 중지(surface detach)")
    }

    private fun onImageAvailable(reader: ImageReader) {
        // 항상 최신 이미지만 취득(오래된 프레임은 버려 백프레셔 방지).
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val now = SystemClock.uptimeMillis()
            // 스로틀: 최소 간격 안이면 프레임을 버린다(초당 처리수 제한).
            if (now - lastFrameAtMs < minFrameIntervalMs) return
            lastFrameAtMs = now

            val bitmap = imageToBitmap(image) ?: return
            onFrame?.invoke(bitmap, now)
        } finally {
            image.close()
        }
    }

    /**
     * Image(RGBA_8888) → Bitmap. rowStride 가 width*4 보다 클 수 있어(행 패딩) 패딩을 포함해
     * 넓은 Bitmap 으로 복사한 뒤, 필요한 width 만 잘라낸다.
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        if (planes.isEmpty()) return null
        val plane = planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        // 패딩 픽셀 수를 포함한 폭.
        val paddedWidth = image.width + rowPadding / pixelStride

        // 재사용 Bitmap 이 크기가 맞지 않으면 새로 만든다.
        var bmp = reusableBitmap
        if (bmp == null || bmp.width != paddedWidth || bmp.height != image.height) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            reusableBitmap = bmp
        }
        bmp.copyPixelsFromBuffer(buffer)

        // 패딩이 없으면 그대로, 있으면 실제 width 로 잘라 반환.
        return if (rowPadding == 0) {
            bmp
        } else {
            Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
        }
    }

    companion object {
        private const val TAG = "CaptureManager"
        const val DEFAULT_DOWNSCALE = 0.5f
        const val DEFAULT_MIN_FRAME_INTERVAL_MS = 350L // ≈ 초당 ~3회
        private const val MAX_IMAGES = 2 // 최신 프레임만 필요 → 얕은 버퍼.
    }
}
