package com.nejestudio.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nejestudio.MainViewModel
import com.nejestudio.ble.MachineState
import com.nejestudio.ble.NejeBle
import com.nejestudio.imaging.BitmapLoader
import com.nejestudio.model.ItemKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

private sealed interface Screen {
    data object Main : Screen
    data object Settings : Screen
    /** targetId == null 이면 새 이미지 추가 */
    data class Crop(val bitmap: Bitmap, val targetId: Long?) : Screen
}

@Composable
fun App(vm: MainViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.messages.collect {
            snackbar.currentSnackbarData?.dismiss() // 새 알림이 오면 이전 알림은 바로 닫기
            launch { snackbar.showSnackbar(it) }
        }
    }
    Box(Modifier.fillMaxSize()) {
        AppContent(vm, snackbar)
        // 알림은 화면 상단(앱 바 아래)에 표시
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 60.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppContent(vm: MainViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val geometry by vm.geometry.collectAsState()
    val items by vm.items.collectAsState()
    val previews by vm.previews.collectAsState()
    val selectedId by vm.selectedId.collectAsState()
    val conn by vm.ble.connection.collectAsState()
    val info by vm.ble.info.collectAsState()
    val status by vm.ble.status.collectAsState()
    val upload by vm.ble.upload.collectAsState()
    val deviceSetting by vm.ble.setting.collectAsState()
    val scanning by vm.ble.scanning.collectAsState()
    val found by vm.ble.found.collectAsState()
    val presets by vm.presets.collectAsState()
    val activePreset by vm.activePresetId.collectAsState()
    val carvingTime by vm.carvingTime.collectAsState()
    val power by vm.power.collectAsState()
    val framing by vm.framing.collectAsState()
    val busy by vm.busy.collectAsState()
    val axis by vm.axis.collectAsState()
    val headPos by vm.headPos.collectAsState()
    val jogStep by vm.jogStepMm.collectAsState()
    val jogging by vm.jogging.collectAsState()
    val frameArea by vm.frameArea.collectAsState()

    var screen by remember { mutableStateOf<Screen>(Screen.Main) }
    var showScan by remember { mutableStateOf(false) }
    var textDialog by remember { mutableStateOf<Long?>(null) } // -1 = 새 텍스트
    var showPresetSave by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(1) }

    LaunchedEffect(selectedId) { if (selectedId != null) tab = 0 }
    LaunchedEffect(conn) { if (conn == NejeBle.Conn.CONNECTED) showScan = false }
    // 이전에 연결했던 기기가 보이면 자동 연결
    var autoTried by remember { mutableStateOf(false) }
    LaunchedEffect(showScan) { if (showScan) autoTried = false }
    LaunchedEffect(found, showScan) {
        val last = vm.lastAddress
        if (showScan && !autoTried && conn == NejeBle.Conn.DISCONNECTED && last != null && found.any { it.address == last }) {
            autoTried = true
            vm.ble.connect(last)
        }
    }
    // 연결 중에는 화면 꺼짐 방지
    val view = LocalView.current
    DisposableEffect(conn) {
        view.keepScreenOn = conn == NejeBle.Conn.CONNECTED
        onDispose { view.keepScreenOn = false }
    }

    // ---- 블루투스 권한 / 켜기
    fun beginScan() {
        showScan = true
        vm.ble.startScan()
    }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (vm.ble.isAdapterEnabled) beginScan()
    }
    val perms = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) {
            if (vm.ble.isAdapterEnabled) beginScan()
            else enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            scope.launch { snackbar.showSnackbar("각인기와 연결하려면 블루투스 권한이 필요합니다") }
        }
    }

    // ---- 이미지 불러오기
    fun openImage(uri: Uri?) {
        if (uri == null) return
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { BitmapLoader.load(context, uri) }
            if (bmp == null) snackbar.showSnackbar("이미지를 불러올 수 없습니다")
            else screen = Screen.Crop(bmp, null)
        }
    }
    // 1) 갤러리 앱 (삼성 갤러리, 구글 포토 등)
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) openImage(r.data?.data)
    }
    // 2) 파일 앱 (다운로드 폴더, 클라우드 등)
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { openImage(it) }
    // 3) 안드로이드 사진 선택기
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { openImage(it) }
    var showImageMenu by remember { mutableStateOf(false) }

    fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK).setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        try {
            galleryLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            // 갤러리 앱이 없으면 사진 선택기로
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    when (val s = screen) {
        is Screen.Crop -> {
            CropScreen(
                bitmap = s.bitmap,
                title = if (s.targetId == null) "이미지 자르기 — 필요 없으면 '완료'" else "이미지 자르기",
                onCancel = { screen = Screen.Main },
                onDone = { bmp ->
                    if (s.targetId == null) vm.addImage(bmp) else vm.replaceSource(s.targetId, bmp)
                    screen = Screen.Main
                },
            )
            return
        }
        Screen.Settings -> {
            SettingsScreen(
                geometry = geometry,
                info = info,
                setting = deviceSetting,
                presets = presets,
                axis = axis,
                onAxis = vm::setAxis,
                onWorkArea = vm::setWorkArea,
                onDeviceSetting = vm::updateDeviceSetting,
                onDeletePreset = vm::deletePreset,
                onBack = { screen = Screen.Main },
            )
            return
        }
        Screen.Main -> Unit
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1F1F1F), titleContentColor = Color.White, actionIconContentColor = Color.White,
                ),
                title = {
                    Column {
                        Text("NEJE Studio")
                        Text(
                            statusLine(conn, info?.model, status),
                            style = MaterialTheme.typography.labelSmall, color = Color(0xFFBDBDBD),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (conn == NejeBle.Conn.DISCONNECTED) {
                        Button(onClick = { permLauncher.launch(perms) }) { Text("연결") }
                    } else {
                        TextButton(onClick = { vm.ble.disconnect() }) {
                            Text(if (conn == NejeBle.Conn.CONNECTING) "연결 취소" else "연결 해제", color = Color.White)
                        }
                    }
                    IconButton(onClick = { screen = Screen.Settings }) { Icon(Icons.Default.Settings, "설정") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            EditorCanvas(
                geometry = geometry,
                items = items,
                previews = previews,
                selectedId = selectedId,
                onSelect = vm::select,
                hitTest = vm::hitTest,
                onTransform = vm::transform,
                onResize = vm::setSize,
                modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFFB0BEC5)),
                head = headPos?.let { vm.headInArea(it) },
                frame = frameArea,
                headLabel = when {
                    conn != NejeBle.Conn.CONNECTED -> null
                    frameArea != null -> vm.frameLabel(frameArea!!)
                    headPos == null -> "헤드: 위치 모름 (영점 복귀 필요)"
                    else -> vm.headUserMm(headPos!!).let { (x, y) ->
                        "헤드  X %+.1f mm · Y %+.1f mm  ·  %s".format(x, y, if (jogging) "위치 이동 중" else "위치 확인됨")
                    }
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    FilledTonalButton(onClick = { showImageMenu = true }) { Text("이미지 추가") }
                    DropdownMenu(expanded = showImageMenu, onDismissRequest = { showImageMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("갤러리에서 선택") },
                            onClick = { showImageMenu = false; openGallery() },
                        )
                        DropdownMenuItem(
                            text = { Text("파일에서 선택") },
                            onClick = { showImageMenu = false; fileLauncher.launch("image/*") },
                        )
                        DropdownMenuItem(
                            text = { Text("사진 선택기") },
                            onClick = {
                                showImageMenu = false
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        )
                    }
                }
                FilledTonalButton(onClick = { textDialog = -1L }) { Text("텍스트 추가") }
                Spacer(Modifier.weight(1f))
                Text(
                    "%.0f×%.0fmm\n영점 %s".format(geometry.area.widthMm, geometry.area.heightMm, geometry.area.origin.label),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Surface(tonalElevation = 2.dp) {
                Column {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("개체 편집") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("각인") })
                        Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("헤드 이동") })
                    }
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 330.dp).verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                    ) {
                        if (tab == 0) {
                            val sel = items.find { it.id == selectedId }
                            if (sel == null) {
                                Text(
                                    "캔버스에서 개체를 눌러 선택하세요.\n· 한 손가락: 이동   · 두 손가락: 크기 조절\n· 파란 동그라미를 끌어 크기 조절",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            } else {
                                ItemPanel(
                                    item = sel,
                                    geometry = geometry,
                                    onRect = { x, y, w, h, lock -> vm.setUserRect(sel.id, x, y, w, h, lock) },
                                    onChange = { f -> vm.update(sel.id, f) },
                                    onCrop = { screen = Screen.Crop(sel.source, sel.id) },
                                    onEditText = { textDialog = sel.id },
                                    onRotate = { vm.rotate(sel.id) },
                                    onCenter = { vm.center(sel.id) },
                                    onOrigin = { vm.moveToOrigin(sel.id) },
                                    onFront = { vm.bringToFront(sel.id) },
                                    onDelete = { vm.delete(sel.id) },
                                )
                            }
                        } else if (tab == 2) {
                            val idle = status.state != MachineState.CARVING && status.state != MachineState.PAUSED
                            JogPanel(
                                connected = conn == NejeBle.Conn.CONNECTED,
                                enabled = idle && !busy && upload == NejeBle.Upload.Idle,
                                originLabel = geometry.area.origin.label,
                                headMm = headPos?.let { vm.headUserMm(it) },
                                moving = jogging,
                                stepMm = jogStep,
                                onStep = { vm.jogStepMm.value = it },
                                onJog = vm::jog,
                                onHome = vm::goHome,
                            )
                        } else {
                            JobPanel(
                                connected = conn == NejeBle.Conn.CONNECTED,
                                status = status,
                                upload = upload,
                                busy = busy || upload != NejeBle.Upload.Idle,
                                framing = framing,
                                supportsPwm = info?.supportsPwm ?: true,
                                carvingTime = carvingTime,
                                power = power,
                                presets = presets,
                                activePresetId = activePreset,
                                onCarvingTime = vm::setCarvingTime,
                                onPower = vm::setPower,
                                onApplyPreset = vm::applyPreset,
                                onSavePreset = { showPresetSave = true },
                                hasItems = items.isNotEmpty(),
                                originLabel = geometry.area.origin.label,
                                onAlignOrigin = vm::alignAllToOrigin,
                                onFraming = vm::toggleFraming,
                                onStartPause = vm::startOrPause,
                                onStop = vm::stop,
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- 대화상자
    if (showScan) {
        ScanDialog(
            scanning = scanning,
            connecting = conn == NejeBle.Conn.CONNECTING,
            found = found,
            onRescan = { vm.ble.startScan() },
            onPick = { vm.ble.connect(it.address) },
            onDismiss = { showScan = false; vm.ble.stopScan() },
        )
    }
    textDialog?.let { id ->
        val editing = items.find { it.id == id && it.kind == ItemKind.TEXT }
        TextDialog(
            initial = editing?.text,
            onDismiss = { textDialog = null },
            onConfirm = { spec ->
                if (editing != null) vm.updateText(editing.id, spec) else vm.addText(spec)
                textDialog = null
            },
        )
    }
    if (showPresetSave) {
        PresetSaveDialog(
            carvingTime = carvingTime, power = power,
            onDismiss = { showPresetSave = false },
            onSave = { vm.savePreset(it); showPresetSave = false },
        )
    }
    if (status.tilt) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("기울기 보호 작동") },
            text = { Text("기기가 기울어지거나 움직여 각인이 멈췄습니다. 기기를 평평하게 놓은 뒤 해제하세요.") },
            confirmButton = { TextButton(onClick = vm::resetTilt) { Text("해제") } },
        )
    }
    if (status.homing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("원점 복귀 중") },
            text = { Text("잠시 기다려 주세요…") },
            confirmButton = {},
        )
    }
}

private fun statusLine(conn: NejeBle.Conn, model: String?, s: NejeBle.Status): String = when (conn) {
    NejeBle.Conn.DISCONNECTED -> "연결 안 됨"
    NejeBle.Conn.CONNECTING -> "연결 중…"
    NejeBle.Conn.CONNECTED -> buildList {
        add(model ?: "연결됨")
        add(s.state.label)
        s.battery?.let { add("배터리 $it%") }
        s.laserTemp?.let { add("레이저 $it°C") }
    }.joinToString(" · ")
}
