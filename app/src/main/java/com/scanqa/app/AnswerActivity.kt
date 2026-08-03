package com.scanqa.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/** 全屏查看 AI 解答，方便看长一点的解题过程，不用挤在小窗口里。 */
class AnswerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TEXT = "answer_text"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_answer)

        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()

        val tvAnswerFull = findViewById<TextView>(R.id.tvAnswerFull)
        tvAnswerFull.text = text

        findViewById<MaterialButton>(R.id.btnClose).setOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btnCopy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AI 解答", text))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }
    }
}
