package com.pochamps.supporter.debug

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import java.io.File
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.VideoView

/**
 * P10 E2E 검증 전용(디버그 빌드에만 존재). 릴리즈/메인 흐름 비침투.
 *
 * 에뮬레이터 화면에 field_samples 실배틀 스크린샷을 **가로 전체화면(immersive)** 으로 표시한다.
 * 이러면 앱이 MediaProjection 으로 캡처한 프레임 = 이 배틀 이미지가 되어,
 * "앱이 실제 화면을 스스로 캡처→OCR→오버레이 갱신"하는 완전한 E2E 파이프라인을 검증할 수 있다.
 *
 * 실행(디버그 APK, adb):
 *   # 정지 이미지:
 *   adb shell am start -n com.pochamps.supporter/.debug.SampleImageActivity \
 *     --es img /sdcard/Download/e2e_sample.jpg
 *   # 움직이는 배틀 영상(P11 동적 E2E):
 *   adb shell am start -n com.pochamps.supporter/.debug.SampleImageActivity \
 *     --es vid /sdcard/Download/battle.mp4
 *
 * ROI 정합: 배틀 이미지/영상은 가로(16:9~19.5:9). AVD 를 가로로 두면 화면비가 field_sample 뷰포트와 유사.
 * fitCenter(레터박스) 하면 상/하 레터박스로 ROI 가 어긋날 수 있으므로 기본은 fitXY(화면을 꽉 채움).
 * 필요 시 --es scale center 로 레터박스 표시. 영상은 VideoView 로 자동 루프 재생(무음).
 */
class SampleImageActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var imageView: ImageView
    private var videoView: VideoView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 상태바/네비바가 이미지를 가리지 않게 immersive(전체화면).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        imageView = ImageView(this)
        root.addView(
            imageView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)
        applyIntent(intent)
    }

    // singleTop 재실행 시 새 이미지/영상으로 갱신(서비스/캡처를 죽이지 않고 배경만 교체 가능).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    private fun applyIntent(intent: Intent) {
        val scale = intent.getStringExtra("scale") ?: "xy"
        val vid = intent.getStringExtra("vid")
        if (vid != null) {
            showVideo(vid)
            return
        }

        // 이미지 경로면 기존 VideoView 를 정리하고 ImageView 로.
        teardownVideo()
        imageView.visibility = View.VISIBLE
        imageView.scaleType =
            if (scale == "center") ImageView.ScaleType.FIT_CENTER
            else ImageView.ScaleType.FIT_XY

        val path = intent.getStringExtra("img")
        if (path != null) {
            val bmp = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
            if (bmp != null) {
                imageView.setImageBitmap(bmp)
                Log.i(TAG, "이미지 표시: $path (${bmp.width}x${bmp.height}) scale=$scale")
            } else {
                Log.e(TAG, "이미지 디코드 실패: $path")
            }
        } else {
            Log.e(TAG, "img/vid extra 없음")
        }
    }

    /** P11 동적 E2E: 배틀 영상을 fitXY 전체화면으로 무음 루프 재생. */
    private fun showVideo(path: String) {
        teardownVideo()
        imageView.visibility = View.GONE
        val vv = VideoView(this)
        root.addView(
            vv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                android.view.Gravity.CENTER,
            ),
        )
        // 로컬 파일 경로는 file:// URI 로 넘겨야 VideoView 가 안정적으로 연다.
        val uri = if (path.startsWith("/")) Uri.fromFile(File(path)) else Uri.parse(path)
        vv.setVideoURI(uri)
        vv.setOnPreparedListener { mp ->
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            // VideoView 는 종횡비를 유지(letterbox)한다. AVD 를 16:9 로 맞추면 레터박스가 최소화된다.
            mp.start()
            Log.i(TAG, "영상 재생: $path (${mp.videoWidth}x${mp.videoHeight}) looping")
        }
        vv.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "영상 재생 실패: $path what=$what extra=$extra")
            true
        }
        videoView = vv
        vv.requestFocus()
    }

    private fun teardownVideo() {
        videoView?.let {
            runCatching { it.stopPlayback() }
            root.removeView(it)
        }
        videoView = null
    }

    override fun onDestroy() {
        teardownVideo()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "SampleImageActivity"
    }
}
