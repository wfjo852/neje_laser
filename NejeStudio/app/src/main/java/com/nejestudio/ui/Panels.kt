package com.nejestudio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nejestudio.ble.MachineState
import com.nejestudio.ble.NejeBle
import com.nejestudio.model.CanvasItem
import com.nejestudio.model.FontKind
import com.nejestudio.model.Geometry
import com.nejestudio.model.ItemKind
import com.nejestudio.model.Preset
import com.nejestudio.model.RenderMode
import com.nejestudio.model.TextSpec
import kotlin.math.roundToInt

private fun fmt(v: Float) = "%.1f".format(v)

/** mm 숫자 입력칸. 포커스를 잃거나 완료를 누르면 반영 */
@Composable
fun MmField(label: String, value: Float, onCommit: (Float) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(fmt(value)) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(value) { if (!focused) text = fmt(value) }
    fun commit() {
        text.replace(',', '.').toFloatOrNull()?.let { if (it != value) onCommit(it) } ?: run { text = fmt(value) }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit(); focus.clearFocus() }),
        modifier = modifier.onFocusChanged {
            if (focused && !it.isFocused) commit()
            focused = it.isFocused
        },
    )
}

@Composable
fun ItemPanel(
    item: CanvasItem,
    geometry: Geometry,
    onRect: (x: Float?, y: Float?, w: Float?, h: Float?, lock: Boolean) -> Unit,
    onChange: ((CanvasItem) -> CanvasItem) -> Unit,
    onCrop: () -> Unit,
    onEditText: () -> Unit,
    onRotate: () -> Unit,
    onCenter: () -> Unit,
    onOrigin: () -> Unit,
    onFront: () -> Unit,
    onDelete: () -> Unit,
) {
    var lock by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val originLabel = geometry.area.origin.label
        Text("위치 (영점 $originLabel 기준) · 크기", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MmField("X mm", geometry.userX(item), { onRect(it, null, null, null, lock) }, Modifier.weight(1f))
            MmField("Y mm", geometry.userY(item), { onRect(null, it, null, null, lock) }, Modifier.weight(1f))
            MmField("가로 mm", geometry.toMm(item.w), { onRect(null, null, it, null, lock) }, Modifier.weight(1f))
            MmField("세로 mm", geometry.toMm(item.h), { onRect(null, null, null, it, lock) }, Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = lock, onCheckedChange = { lock = it })
            Text("비율 유지", Modifier.clickable { lock = !lock })
            Spacer(Modifier.weight(1f))
            if (!geometry.isInside(item)) Text("작업 구역 밖은 잘립니다", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall)
        }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            RenderMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = item.mode == m,
                    onClick = { onChange { it.copy(mode = m) } },
                    shape = SegmentedButtonDefaults.itemShape(i, RenderMode.entries.size),
                ) { Text(m.label) }
            }
        }
        LabeledSlider("밝기 기준값", item.threshold.toFloat(), 1f..254f, "${item.threshold}") { v ->
            onChange { it.copy(threshold = v.roundToInt()) }
        }
        if (item.mode == RenderMode.OUTLINE) {
            LabeledSlider("선 두께", item.thickness.toFloat(), 1f..10f,
                "${item.thickness}px (${fmt(geometry.toMm(item.thickness.toFloat()))}mm)") { v ->
                onChange { it.copy(thickness = v.roundToInt()) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("색 반전")
            Spacer(Modifier.width(8.dp))
            Switch(checked = item.invert, onCheckedChange = { b -> onChange { it.copy(invert = b) } })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (item.kind == ItemKind.IMAGE) OutlinedButton(onClick = onCrop) { Text("자르기") }
            if (item.kind == ItemKind.TEXT) OutlinedButton(onClick = onEditText) { Text("글자 수정") }
            OutlinedButton(onClick = onOrigin) { Text("영점으로") }
            OutlinedButton(onClick = onCenter) { Text("가운데로") }
            OutlinedButton(onClick = onRotate) { Text("90° 회전") }
            OutlinedButton(onClick = onFront) { Text("맨 앞으로") }
            OutlinedButton(
                onClick = onDelete,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("삭제") }
        }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled,
            modifier = Modifier.heightIn(max = 36.dp))
    }
}

@Composable
fun JobPanel(
    connected: Boolean,
    status: NejeBle.Status,
    upload: NejeBle.Upload,
    busy: Boolean,
    framing: Boolean,
    supportsPwm: Boolean,
    carvingTime: Int,
    power: Int,
    presets: List<Preset>,
    activePresetId: Long?,
    onCarvingTime: (Int) -> Unit,
    onPower: (Int) -> Unit,
    onApplyPreset: (Preset) -> Unit,
    onSavePreset: () -> Unit,
    hasItems: Boolean,
    originLabel: String,
    onAlignOrigin: () -> Unit,
    onFraming: () -> Unit,
    onStartPause: () -> Unit,
    onStop: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("프리셋", style = MaterialTheme.typography.labelMedium)
            presets.forEach { p ->
                FilterChip(
                    selected = p.id == activePresetId,
                    onClick = { onApplyPreset(p) },
                    label = { Text("${p.name} (${p.carvingTime}ms/${p.power}%)") },
                )
            }
            AssistChip(onClick = onSavePreset, label = { Text("+ 현재 값 저장") })
        }
        LabeledSlider("각인 시간", carvingTime.toFloat(), 0f..100f, "$carvingTime ms") { onCarvingTime(it.roundToInt()) }
        LabeledSlider("레이저 파워", power.toFloat(), 1f..100f, "$power %", enabled = supportsPwm) { onPower(it.roundToInt()) }

        val sending = upload as? NejeBle.Upload.Sending
        when {
            sending != null -> {
                Text("이미지 전송 중 ${(sending.fraction * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(progress = { sending.fraction }, Modifier.fillMaxWidth())
            }
            upload == NejeBle.Upload.Waiting -> {
                Text("기기 응답 대기 중…", style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            status.state == MachineState.CARVING || status.state == MachineState.PAUSED -> {
                val t = status.elapsedSec
                Text("진행 %d%% · %02d:%02d".format(status.progress, t / 60, t % 60), style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(progress = { status.progress / 100f }, Modifier.fillMaxWidth())
            }
        }

        val idle = status.state != MachineState.CARVING && status.state != MachineState.PAUSED
        OutlinedButton(
            onClick = onAlignOrigin,
            enabled = hasItems && idle && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("전체를 영점($originLabel)에 맞추기") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onFraming, enabled = connected && idle && !busy, modifier = Modifier.weight(1f)) {
                Text(if (framing) "위치 확인 끄기" else "위치 확인")
            }
            Button(onClick = onStartPause, enabled = connected && !busy, modifier = Modifier.weight(1.2f)) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                else Text(
                    when (status.state) {
                        MachineState.CARVING -> "일시정지"
                        MachineState.PAUSED -> "재개"
                        else -> "각인 시작"
                    },
                )
            }
            OutlinedButton(
                onClick = onStop, enabled = connected && !idle, modifier = Modifier.weight(0.8f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("중지") }
        }
    }
}

// ------------------------------------------------------------------ 대화상자

@Composable
fun TextDialog(initial: TextSpec?, onDismiss: () -> Unit, onConfirm: (TextSpec) -> Unit) {
    var text by remember { mutableStateOf(initial?.text ?: "") }
    var size by remember { mutableStateOf(fmt(initial?.sizeMm ?: 5f)) }
    var bold by remember { mutableStateOf(initial?.bold ?: false) }
    var font by remember { mutableStateOf(initial?.font ?: FontKind.SANS) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "텍스트 추가" else "텍스트 수정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, label = { Text("내용 (여러 줄 가능)") },
                    minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = size, onValueChange = { size = it }, label = { Text("글자 크기 (mm)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FontKind.entries.forEach { f ->
                        FilterChip(selected = font == f, onClick = { font = f }, label = { Text(f.label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("굵게")
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = bold, onCheckedChange = { bold = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    val mm = size.replace(',', '.').toFloatOrNull()?.coerceIn(0.5f, 500f) ?: 5f
                    onConfirm(TextSpec(text.trimEnd(), mm, bold, font))
                },
            ) { Text("확인") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
fun PresetSaveDialog(carvingTime: Int, power: Int, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("프리셋 저장") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("각인 시간 $carvingTime ms · 레이저 파워 $power %")
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("이름 (예: 나무, 가죽, 종이)") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
fun ScanDialog(
    scanning: Boolean,
    connecting: Boolean,
    found: List<NejeBle.Found>,
    onRescan: () -> Unit,
    onPick: (NejeBle.Found) -> Unit,
    onDismiss: () -> Unit,
) {
    val sorted = found.sortedWith(compareByDescending<NejeBle.Found> { it.name == "Laser Carver" }.thenByDescending { it.rssi })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (connecting) "연결 중…" else "각인기 찾기")
                Spacer(Modifier.width(12.dp))
                if (scanning || connecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        },
        text = {
            if (sorted.isEmpty()) {
                Text(if (scanning) "주변 기기를 찾는 중입니다…\n각인기 전원이 켜져 있는지 확인하세요." else "찾은 기기가 없습니다.")
            } else {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(sorted, key = { it.address }) { d ->
                        val neje = d.name == "Laser Carver"
                        Column(
                            Modifier.fillMaxWidth().clickable(enabled = !connecting) { onPick(d) }.padding(vertical = 10.dp),
                        ) {
                            Text(d.name, fontWeight = if (neje) FontWeight.Bold else FontWeight.Normal,
                                color = if (neje) MaterialTheme.colorScheme.primary else Color.Unspecified)
                            Text("${d.address} · ${d.rssi} dBm", style = MaterialTheme.typography.labelSmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onRescan, enabled = !scanning && !connecting) { Text("다시 검색") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}
