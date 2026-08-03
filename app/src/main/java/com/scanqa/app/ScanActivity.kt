package com.scanqa.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.scanqa.app.ai.AiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class ScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var panel: View
    private lateinit var ivPhoto: ImageView
    private lateinit var etText: EditText
    private lateinit var tvAnswer: TextView
    private lateinit var btnCapture: Button
    private lateinit var btnModel: Button
    private lateinit var btnAi: Button
    private lateinit var btnWeb: Button
    private lateinit var btnRetake: Button
    private lateinit var btnFullscreen: Button

    private var imageCapture: ImageCapture? = null
    private var capturedFile: File? = null
    private var profiles: List<AiProfile> = emptyList()
    private var selectedProfile: AiProfile? = null
    private var orientationEventListener: OrientationEventListener? = null
    private val recognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        previewView = findViewById(R.id.previewView)
        panel = findViewById(R.id.panel)
        ivPhoto = findViewById(R.id.ivPhoto)
        etText = findViewById(R.id.etText)
        tvAnswer = findViewById(R.id.tvAnswer)
        btnCapture = findViewById(R.id.btnCapture)
        btnModel = findViewById(R.id.btnModel)
        btnAi = findViewById(R.id.btnAi)
        btnWeb = findViewById(R.id.btnWeb)
        btnRetake = findViewById(R.id.btnRetake)
        btnFullscreen = findViewById(R.id.btnFullscreen)

        btnCapture.setOnClickListener { capture() }
        btnModel.setOnClickListener { showModelPicker() }
        btnAi.setOnClickListener { askAi() }
        btnWeb.setOnClickListener { webSearch() }
        btnRetake.setOnClickListener { resetToCamera() }
        btnFullscreen.setOnClickListener { openFullscreenAnswer() }

        // 界面本身锁定竖屏显示，但拍照时手机可能被横着拿（拍长题目）。
        // 这里实时监听手机的物理朝向，动态纠正 ImageCapture 的旋转角度，
        // 这样横拍出来的照片方向信息才是对的，OCR 识别顺序才不会乱。
        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageCapture?.targetRotation = rotation
            }
        }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }
    }

    override fun onResume() {
        super.onResume()
        // 从设置页返回时，模型列表可能有增删改，刷新一下
        loadProfiles()
        if (orientationEventListener?.canDetectOrientation() == true) {
            orientationEventListener?.enable()
        }
    }

    override fun onPause() {
        super.onPause()
        orientationEventListener?.disable()
    }

    private fun loadProfiles() {
        profiles = Prefs.profiles(this)
        val activeId = Prefs.activeProfileId(this)
        selectedProfile = profiles.find { it.id == activeId } ?: profiles.firstOrNull()
        updateModelButtonText()
    }

    private fun updateModelButtonText() {
        val p = selectedProfile
        btnModel.text = if (p != null) {
            "当前模型：${p.name}（点击切换）"
        } else {
            "⚠️ 还没配置模型，点击去设置"
        }
    }

    private fun showModelPicker() {
        if (profiles.isEmpty()) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val names = profiles.map { it.name.ifBlank { it.model } }.toTypedArray()
        val currentIndex = profiles.indexOfFirst { it.id == selectedProfile?.id }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("选择解题模型")
            .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                selectedProfile = profiles[which]
                Prefs.setActiveProfileId(this, selectedProfile!!.id)
                updateModelButtonText()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("管理模型") { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .show()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && hasCameraPermission()) {
            startCamera()
        } else {
            Toast.makeText(this, "需要相机权限才能扫描", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            } catch (e: Exception) {
                Toast.makeText(this, "相机启动失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capture() {
        val ic = imageCapture ?: return
        val file = File(cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(file).build()
        btnCapture.isEnabled = false
        Toast.makeText(this, "识别中…", Toast.LENGTH_SHORT).show()
        ic.takePicture(
            opts,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturedFile = file
                    runOcr(Uri.fromFile(file))
                }

                override fun onError(exc: ImageCaptureException) {
                    btnCapture.isEnabled = true
                    Toast.makeText(this@ScanActivity, "拍照失败：${exc.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun runOcr(uri: Uri) {
        val image = try {
            InputImage.fromFilePath(this, uri)
        } catch (e: Exception) {
            btnCapture.isEnabled = true
            Toast.makeText(this, "读取图片失败：${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        recognizer.process(image)
            .addOnSuccessListener { result ->
                btnCapture.isEnabled = true
                val text = result.text.trim()
                if (text.isEmpty()) {
                    // 纯图形/图表题常常识别不到文字，但图片本身仍然有用，
                    // 所以不再中断流程，继续把照片交给 AI 解答。
                    Toast.makeText(this, "没识别到文字，将直接用照片让 AI 解答", Toast.LENGTH_SHORT).show()
                }
                showResult(text, uri)
            }
            .addOnFailureListener { e ->
                btnCapture.isEnabled = true
                // OCR 失败也不阻断，照片依然可以直接发给 AI
                Toast.makeText(this, "文字识别失败，将直接用照片让 AI 解答", Toast.LENGTH_SHORT).show()
                showResult("", uri)
            }
    }

    private fun showResult(text: String, photoUri: Uri) {
        etText.setText(text)
        tvAnswer.text = ""
        ivPhoto.setImageURI(photoUri)
        ivPhoto.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
    }

    private fun resetToCamera() {
        panel.visibility = View.GONE
        etText.setText("")
        tvAnswer.text = ""
        capturedFile = null
    }

    /** 把拍到的照片压缩成较小的 JPEG 并转成 Base64，供多模态模型识别图片内容用。 */
    private suspend fun photoToBase64(): String? = withContext(Dispatchers.IO) {
        val file = capturedFile ?: return@withContext null
        try {
            // 先只读边界，算出合适的缩小比例，避免大图片占用过多内存/流量
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, boundsOpts)
            val maxSide = 1280
            var sample = 1
            while ((boundsOpts.outWidth / sample) > maxSide || (boundsOpts.outHeight / sample) > maxSide) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeFile(file.path, decodeOpts) ?: return@withContext null

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bitmap.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun askAi() {
        val profile = selectedProfile
        if (profile == null) {
            Toast.makeText(this, "还没有配置 AI 模型，请先去设置里添加", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val q = etText.text.toString().trim()
        if (q.isEmpty() && capturedFile == null) {
            Toast.makeText(this, "题目为空", Toast.LENGTH_SHORT).show()
            return
        }
        tvAnswer.text = "AI 思考中…（${profile.name}）"
        btnAi.isEnabled = false
        lifecycleScope.launch {
            val imageBase64 = photoToBase64()
            val ans = AiClient.answer(profile, q, imageBase64)
            tvAnswer.text = ans
            btnAi.isEnabled = true
        }
    }

    private fun webSearch() {
        val q = etText.text.toString().trim()
        if (q.isEmpty()) {
            Toast.makeText(this, "题目为空", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, WebSearchActivity::class.java).putExtra("query", q))
    }

    private fun openFullscreenAnswer() {
        val text = tvAnswer.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "还没有解答内容", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, AnswerActivity::class.java).putExtra(AnswerActivity.EXTRA_TEXT, text))
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
    }
}
