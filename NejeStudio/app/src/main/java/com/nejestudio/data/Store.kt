package com.nejestudio.data

import android.content.Context
import com.nejestudio.model.MachineProfile
import com.nejestudio.model.Origin
import com.nejestudio.model.Preset
import com.nejestudio.model.WorkArea
import org.json.JSONArray
import org.json.JSONObject

/** 설정·프리셋 저장소 (SharedPreferences) */
class Store(context: Context) {
    private val p = context.getSharedPreferences("neje_studio", Context.MODE_PRIVATE)

    fun loadWorkArea(): WorkArea = WorkArea(
        widthMm = p.getFloat("wa_w", 40f),
        heightMm = p.getFloat("wa_h", 40f),
        origin = runCatching { Origin.valueOf(p.getString("wa_origin", null)!!) }.getOrDefault(Origin.TOP_LEFT),
    )

    fun saveWorkArea(w: WorkArea) {
        p.edit().putFloat("wa_w", w.widthMm).putFloat("wa_h", w.heightMm).putString("wa_origin", w.origin.name).apply()
    }

    fun loadMachine(): MachineProfile {
        val d = MachineProfile()
        return MachineProfile(
            maxWidth = p.getInt("m_w", d.maxWidth),
            maxHeight = p.getInt("m_h", d.maxHeight),
            mmPerPixel = p.getFloat("m_mpp", d.mmPerPixel),
        )
    }

    fun saveMachine(m: MachineProfile) {
        p.edit().putInt("m_w", m.maxWidth).putInt("m_h", m.maxHeight).putFloat("m_mpp", m.mmPerPixel).apply()
    }

    fun loadPresets(): List<Preset> {
        val raw = p.getString("presets", null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                Preset(o.getLong("id"), o.getString("name"), o.getInt("time"), o.getInt("power"))
            }
        }.getOrDefault(emptyList())
    }

    fun savePresets(list: List<Preset>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("time", it.carvingTime).put("power", it.power))
        }
        p.edit().putString("presets", a.toString()).apply()
    }

    var lastAddress: String?
        get() = p.getString("last_addr", null)
        set(v) { p.edit().putString("last_addr", v).apply() }

    var lastCarvingTime: Int
        get() = p.getInt("last_time", 30)
        set(v) { p.edit().putInt("last_time", v).apply() }

    var lastPower: Int
        get() = p.getInt("last_power", 100)
        set(v) { p.edit().putInt("last_power", v).apply() }
}
