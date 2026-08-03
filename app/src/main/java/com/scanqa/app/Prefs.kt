package com.scanqa.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 一个 AI 服务配置：名称 + 接口地址 + Key + 模型名。用户可以配置多个，使用时切换。 */
data class AiProfile(
    val id: String,
    var name: String,
    var baseUrl: String,
    var apiKey: String,
    var model: String
)

object Prefs {
    private const val NAME = "scanqa_prefs"

    private const val KEY_PROFILES = "ai_profiles_json"
    private const val KEY_ACTIVE_ID = "active_profile_id"

    // 旧版本（只支持单个模型）用过的 key，仅用于一次性迁移到新的多模型结构
    private const val LEGACY_KEY_BASE = "base_url"
    private const val LEGACY_KEY_API = "api_key"
    private const val LEGACY_KEY_MODEL = "model"

    const val DEFAULT_BASE = "https://api.deepseek.com/v1"
    const val DEFAULT_MODEL = "deepseek-chat"

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun newProfileId(): String = UUID.randomUUID().toString()

    /** 所有已配置的 AI 模型（自动做一次旧数据迁移）。 */
    fun profiles(ctx: Context): MutableList<AiProfile> {
        migrateIfNeeded(ctx)
        val raw = sp(ctx).getString(KEY_PROFILES, null) ?: return mutableListOf()
        val result = mutableListOf<AiProfile>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result.add(
                    AiProfile(
                        id = o.optString("id").ifBlank { newProfileId() },
                        name = o.optString("name"),
                        baseUrl = o.optString("baseUrl"),
                        apiKey = o.optString("apiKey"),
                        model = o.optString("model")
                    )
                )
            }
        } catch (e: Exception) {
            // 数据损坏时不崩溃，当作没有配置
        }
        return result
    }

    fun saveProfiles(ctx: Context, list: List<AiProfile>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("baseUrl", p.baseUrl)
                    .put("apiKey", p.apiKey)
                    .put("model", p.model)
            )
        }
        sp(ctx).edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    fun activeProfileId(ctx: Context): String? =
        sp(ctx).getString(KEY_ACTIVE_ID, null)

    fun setActiveProfileId(ctx: Context, id: String) {
        sp(ctx).edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    /** 当前选中的模型；如果之前选的那个被删掉了，就退回列表第一个。 */
    fun activeProfile(ctx: Context): AiProfile? {
        val list = profiles(ctx)
        if (list.isEmpty()) return null
        val id = activeProfileId(ctx)
        val found = list.find { it.id == id }
        if (found != null) return found
        setActiveProfileId(ctx, list.first().id)
        return list.first()
    }

    /** 把旧版本单模型设置（如果存在）搬到新的多模型列表里，只做一次。 */
    private fun migrateIfNeeded(ctx: Context) {
        val prefs = sp(ctx)
        if (prefs.contains(KEY_PROFILES)) return

        val legacyBase = prefs.getString(LEGACY_KEY_BASE, null)
        val legacyKey = prefs.getString(LEGACY_KEY_API, null)
        val legacyModel = prefs.getString(LEGACY_KEY_MODEL, null)

        val list = mutableListOf<AiProfile>()
        if (!legacyBase.isNullOrBlank() || !legacyKey.isNullOrBlank() || !legacyModel.isNullOrBlank()) {
            list.add(
                AiProfile(
                    id = newProfileId(),
                    name = "默认",
                    baseUrl = legacyBase?.ifBlank { DEFAULT_BASE } ?: DEFAULT_BASE,
                    apiKey = legacyKey.orEmpty(),
                    model = legacyModel?.ifBlank { DEFAULT_MODEL } ?: DEFAULT_MODEL
                )
            )
        }
        saveProfiles(ctx, list)
        if (list.isNotEmpty()) {
            setActiveProfileId(ctx, list.first().id)
        }
    }
}
