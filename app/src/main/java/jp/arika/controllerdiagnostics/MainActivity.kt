package jp.arika.controllerdiagnostics

import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ControllerColorScheme = darkColorScheme(
    primary = Color(0xFF74E3FF),
    onPrimary = Color(0xFF00151C),
    primaryContainer = Color(0xFF123C49),
    onPrimaryContainer = Color(0xFFC4F3FF),
    secondary = Color(0xFFB5A2FF),
    onSecondary = Color(0xFF201442),
    secondaryContainer = Color(0xFF33265D),
    surface = Color(0xFF0B1117),
    surfaceVariant = Color(0xFF17232C),
    onSurface = Color(0xFFE7F1F5),
    onSurfaceVariant = Color(0xFFB6C7CF),
    outline = Color(0xFF536873)
)
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
            MaterialTheme(colorScheme = ControllerColorScheme) {
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
        Log.i(TAG, message)

        screenState = screenState.copy(
            status = "ボタン入力を受信: ${event.device?.name ?: event.deviceId}",
            pressedKeys = screenState.pressedKeys.run {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> this + event.keyCode
                    KeyEvent.ACTION_UP -> this - event.keyCode
                    else -> this
                }
            },
            lastKeyCode = if (event.action == KeyEvent.ACTION_DOWN) event.keyCode else screenState.lastKeyCode,
            pressedScanCodes = screenState.pressedScanCodes.run {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> this + event.scanCode
                    KeyEvent.ACTION_UP -> this - event.scanCode
                    else -> this
                }
            },
            lastScanCode = if (event.action == KeyEvent.ACTION_DOWN) event.scanCode else screenState.lastScanCode,
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
        if (shouldLog) {
            lastMotionLogTimeMs = event.eventTime
            Log.d(TAG, "MOTION source=${sourceName(event.source)} device=${event.deviceId} " + readings.joinToString { "${it.name}=${it.value.fmt()}[${it.min.fmt()}..${it.max.fmt()}]" })
        }

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
        if (shouldLogMove) Log.d(TAG, "POINTER source=${sourceName(event.source)} action=${motionActionName(event.actionMasked)} raw=(${firstPoint.rawX.fmt()},${firstPoint.rawY.fmt()}) norm=${firstPoint.normalizedText()}")

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
            lastPointerPoint = firstPoint,
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
        isFromSource(InputDevice.SOURCE_TOUCHPAD) || isFromSource(InputDevice.SOURCE_MOUSE)

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
        private const val TAG = "ControllerDiag"
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
    val lastPointerPoint: PointerPoint? = null,
    val lastAxes: List<AxisReading> = emptyList(),
    val lastGesture: TouchGesture = TouchGesture.NONE,
    val pressedKeys: Set<Int> = emptySet(),
    val lastKeyCode: Int? = null,
    val pressedScanCodes: Set<Int> = emptySet(),
    val lastScanCode: Int? = null,
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
@Composable
private fun DiagnosticsScreen(
    state: DiagnosticState,
    onRefresh: () -> Unit,
    onClearLog: () -> Unit
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("CONTROLLER LAB", fontWeight = FontWeight.Black)
                        Text("Input monitor", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusHero(state)
            RealtimeDashboard(state)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onRefresh, modifier = Modifier.weight(1f)) { Text("Refresh") }
                OutlinedButton(onClick = { showDetails = !showDetails }, modifier = Modifier.weight(1f)) {
                    Text(if (showDetails) "Hide details" else "Details")
                }
            }
            if (showDetails) {
                OutlinedButton(onClick = onClearLog, modifier = Modifier.fillMaxWidth()) { Text("Clear history") }
                DeviceCard(state.devices)
                SectionCard("Last motion event") { Text(state.lastMotion) }
                AxisCard(state.lastAxes)
                SectionCard("Last pointer event") {
                    Text(state.lastPointer, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                EventSection("Important events", state.importantEvents)
                EventSection("Pointer history", state.pointerEvents)
                EventSection("Motion history", state.motionEvents)
            }
        }
    }
}

@Composable
private fun StatusHero(state: DiagnosticState) {
    val connected = state.devices.isNotEmpty()
    val deviceName = state.devices.firstOrNull()?.name ?: "Waiting for controller"
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer)
                    )
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(99.dp),
                    color = if (connected) Color(0xFF53E69D) else MaterialTheme.colorScheme.outline
                ) {
                    Text(
                        if (connected) "LIVE" else "WAITING",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = Color(0xFF07130D),
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Text("DUALSENSE INPUT CHECK", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            Text(deviceName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text(state.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
@Composable
private fun RealtimeDashboard(state: DiagnosticState) {
    val byAxis = state.lastAxes.associateBy { it.axis }
    fun axis(code: Int): AxisReading? = byAxis[code]

    SectionCard("LIVE CONTROLS") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StickPanel("LEFT STICK", axis(MotionEvent.AXIS_X), axis(MotionEvent.AXIS_Y), Modifier.weight(1f))
            StickPanel("RIGHT STICK", axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_RTRIGGER), Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TriggerPanel("L2", axis(MotionEvent.AXIS_Z), Modifier.weight(1f))
            TriggerPanel("R2", axis(MotionEvent.AXIS_RZ), Modifier.weight(1f))
        }
        DpadPanel(axis(MotionEvent.AXIS_HAT_X), axis(MotionEvent.AXIS_HAT_Y), Modifier.fillMaxWidth())
    }
    SectionCard("BUTTON MATRIX") {
        val last = state.lastScanCode
        Text("LAST  ${last?.let(::dualSenseInputName) ?: "No input yet"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        ButtonPanel(state.pressedScanCodes, last, Modifier.fillMaxWidth())
    }
    SectionCard("TOUCH SURFACE") {
        TouchpadPanel(state.lastPointerPoint)
        val touchLabel = when {
            state.lastScanCode == DUALSENSE_TOUCHPAD_CLICK_SCAN_CODE -> "Touchpad click detected"
            state.lastPointerPoint != null -> "Pointer coordinates detected"
            else -> "Pointer coordinates unavailable on this Bluetooth connection"
        }
        Text(touchLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun RawAxisPanel(readings: List<AxisReading>) {
    val displayedAxes = listOf(
        MotionEvent.AXIS_X to "Left stick: left / right",
        MotionEvent.AXIS_Y to "Left stick: up / down",
        MotionEvent.AXIS_LTRIGGER to "Right stick: left / right",
        MotionEvent.AXIS_RTRIGGER to "Right stick: up / down",
        MotionEvent.AXIS_Z to "L2 trigger",
        MotionEvent.AXIS_RZ to "R2 trigger",
        MotionEvent.AXIS_HAT_X to "D-pad: left / right",
        MotionEvent.AXIS_HAT_Y to "D-pad: up / down"
    )
    val byAxis = readings.associateBy { it.axis }
    displayedAxes.forEach { (axis, physicalName) ->
        RawAxisMeter(byAxis[axis], "$physicalName  (${MotionEvent.axisToString(axis)})")
    }
}

@Composable
private fun RawAxisMeter(axis: AxisReading?, label: String) {
    val line = MaterialTheme.colorScheme.outline
    val active = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.surfaceVariant
    val unit = axis?.unitValue() ?: 0.5f
    Column {
        Text("$label   ${axis?.value?.fmt() ?: "No event"}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Canvas(modifier = Modifier.fillMaxWidth().height(28.dp).background(fill)) {
            val centerX = size.width / 2f
            val y = size.height / 2f
            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 3f)
            drawLine(line, Offset(centerX, 2f), Offset(centerX, size.height - 2f), strokeWidth = 2f)
            if (axis != null) drawCircle(active, radius = 10f, center = Offset(size.width * unit, y))
        }
        Text("range: ${axis?.min?.fmt() ?: "--"} to ${axis?.max?.fmt() ?: "--"}", style = MaterialTheme.typography.bodySmall)
    }
}
@Composable
private fun StickPanel(title: String, xAxis: AxisReading?, yAxis: AxisReading?, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.outline
    val active = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.surfaceVariant
    val x = xAxis?.unitValue()?.minus(0.5f) ?: 0f
    val y = yAxis?.unitValue()?.minus(0.5f) ?: 0f
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontWeight = FontWeight.Bold)
        Canvas(modifier = Modifier.size(76.dp).background(fill)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * 0.40f
            drawCircle(color = line, radius = radius, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
            drawLine(line, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), strokeWidth = 2f)
            drawLine(line, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), strokeWidth = 2f)
            drawCircle(active, radius = 12f, center = Offset(center.x + x * radius * 1.65f, center.y + y * radius * 1.65f))
        }
        Text("X ${xAxis?.value?.fmt() ?: "--"}  /  Y ${yAxis?.value?.fmt() ?: "--"}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TriggerPanel(title: String, axis: AxisReading?, modifier: Modifier = Modifier) {
    val amount = axis?.unitValue() ?: 0f
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontWeight = FontWeight.Black)
            Text("${(amount * 100).toInt()}%", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
        }
        LinearProgressIndicator(progress = { amount }, modifier = Modifier.fillMaxWidth().height(8.dp))
    }
}
@Composable
private fun DpadPanel(xAxis: AxisReading?, yAxis: AxisReading?, modifier: Modifier = Modifier) {
    val x = xAxis?.value ?: 0f
    val y = yAxis?.value ?: 0f
    Column(modifier = modifier) {
        Text("十字キー", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DirectionKey("↑", y < -0.5f)
            DirectionKey("↓", y > 0.5f)
            DirectionKey("←", x < -0.5f)
            DirectionKey("→", x > 0.5f)
        }
    }
}

@Composable
private fun DirectionKey(label: String, active: Boolean) {
    Surface(color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ButtonPanel(pressedScanCodes: Set<Int>, lastScanCode: Int?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            GameButton("\u25A1", 304, pressedScanCodes, lastScanCode)
            GameButton("\u00D7", 305, pressedScanCodes, lastScanCode)
            GameButton("\u25CB", 306, pressedScanCodes, lastScanCode)
            GameButton("\u25B3", 307, pressedScanCodes, lastScanCode)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            GameButton("L1", 308, pressedScanCodes, lastScanCode)
            GameButton("R1", 309, pressedScanCodes, lastScanCode)
            GameButton("L2", 310, pressedScanCodes, lastScanCode)
            GameButton("R2", 311, pressedScanCodes, lastScanCode)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            GameButton("L3", 314, pressedScanCodes, lastScanCode)
            GameButton("R3", 315, pressedScanCodes, lastScanCode)
            GameButton("TOUCH", DUALSENSE_TOUCHPAD_CLICK_SCAN_CODE, pressedScanCodes, lastScanCode)
        }
    }
}

private const val DUALSENSE_TOUCHPAD_CLICK_SCAN_CODE = 317

private fun dualSenseInputName(scanCode: Int): String = when (scanCode) {
    304 -> "SQUARE"
    305 -> "CROSS"
    306 -> "CIRCLE"
    307 -> "TRIANGLE"
    308 -> "L1"
    309 -> "R1"
    310 -> "L2"
    311 -> "R2"
    314 -> "L3"
    315 -> "R3"
    DUALSENSE_TOUCHPAD_CLICK_SCAN_CODE -> "TOUCHPAD"
    else -> "SCAN $scanCode"
}

@Composable
private fun GameButton(label: String, scanCode: Int, pressedScanCodes: Set<Int>, lastScanCode: Int?) {
    val active = scanCode in pressedScanCodes
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Black
        )
    }
}
@Composable
private fun TouchpadPanel(point: PointerPoint?) {
    val line = MaterialTheme.colorScheme.outline
    val active = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = Modifier.fillMaxWidth().height(64.dp).background(fill)) {
        drawLine(line, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), strokeWidth = 2f)
        drawLine(line, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = 2f)
        if (point?.normalizedX != null && point.normalizedY != null) {
            drawCircle(active, radius = 13f, center = Offset(size.width * point.normalizedX, size.height * point.normalizedY))
        }
    }
    Text(
        if (point == null) "座標: 未受信" else "生座標: (${point.rawX.fmt()}, ${point.rawY.fmt()}) / 正規化: ${point.normalizedText()}",
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall
    )
}

private data class DashboardValues(
    val profileLabel: String,
    val leftX: AxisReading?,
    val leftY: AxisReading?,
    val rightX: AxisReading?,
    val rightY: AxisReading?,
    val leftTrigger: AxisReading?,
    val rightTrigger: AxisReading?,
    val dpadX: AxisReading?,
    val dpadY: AxisReading?
)

private fun List<AxisReading>.toDashboardValues(): DashboardValues {
    fun axis(vararg candidates: Int): AxisReading? = candidates
        .asSequence()
        .mapNotNull { candidate -> firstOrNull { it.axis == candidate } }
        .firstOrNull()

    // これは LG style3 でアプリが実際に受信した MotionEvent の軸名にだけ従う。
    // カーネルの evdev 軸名や HID 生レポートとは別の、Android が変換後に公開する値である。
    return DashboardValues(
        profileLabel = "LG style3 実測: X/Y・Z/RZ・LTRIGGER/RTRIGGER",
        leftX = axis(MotionEvent.AXIS_X),
        leftY = axis(MotionEvent.AXIS_Y),
        rightX = axis(MotionEvent.AXIS_Z),
        rightY = axis(MotionEvent.AXIS_RZ),
        leftTrigger = axis(MotionEvent.AXIS_LTRIGGER),
        rightTrigger = axis(MotionEvent.AXIS_RTRIGGER),
        dpadX = axis(MotionEvent.AXIS_HAT_X),
        dpadY = axis(MotionEvent.AXIS_HAT_Y)
    )
}
private fun AxisReading.unitValue(): Float? =
    if (max <= min) null else ((value - min) / (max - min)).coerceIn(0f, 1f)

private fun axisText(label: String, axis: AxisReading?): String =
    if (axis == null) "$label: --" else "${axis.name}=${axis.value.fmt()}"

@Composable
private fun DeviceCard(devices: List<DiagnosticDevice>) = SectionCard("接続入力デバイス") {
    if (devices.isEmpty()) {
        Text("未接続。Android の Bluetooth 設定からコントローラを接続してください。")
    }
    devices.forEach { device ->
        Text("${device.name}  (ID: ${device.id})", fontWeight = FontWeight.Bold)
        Text("source=${device.sources} [0x${device.sourceMask.toString(16)}] external=${device.isExternal}")
        Text("descriptor=${device.descriptor}", style = MaterialTheme.typography.bodySmall)
        device.ranges.forEach { range ->
            Text("${range.axisName}(${range.axis}) source=${range.sourceName} range=[${range.min.fmt()}, ${range.max.fmt()}]", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AxisCard(readings: List<AxisReading>) = SectionCard("全軸の生値") {
    if (readings.isEmpty()) Text("軸イベント未受信")
    readings.forEach { axis ->
        Text("${axis.name}(${axis.axis})=${axis.value.fmt()} source=${axis.sourceName} range=[${axis.min.fmt()}, ${axis.max.fmt()}]", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EventSection(title: String, events: List<String>) {
    SectionCard(title) {
        if (events.isEmpty()) Text("履歴なし")
        events.forEach { line -> Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
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
