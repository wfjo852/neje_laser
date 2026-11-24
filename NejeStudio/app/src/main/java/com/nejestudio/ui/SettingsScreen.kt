package com.nejestudio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nejestudio.ble.DeviceInfo
import com.nejestudio.ble.DeviceSetting
import com.nejestudio.model.Geometry
import com.nejestudio.model.Origin
import com.nejestudio.model.Preset
import com.nejestudio.model.WorkArea
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    geometry: Geometry,
    info: DeviceInfo?,
    setting: DeviceSetting?,
    presets: List<Preset>,
    onWorkArea: (WorkArea) -> Unit,
    onDeviceSetting: ((DeviceSetting) -> DeviceSetting) -> Unit,
    onDeletePreset: (Long) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val wa = geometry.area
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("작업 구역")
            Text(
                "각인할 재료가 놓이는 실제 영역입니다. 편집 화면의 흰 영역이 이 크기로 표시됩니다.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MmField("가로 (mm)", wa.widthMm, { onWorkArea(wa.copy(widthMm = it.coerceIn(1f, 2000f))) }, Modifier.weight(1f))
                MmField("세로 (mm)", wa.heightMm, { onWorkArea(wa.copy(heightMm = it.coerceIn(1f, 2000f))) }, Modifier.weight(1f))
            }
            val tooBig = wa.widthMm > geometry.maxWidthMm + 0.05f || wa.heightMm > geometry.maxHeightMm + 0.05f
            Text(
                "기기 최대 범위: %.1f × %.1f mm  (%d × %d px, %.3f mm/px)%s".format(
                    geometry.maxWidthMm, geometry.maxHeightMm, geometry.machine.maxWidth, geometry.machine.maxHeight,
                    geometry.mmPerPx, if (info == null) " · 연결 전 저장값" else "",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (tooBig) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
            if (tooBig) Text("작업 구역이 기기 범위보다 커서 최대 범위로 제한됩니다.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                onWorkArea(wa.copy(widthMm = round1(geometry.maxWidthMm), heightMm = round1(geometry.maxHeightMm)))
            }) { Text("기기 최대 크기로 설정") }

            Section("영점 위치")
            Text(
                "작업 구역이 기기의 어느 모서리에 붙는지, 그리고 위치(X, Y)를 어느 모서리 기준으로 잴지 정합니다.",
                style = MaterialTheme.typography.bodySmall,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OriginCard(Origin.TOP_LEFT, wa.origin, Modifier.weight(1f)) { onWorkArea(wa.copy(origin = it)) }
                    OriginCard(Origin.TOP_RIGHT, wa.origin, Modifier.weight(1f)) { onWorkArea(wa.copy(origin = it)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OriginCard(Origin.BOTTOM_LEFT, wa.origin, Modifier.weight(1f)) { onWorkArea(wa.copy(origin = it)) }
                    OriginCard(Origin.BOTTOM_RIGHT, wa.origin, Modifier.weight(1f)) { onWorkArea(wa.copy(origin = it)) }
                }
            }
            Text(
                "기기 좌표: 작업 구역 좌상단 = (%d, %d) px".format(geometry.deviceLeft, geometry.deviceTop),
                style = MaterialTheme.typography.labelSmall,
            )

            Section("프리셋")
            if (presets.isEmpty()) {
                Text("저장된 프리셋이 없습니다. 각인 탭에서 '+ 현재 값 저장'을 누르세요.", style = MaterialTheme.typography.bodySmall)
            }
            presets.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.SemiBold)
                        Text("각인 시간 ${p.carvingTime} ms · 파워 ${p.power} %", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { onDeletePreset(p.id) }) { Icon(Icons.Default.Delete, "삭제") }
                }
                HorizontalDivider()
            }

            Section("기기 설정")
            if (info == null || setting == null) {
                Text("각인기를 연결하면 표시됩니다.", style = MaterialTheme.typography.bodySmall)
            } else {
                if (info.supportsWeakLevel) {
                    LabeledSlider("약광 밝기 (위치 확인용)", setting.weakLevel.toFloat(), 0f..100f, "${setting.weakLevel}") { v ->
                        onDeviceSetting { it.copy(weakLevel = v.roundToInt()) }
                    }
                }
                if (info.supportsTilt) {
                    LabeledSlider("기울기 감지 민감도", setting.senseLevel.toFloat(), 0f..3f, "${setting.senseLevel}") { v ->
                        onDeviceSetting { it.copy(senseLevel = v.roundToInt()) }
                    }
                }
                if (info.supportsFastMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("빠른 모드", Modifier.weight(1f))
                        Switch(checked = setting.fastMode != 0, onCheckedChange = { b ->
                            onDeviceSetting { it.copy(fastMode = if (b) 1 else 0) }
                        })
                    }
                }
                Section("기기 정보")
                Text(
                    "모델: ${info.model}\n펌웨어: ${info.firmware} · 프로토콜: ${info.protocolVersion}\n" +
                        "해상도: ${info.mmPerPixel} mm/px · 범위: ${info.maxWidth} × ${info.maxHeight} px",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun round1(v: Float) = (v * 10).toInt() / 10f

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun OriginCard(origin: Origin, current: Origin, modifier: Modifier, onPick: (Origin) -> Unit) {
    val selected = origin == current
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.clickable { onPick(origin) },
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant),
        color = if (selected) accent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val outline = MaterialTheme.colorScheme.outline
            Canvas(Modifier.size(width = 44.dp, height = 32.dp)) {
                drawRect(outline, style = Stroke(1.5.dp.toPx()))
                val cx = if (origin.isLeft) 0f else size.width
                val cy = if (origin.isTop) 0f else size.height
                drawCircle(Color(0xFFE53935), 5.dp.toPx(), Offset(cx, cy))
                // 작업 구역 표시
                val w = size.width * 0.55f; val h = size.height * 0.55f
                val x = if (origin.isLeft) 0f else size.width - w
                val y = if (origin.isTop) 0f else size.height - h
                drawRect(accent.copy(alpha = 0.35f), Offset(x, y), Size(w, h))
            }
            Spacer(Modifier.width(10.dp))
            Text(origin.label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}
