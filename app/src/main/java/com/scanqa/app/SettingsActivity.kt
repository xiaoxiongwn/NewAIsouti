package com.scanqa.app

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.scanqa.app.ai.AiClient
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    /** 一行 UI 对应的控件 + 它绑定的 profile id（新增的行还没有真正的 id，会在保存时生成/沿用）。 */
    private data class ProfileRow(
        val view: View,
        val id: String,
        val rbActive: RadioButton,
        val etName: EditText,
        val etBase: EditText,
        val etKey: EditText,
        val etModel: EditText
    )

    private val rows = mutableListOf<ProfileRow>()
    private lateinit var llProfiles: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        llProfiles = findViewById(R.id.llProfiles)

        val existing = Prefs.profiles(this)
        val activeId = Prefs.activeProfileId(this)
        if (existing.isEmpty()) {
            // 第一次使用：给一个空白模板，方便用户直接填
            addRow(
                AiProfile(
                    id = Prefs.newProfileId(),
                    name = "默认",
                    baseUrl = Prefs.DEFAULT_BASE,
                    apiKey = "",
                    model = Prefs.DEFAULT_MODEL
                ),
                isActive = true
            )
        } else {
            existing.forEach { p -> addRow(p, isActive = p.id == activeId) }
        }

        findViewById<MaterialButton>(R.id.btnAddProfile).setOnClickListener {
            addRow(
                AiProfile(
                    id = Prefs.newProfileId(),
                    name = "",
                    baseUrl = Prefs.DEFAULT_BASE,
                    apiKey = "",
                    model = ""
                ),
                isActive = rows.isEmpty()
            )
        }

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }
    }

    private fun addRow(profile: AiProfile, isActive: Boolean) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_ai_profile, llProfiles, false)

        val rbActive = view.findViewById<RadioButton>(R.id.rbActive)
        val etName = view.findViewById<EditText>(R.id.etName)
        val etBase = view.findViewById<EditText>(R.id.etBase)
        val etKey = view.findViewById<EditText>(R.id.etKey)
        val etModel = view.findViewById<EditText>(R.id.etModel)
        val btnTest = view.findViewById<MaterialButton>(R.id.btnTest)
        val btnDelete = view.findViewById<MaterialButton>(R.id.btnDelete)

        etName.setText(profile.name)
        etBase.setText(profile.baseUrl)
        etKey.setText(profile.apiKey)
        etModel.setText(profile.model)
        rbActive.isChecked = isActive

        val row = ProfileRow(view, profile.id, rbActive, etName, etBase, etKey, etModel)
        rows.add(row)
        llProfiles.addView(view)

        rbActive.setOnClickListener { setActiveRow(row) }

        btnTest.setOnClickListener {
            val testProfile = AiProfile(
                id = "test",
                name = etName.text.toString().trim().ifBlank { "这个模型" },
                baseUrl = etBase.text.toString().trim(),
                apiKey = etKey.text.toString().trim(),
                model = etModel.text.toString().trim()
            )
            if (testProfile.baseUrl.isBlank() || testProfile.apiKey.isBlank() || testProfile.model.isBlank()) {
                Toast.makeText(this, "请先把地址、Key、模型名都填好再测试", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnTest.isEnabled = false
            btnTest.text = "测试中…"
            lifecycleScope.launch {
                val result = AiClient.answer(testProfile, "请只回答：1+1等于几？")
                btnTest.isEnabled = true
                btnTest.text = "测试"
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("「${testProfile.name}」测试结果")
                    .setMessage(result)
                    .setPositiveButton("好", null)
                    .show()
            }
        }

        btnDelete.setOnClickListener {
            val wasActive = rbActive.isChecked
            llProfiles.removeView(view)
            rows.remove(row)
            // 删掉的是当前使用的那个，就把第一个剩下的设为当前
            if (wasActive) {
                rows.firstOrNull()?.let { setActiveRow(it) }
            }
        }
    }

    private fun setActiveRow(active: ProfileRow) {
        rows.forEach { it.rbActive.isChecked = (it === active) }
    }

    private fun save() {
        if (rows.isEmpty()) {
            Toast.makeText(this, "至少保留一个模型配置", Toast.LENGTH_SHORT).show()
            return
        }

        val profiles = rows.map { row ->
            AiProfile(
                id = row.id,
                name = row.etName.text.toString().trim().ifBlank { "未命名模型" },
                baseUrl = row.etBase.text.toString().trim().ifBlank { Prefs.DEFAULT_BASE },
                apiKey = row.etKey.text.toString().trim(),
                model = row.etModel.text.toString().trim().ifBlank { Prefs.DEFAULT_MODEL }
            )
        }

        var activeIndex = rows.indexOfFirst { it.rbActive.isChecked }
        if (activeIndex < 0) activeIndex = 0

        Prefs.saveProfiles(this, profiles)
        Prefs.setActiveProfileId(this, profiles[activeIndex].id)

        Toast.makeText(this, "已保存，当前使用：${profiles[activeIndex].name}", Toast.LENGTH_SHORT).show()
        finish()
    }
}
