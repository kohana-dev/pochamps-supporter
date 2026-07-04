package com.pochamps.supporter.capture

import android.graphics.Bitmap
import android.graphics.Matrix

/**
 * [3] RoiCropper — [RoiConfig] 의 비율 ROI 를 캡처 비트맵에서 실제로 잘라낸다(+옵션 업스케일).
 *
 * 비율 rect 계산은 [RoiRect]/[RoiConfig](순수 JVM)에 있고, 여기선 Bitmap 크롭/스케일만 담당한다.
 * OCR 정확도를 위해 작은 크롭을 [upscaleFactor] 배로 키우는 전처리 옵션을 제공한다(DESIGN.md K3 대응).
 *
 * @param upscaleFactor 크롭 후 확대 배율. 1f 면 확대 안 함. 2f 권장(작은 폰트 OCR 개선).
 */
class RoiCropper(
    private val upscaleFactor: Float = DEFAULT_UPSCALE,
) {

    /**
     * ROI 하나를 크롭한다. 좌표는 [RoiRect] 비율을 비트맵 크기에 맞춰 픽셀로 변환.
     * @return 크롭(+업스케일)된 비트맵. 크롭 영역이 비정상이면 null.
     */
    fun crop(source: Bitmap, roi: RoiRect): Bitmap? {
        val rect = roi.toPixels(source.width, source.height)
        return cropPixels(source, rect)
    }

    /**
     * [RoiConfig] 의 모든 ROI 를 크롭해 (roiIndex, bitmap) 리스트로 반환.
     * 특정 ROI 크롭이 실패하면 그 항목은 건너뛴다(리스트에서 제외).
     */
    fun cropAll(source: Bitmap, config: RoiConfig): List<CroppedRoi> =
        config.rois.mapIndexedNotNull { index, roi ->
            crop(source, roi)?.let { CroppedRoi(index, it) }
        }

    /**
     * 전체화면 상단 절반 fallback 크롭. ROI 크롭이 전부 실패했을 때 사용.
     * roiIndex 는 -1(=fallback 표식).
     */
    fun cropFullTopHalf(source: Bitmap): CroppedRoi? =
        crop(source, RoiConfig.FULL_TOP_HALF)?.let { CroppedRoi(FALLBACK_ROI_INDEX, it) }

    private fun cropPixels(source: Bitmap, rect: PixelRect): Bitmap? {
        // 경계 방어(비트맵 밖 접근 방지).
        if (rect.width <= 0 || rect.height <= 0) return null
        if (rect.x + rect.width > source.width || rect.y + rect.height > source.height) return null

        val cropped = Bitmap.createBitmap(source, rect.x, rect.y, rect.width, rect.height)
        if (upscaleFactor <= 1f) return cropped

        // 업스케일 전처리(OCR 정확도용). FILTER=true 로 부드럽게 확대.
        val matrix = Matrix().apply { postScale(upscaleFactor, upscaleFactor) }
        val scaled = Bitmap.createBitmap(
            cropped, 0, 0, cropped.width, cropped.height, matrix, /* filter = */ true,
        )
        // createBitmap 이 새 인스턴스를 반환한 경우 원본 크롭은 회수.
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }

    /** 크롭 결과: 어느 ROI(index) 에서 나온 어떤 비트맵인지. */
    data class CroppedRoi(val roiIndex: Int, val bitmap: Bitmap)

    companion object {
        const val DEFAULT_UPSCALE = 2f

        /** fallback(전체 상단 절반) 크롭의 roiIndex 표식. */
        const val FALLBACK_ROI_INDEX = -1
    }
}
