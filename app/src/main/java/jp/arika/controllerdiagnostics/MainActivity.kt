package jp.arika.controllerdiagnostics

import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PS4 / PS5 コントローラが Android へ公開する入力を調べる診断アプリ。
 * Bluetooth のペアリングは Android OS に任せ、ここでは標準入力イベントだけを観測する。
 */
class MainActivity : ComponentActivity(), InputManager.InputDeviceListener {
    private lateinit var inputManager: InputManager
    private var screenState by mutableStateOf(DiagnosticState())
    private var activeTouch: TouchStart? = null
    private var lastMotionLogTimeMs = Long.MIN_VALUE
    private var lastPointerMoveLogTimeMs = Long.MIN_VALUE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inputManager = getSystemService(InputManager::class.java)
        refreshDevices("アプリを開始しました")
        setContent {
            MaterialTheme {
                DiagnosticsScreen(
                    state = screenState,
                    onRefresh = { refreshDevices("入力デバイス一覧を更新しました") },
                    onClearLog = {
                        screenState = screenState.copy(
                            importantEvents = emptyList(),
                            pointerEvents = emptyList(),
                            motionEvents = emptyList()
                        )
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        refreshDevices("前面に戻りました")
    }

    override fun onPause() {
        inputManager.unregisterInputDeviceListener(this)
        super.onPause()
    }

    override fun onInputDeviceAdded(deviceId: Int) = refreshDevices("デバイス接続: $deviceId")
    override fun onInputDeviceRemoved(deviceId: Int) = refreshDevices("デバイス切断: $deviceId")
    override fun onInputDeviceChanged(deviceId: Int) = refreshDevices("デバイス変更: $deviceId")

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        var handled = false

        if (event.isControllerMotionEvent()) {
            recordMotion(event)
            handled = true
        }

        if (event.isPointerDiagnosticEvent()) {
            recordPointerEvent(event)
            handled = true
        }

        // コントローラ操作で Compose のボタンや画面スクロールが誤作動しないよう消費する。
        return if (handled) true else super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.isPointerDiagnosticEvent()) {
            recordPointerEvent(event)
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!event.device.isController()) return super.dispatchKeyEvent(event)

        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            KeyEvent.ACTION_MULTIPLE -> "MULTIPLE"
            else -> event.action.toString()
        }
        val message = buildString {
            append("KEY $action  ")
            append(KeyEvent.keyCodeToString(event.keyCode))
            append("  device=")
            append(event.deviceId)
            append(" source=")
            append(sourceName(event.source))
            append(" scan=")
            append(event.scanCode)
            append(" repeat=")
            append(event.repeatCount)
        }
        screenState = screenState.copy(
            status = "ボタン入力を受信: ${event.device?.name ?: event.deviceId}",
            importantEvents = prependEvent(screenState.importantEvents, message, MAX_IMPORTANT_EVENTS)
        )
        return true
    }

    private fun refreshDevices(message: String) {
        val devices = inputManager.inputDeviceIds
            .mapNotNull(InputDevice::getDevice)
            .filter(InputDevice::isDiagnosticDevice)
            .map(DiagnosticDevice::from)
            .sortedBy { it.id }

        screenState = screenState.copy(
            devices = devices,
            status = message,
            importantEvents = prependEvent(screenState.importantEvents, message, MAX_IMPORTANT_EVENTS)
        )
    }

    private fun recordMotion(event: MotionEvent) {
        val readings = AxisReading.from(event)
        val shouldLog = shouldRecord(
            nowMs = event.eventTime,
            previousMs = lastMotionLogTimeMs,
            intervalMs = MOTION_LOG_INTERVAL_MS
        )
        if (shouldLog) lastMotionLogTimeMs = event.eventTime

        val summary = readings
            .filter { it.axis in SUMMARY_AXES }
            .joinToString("  ") { "${it.name}=${it.value.fmt()}" }
            .ifEmpty { "公開軸なし" }

        screenState = screenState.copy(
            lastAxes = readings,
            lastMotion = "${motionActionName(event.actionMasked)} / ${sourceName(event.source)} / device=${event.deviceId}",
            status = "軸入力を受信: ${event.device?.name ?: event.deviceId}",
            motionEvents = if (shouldLog) {
                prependEvent(
                    screenState.motionEvents,
                    "MOTION ${motionActionName(event.actionMasked)} source=${sourceName(event.source)} $summary",
                    MAX_MOTION_EVENTS
                )
            } else {
                screenState.motionEvents
            }
        )
    }

    private fun recordPointerEvent(event: MotionEvent) {
        val firstPoint = event.pointerPoint(0)
        val pointerSummary = (0 until event.pointerCount).joinToString(" | ") { index ->
            val point = event.pointerPoint(index)
            "id=${event.getPointerId(index)} tool=${toolTypeName(event.getToolType(index))} " +
                "raw=(${point.rawX.fmt()},${point.rawY.fmt()}) " +
                "norm=${point.normalizedText()} p=${event.getPressure(index).fmt()}"
        }
        val message =
            "POINTER ${motionActionName(event.actionMasked)} device=${event.deviceId} " +
                "source=${sourceName(event.source)} count=${event.pointerCount} buttons=0x${event.buttonState.toString(16)} " +
                pointerSummary

        val isMove = event.actionMasked == MotionEvent.ACTION_MOVE ||
            event.actionMasked == MotionEvent.ACTION_HOVER_MOVE
        val shouldLogMove = !isMove || shouldRecord(
            nowMs = event.eventTime,
            previousMs = lastPointerMoveLogTimeMs,
            intervalMs = POINTER_MOVE_LOG_INTERVAL_MS
        )
        if (isMove && shouldLogMove) lastPointerMoveLogTimeMs = event.eventTime

        var gesture = screenState.lastGesture
        var importantEvents = screenState.importantEvents

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeTouch = firstPoint.toTouchStart(event.eventTime)
                if (activeTouch == null) {
                    importantEvents = prependEvent(
                        importantEvents,
                        "タッチ開始を受信しましたが、座標レンジがなく正規化できません",
                        MAX_IMPORTANT_EVENTS
                    )
                }
            }

            MotionEvent.ACTION_UP -> {
                val start = activeTouch
                activeTouch = null
                val endX = firstPoint.normalizedX
                val endY = firstPoint.normalizedY
                if (start != null && endX != null && endY != null) {
                    gesture = GestureClassifier.classify(start, endX, endY, event.eventTime)
                    importantEvents = prependEvent(
                        importantEvents,
                        "GESTURE ${gesture.label}",
                        MAX_IMPORTANT_EVENTS
                    )
                } else {
                    importantEvents = prependEvent(
                        importantEvents,
                        "タッチ終了（開始イベントまたは正規化座標なし）",
                        MAX_IMPORTANT_EVENTS
                    )
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                activeTouch = null
                importantEvents = prependEvent(
                    importantEvents,
                    "POINTER CANCEL: ジェスチャー判定を破棄しました",
                    MAX_IMPORTANT_EVENTS
                )
            }
        }

        screenState = screenState.copy(
            status = "ポインタ入力を受信: ${event.device?.name ?: event.deviceId}",
            lastPointer = message,
            lastGesture = gesture,
            importantEvents = importantEvents,
            pointerEvents = if (shouldLogMove) {
                prependEvent(screenState.pointerEvents, message, MAX_POINTER_EVENTS)
            } else {
                screenState.pointerEvents
            }
        )
    }

    private fun MotionEvent.pointerPoint(index: Int): PointerPoint {
        val rawX = getX(index)
        val rawY = getY(index)
        val xRange = device?.getMotionRange(MotionEvent.AXIS_X, source)
            ?: device?.getMotionRange(MotionEvent.AXIS_X)
        val yRange = device?.getMotionRange(MotionEvent.AXIS_Y, source)
            ?: device?.getMotionRange(MotionEvent.AXIS_Y)
        return PointerPoint(
            rawX = rawX,
            rawY = rawY,
            normalizedX = rawX.normalizeWith(xRange),
            normalizedY = rawY.normalizeWith(yRange),
            xRange = xRange?.let(MotionRangeInfo::from),
            yRange = yRange?.let(MotionRangeInfo::from)
        )
    }

    private fun MotionEvent.isControllerMotionEvent(): Boolean =
        device.isController() &&
            (isFromSource(InputDevice.SOURCE_JOYSTICK) || isFromSource(InputDevice.SOURCE_GAMEPAD))

    private fun MotionEvent.isPointerDiagnosticEvent(): Boolean =
        isFromSource(InputDevice.SOURCE_TOUCHPAD) ||
            (device?.isExternal == true && isFromSource(InputDevice.SOURCE_MOUSE))

    private fun Float.normalizeWith(range: InputDevice.MotionRange?): Float? {
        if (range == null || range.range == 0f) return null
        return ((this - range.min) / range.range).coerceIn(0f, 1f)
    }

    private fun prependEvent(events: List<String>, message: String, maxEvents: Int): List<String> =
        (listOf(eventLine(message)) + events).take(maxEvents)

    private fun eventLine(message: String): String = "${timeFormatter.format(Date())}  $message"

    private fun shouldRecord(nowMs: Long, previousMs: Long, intervalMs: Long): Boolean =
        previousMs == Long.MIN_VALUE || nowMs < previousMs || nowMs - previousMs >= intervalMs

    companion object {
        private const val MAX_IMPORTANT_EVENTS = 80
        private const val MAX_POINTER_EVENTS = 60
        private const val MAX_MOTION_EVENTS = 30
        private const val MOTION_LOG_INTERVAL_MS = 100L
        private const val POINTER_MOVE_LOG_INTERVAL_MS = 50L

        private val SUMMARY_AXES = setOf(
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_Y,
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_RX,
            MotionEvent.AXIS_RY,
            MotionEvent.AXIS_LTRIGGER,
            MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_BRAKE,
            MotionEvent.AXIS_GAS,
            MotionEvent.AXIS_HAT_X,
            MotionEvent.AXIS_HAT_Y
        )

        private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.JAPAN)
    }
}

private data class DiagnosticState(
    val devices: List<DiagnosticDevice> = emptyList(),
    val status: String = "コントローラを待機中",
    val lastMotion: String = "未受信",
    val lastPointer: String = "未受信",
    val lastAxes: List<AxisReading> = emptyList(),
    val lastGesture: TouchGesture = TouchGesture.NONE,
    val importantEvents: List<String> = emptyList(),
    val pointerEvents: List<String> = emptyList(),
    val motionEvents: List<String> = emptyList()
)

private data class DiagnosticDevice(
    val id: Int,
    val name: String,
    val descriptor: String,
    val sources: String,
    val sourceMask: Int,
    val isExternal: Boolean,
    val ranges: List<MotionRangeInfo>
) {
    companion object {
        fun from(device: InputDevice): DiagnosticDevice = DiagnosticDevice(
            id = device.id,
            name = device.name,
            descriptor = device.descriptor,
            sources = sourceName(device.sources),
            sourceMask = device.sources,
            isExternal = device.isExternal,
            ranges = device.motionRanges
                .map(MotionRangeInfo::from)
                .distinctBy { it.axis to it.source }
                .sortedWith(compareBy<MotionRangeInfo> { it.source }.thenBy { it.axis })
        )
    }
}

private data class MotionRangeInfo(
    val axis: Int,
    val axisName: String,
    val source: Int,
    val sourceName: String,
    val min: Float,
    val max: Float,
    val flat: Float,
    val fuzz: Float,
    val resolution: Float
) {
    companion object {
        fun from(range: InputDevice.MotionRange): MotionRangeInfo = MotionRangeInfo(
            axis = range.axis,
            axisName = MotionEvent.axisToString(range.axis),
            source = range.source,
            sourceName = sourceName(range.source),
            min = range.min,
            max = range.max,
            flat = range.flat,
            fuzz = range.fuzz,
            resolution = range.resolution
        )
    }
}

private data class AxisReading(
    val axis: Int,
    val name: String,
    val source: Int,
    val sourceName: String,
    val value: Float,
    val min: Float,
    val max: Float,
    val flat: Float,
    val fuzz: Float,
    val resolution: Float
) {
    companion object {
        fun from(event: MotionEvent): List<AxisReading> = event.device?.motionRanges
            .orEmpty()
            .map { range ->
                AxisReading(
                    axis = range.axis,
                    name = MotionEvent.axisToString(range.axis),
                    source = range.source,
                    sourceName = sourceName(range.source),
                    value = event.getAxisValue(range.axis),
                    min = range.min,
                    max = range.max,
                    flat = range.flat,
                    fuzz = range.fuzz,
                    resolution = range.resolution
                )
            }
            .distinctBy { it.axis to it.source }
            .sortedWith(compareBy<AxisReading> { it.source }.thenBy { it.axis })
    }
}

private data class PointerPoint(
    val rawX: Float,
    val rawY: Float,
    val normalizedX: Float?,
    val normalizedY: Float?,
    val xRange: MotionRangeInfo?,
    val yRange: MotionRangeInfo?
) {
    fun normalizedText(): String = if (normalizedX != null && normalizedY != null) {
        "(${normalizedX.fmt()},${normalizedY.fmt()})"
    } else {
        "不可"
    }

    fun toTouchStart(timeMs: Long): TouchStart? {
        val x = normalizedX ?: return null
        val y = normalizedY ?: return null
        return TouchStart(x, y, timeMs)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun DiagnosticsScreen(
    state: DiagnosticState,
    onRefresh: () -> Unit,
    onClearLog: () -> Unit
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Controller Input Diagnostics") }) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Text(state.status, style = MaterialTheme.typography.bodyMedium) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRefresh) { Text("再検索") }
                    OutlinedButton(onClick = onClearLog) { Text("履歴を消去") }
                }
            }
            item { DeviceCard(state.devices) }
            item {
                SectionCard("最後のモーションイベント") {
                    Text(state.lastMotion)
                }
            }
            item { AxisCard(state.lastAxes) }
            item {
                SectionCard("最後のポインタイベント") {
                    Text(state.lastPointer, fontFamily = FontFamily.Monospace)
                }
            }
            item {
                SectionCard("タッチパッド・ジェスチャー") {
                    Text("最後の判定: ${state.lastGesture.label}", fontWeight = FontWeight.Bold)
                    Text(
                        "TOUCHPAD、または外部MOUSEとして届くイベントを監視します。" +
                            "座標レンジが取得できない場合は生座標だけを表示し、誤ったジェスチャー判定は行いません。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            item { EventSection("重要イベント（新しい順）", state.importantEvents) }
            item { EventSection("ポインタ履歴（MOVEは50 ms間引き）", state.pointerEvents) }
            item { EventSection("モーション履歴（100 ms間引き）", state.motionEvents) }
        }
    }
}

@androidx.compose.runtime.Composable
private fun DeviceCard(devices: List<DiagnosticDevice>) = SectionCard("接続入力デバイス") {
    if (devices.isEmpty()) {
        Text("未接続。Android の Bluetooth 設定からコントローラを接続してください。")
    }
    devices.forEach { device ->
        Text("${device.name}  (ID: ${device.id})", fontWeight = FontWeight.Bold)
        Text("source=${device.sources} [0x${device.sourceMask.toString(16)}] external=${device.isExternal}")
        Text("descriptor=${device.descriptor}", style = MaterialTheme.typography.bodySmall)
        if (device.ranges.isEmpty()) {
            Text("公開されているモーション軸なし", style = MaterialTheme.typography.bodySmall)
        } else {
            device.ranges.forEach { range ->
                Text(
                    "${range.axisName}(${range.axis}) source=${range.sourceName} " +
                        "range=[${range.min.fmt()}, ${range.max.fmt()}] flat=${range.flat.fmt()} " +
                        "fuzz=${range.fuzz.fmt()} res=${range.resolution.fmt()}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun AxisCard(readings: List<AxisReading>) = SectionCard("全軸の生値") {
    if (readings.isEmpty()) {
        Text("軸イベント未受信")
    }
    readings.forEach { axis ->
        Text(
            "${axis.name}(${axis.axis})=${axis.value.fmt()} source=${axis.sourceName} " +
                "range=[${axis.min.fmt()}, ${axis.max.fmt()}] flat=${axis.flat.fmt()} " +
                "fuzz=${axis.fuzz.fmt()} res=${axis.resolution.fmt()}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@androidx.compose.runtime.Composable
private fun EventSection(title: String, events: List<String>) {
    SectionCard(title) {
        if (events.isEmpty()) Text("履歴なし")
        events.forEach { line ->
            Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@androidx.compose.runtime.Composable
private fun SectionCard(
    title: String,
    content: @androidx.compose.runtime.Composable () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

private fun InputDevice?.isController(): Boolean {
    val source = this?.sources ?: return false
    return source.hasSource(InputDevice.SOURCE_GAMEPAD) || source.hasSource(InputDevice.SOURCE_JOYSTICK)
}

private fun InputDevice.isDiagnosticDevice(): Boolean =
    isController() ||
        sources.hasSource(InputDevice.SOURCE_TOUCHPAD) ||
        (isExternal && sources.hasSource(InputDevice.SOURCE_MOUSE))

private fun Int.hasSource(expected: Int): Boolean = this and expected == expected

private fun Float.fmt(): String = String.format(Locale.US, "%.3f", this)

private fun motionActionName(action: Int): String = when (action) {
    MotionEvent.ACTION_DOWN -> "DOWN"
    MotionEvent.ACTION_UP -> "UP"
    MotionEvent.ACTION_MOVE -> "MOVE"
    MotionEvent.ACTION_CANCEL -> "CANCEL"
    MotionEvent.ACTION_OUTSIDE -> "OUTSIDE"
    MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
    MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
    MotionEvent.ACTION_HOVER_MOVE -> "HOVER_MOVE"
    MotionEvent.ACTION_SCROLL -> "SCROLL"
    MotionEvent.ACTION_HOVER_ENTER -> "HOVER_ENTER"
    MotionEvent.ACTION_HOVER_EXIT -> "HOVER_EXIT"
    MotionEvent.ACTION_BUTTON_PRESS -> "BUTTON_PRESS"
    MotionEvent.ACTION_BUTTON_RELEASE -> "BUTTON_RELEASE"
    else -> action.toString()
}

private fun toolTypeName(toolType: Int): String = when (toolType) {
    MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
    MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
    MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
    MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
    MotionEvent.TOOL_TYPE_UNKNOWN -> "UNKNOWN"
    else -> toolType.toString()
}

private fun sourceName(source: Int): String = buildList {
    if (source.hasSource(InputDevice.SOURCE_GAMEPAD)) add("GAMEPAD")
    if (source.hasSource(InputDevice.SOURCE_JOYSTICK)) add("JOYSTICK")
    if (source.hasSource(InputDevice.SOURCE_TOUCHPAD)) add("TOUCHPAD")
    if (source.hasSource(InputDevice.SOURCE_MOUSE)) add("MOUSE")
    if (source.hasSource(InputDevice.SOURCE_KEYBOARD)) add("KEYBOARD")
    if (source.hasSource(InputDevice.SOURCE_TOUCHSCREEN)) add("TOUCHSCREEN")
    if (source.hasSource(InputDevice.SOURCE_DPAD)) add("DPAD")
}.ifEmpty { listOf("0x${source.toString(16)}") }.joinToString("|")
