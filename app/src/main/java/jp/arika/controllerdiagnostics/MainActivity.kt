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
import androidx.compose.foundation.lazy.items
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
import kotlin.math.abs

/**
 * 第1段階用の診断アプリです。Bluetooth の接続・切断は Android OS が担当し、
 * この Activity は OS から配送される入力イベントを表示するだけです。
 */
class MainActivity : ComponentActivity(), InputManager.InputDeviceListener {
    private lateinit var inputManager: InputManager
    private var screenState by mutableStateOf(DiagnosticState())
    private var activeTouch: TouchStart? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inputManager = getSystemService(InputManager::class.java)
        refreshControllers("アプリを開始しました")
        setContent {
            MaterialTheme {
                DiagnosticsScreen(
                    state = screenState,
                    onRefresh = { refreshControllers("コントローラ一覧を更新しました") },
                    onClearLog = { screenState = screenState.copy(events = emptyList()) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        refreshControllers("前面に戻りました")
    }

    override fun onPause() {
        inputManager.unregisterInputDeviceListener(this)
        super.onPause()
    }

    override fun onInputDeviceAdded(deviceId: Int) = refreshControllers("デバイス接続: $deviceId")
    override fun onInputDeviceRemoved(deviceId: Int) = refreshControllers("デバイス切断: $deviceId")
    override fun onInputDeviceChanged(deviceId: Int) = refreshControllers("デバイス変更: $deviceId")

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.device.isController()) {
            recordMotion(event)
            // 機種によってはタッチパッドが通常の touch event ではなく generic motion として届く。
            if (event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) recordTouchpadEvent(event)
        }
        // Android の通常のイベント配送を妨げない。診断表示だけを更新する。
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // 端末画面ではなく、コントローラが TOUCHPAD ソースとして送ったイベントだけを判定する。
        if (event.device.isController() && event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) {
            recordTouchpadEvent(event)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.device.isController()) {
            val action = if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
            appendEvent("KEY $action  ${KeyEvent.keyCodeToString(event.keyCode)}  source=${sourceName(event.source)}")
        }
        return super.dispatchKeyEvent(event)
    }

    private fun refreshControllers(message: String) {
        val controllers = inputManager.inputDeviceIds
            .mapNotNull { id -> InputDevice.getDevice(id) }
            .filter { device -> device.isController() }
            .map { ControllerDevice(it.id, it.name, sourceName(it.sources), it.sources) }
        screenState = screenState.copy(
            controllers = controllers,
            status = message,
            events = listOf(eventLine(message)) + screenState.events
        )
    }

    private fun recordMotion(event: MotionEvent) {
        val axes = AxisValues.from(event)
        screenState = screenState.copy(
            lastAxes = axes,
            lastMotion = "${motionActionName(event.actionMasked)} / ${sourceName(event.source)}",
            status = "入力を受信: ${event.device.name}"
        )
        appendEvent(
            "MOTION ${motionActionName(event.actionMasked)}  source=${sourceName(event.source)}  " +
                "X=${axes.x.fmt()} Y=${axes.y.fmt()} L2=${axes.leftTrigger.fmt()} R2=${axes.rightTrigger.fmt()}"
        )
    }

    private fun recordTouchpadEvent(event: MotionEvent) {
        val x = event.getX(0)
        val y = event.getY(0)
        val normalized = event.normalizedPoint(x, y)
        val now = event.eventTime
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeTouch = TouchStart(normalized.first, normalized.second, now)
                appendEvent("TOUCHPAD DOWN  x=${normalized.first.fmt()} y=${normalized.second.fmt()}")
            }
            MotionEvent.ACTION_MOVE -> {
                appendEvent("TOUCHPAD MOVE  x=${normalized.first.fmt()} y=${normalized.second.fmt()}")
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val start = activeTouch
                activeTouch = null
                if (start == null) {
                    appendEvent("TOUCHPAD UP（開始イベントなし）")
                    return
                }
                val gesture = GestureClassifier.classify(start, normalized.first, normalized.second, now)
                screenState = screenState.copy(lastGesture = gesture)
                appendEvent(
                    "TOUCHPAD UP  x=${normalized.first.fmt()} y=${normalized.second.fmt()}  " +
                        "→ ${gesture.label}"
                )
            }
        }
    }

    private fun appendEvent(message: String) {
        screenState = screenState.copy(events = (listOf(eventLine(message)) + screenState.events).take(MAX_EVENTS))
    }

    private fun eventLine(message: String): String = "${timeFormatter.format(Date())}  $message"

    private fun MotionEvent.normalizedPoint(x: Float, y: Float): Pair<Float, Float> {
        val xRange = device?.getMotionRange(MotionEvent.AXIS_X, source)
            ?: device?.getMotionRange(MotionEvent.AXIS_X)
        val yRange = device?.getMotionRange(MotionEvent.AXIS_Y, source)
            ?: device?.getMotionRange(MotionEvent.AXIS_Y)
        fun normalize(value: Float, range: InputDevice.MotionRange?): Float =
            if (range == null || range.range == 0f) value else ((value - range.min) / range.range).coerceIn(0f, 1f)
        return normalize(x, xRange) to normalize(y, yRange)
    }

    private fun InputDevice?.isController(): Boolean {
        val source = this?.sources ?: return false
        return (source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) ||
            (source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
    }

    companion object {
        private const val MAX_EVENTS = 40
        private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.JAPAN)
    }
}

private data class DiagnosticState(
    val controllers: List<ControllerDevice> = emptyList(),
    val status: String = "コントローラを待機中",
    val lastMotion: String = "未受信",
    val lastAxes: AxisValues = AxisValues(),
    val lastGesture: TouchGesture = TouchGesture.NONE,
    val events: List<String> = emptyList()
)

private data class ControllerDevice(val id: Int, val name: String, val sources: String, val sourceMask: Int)

private data class AxisValues(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val rz: Float = 0f,
    val rx: Float = 0f,
    val ry: Float = 0f,
    val leftTrigger: Float = 0f,
    val rightTrigger: Float = 0f,
    val brake: Float = 0f,
    val gas: Float = 0f,
    val hatX: Float = 0f,
    val hatY: Float = 0f
) {
    companion object {
        fun from(event: MotionEvent) = AxisValues(
            x = event.getAxisValue(MotionEvent.AXIS_X), y = event.getAxisValue(MotionEvent.AXIS_Y),
            z = event.getAxisValue(MotionEvent.AXIS_Z), rz = event.getAxisValue(MotionEvent.AXIS_RZ),
            rx = event.getAxisValue(MotionEvent.AXIS_RX), ry = event.getAxisValue(MotionEvent.AXIS_RY),
            leftTrigger = event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            rightTrigger = event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            brake = event.getAxisValue(MotionEvent.AXIS_BRAKE), gas = event.getAxisValue(MotionEvent.AXIS_GAS),
            hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X), hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        )
    }
}

private data class TouchStart(val x: Float, val y: Float, val timeMs: Long)

private enum class TouchGesture(val label: String) {
    NONE("なし"),
    SWIPE_UP("上スワイプ（速度 +5% 候補）"),
    SWIPE_DOWN("下スワイプ（速度 -5% 候補）"),
    DIAGONAL_STOP_LEFT_TO_RIGHT("対角急停止: 左上→右下"),
    DIAGONAL_STOP_RIGHT_TO_LEFT("対角急停止: 右上→左下")
}

/** 設計書の初期しきい値。ここでは値を変えず、認識結果だけを表示する。 */
private object GestureClassifier {
    fun classify(start: TouchStart, endX: Float, endY: Float, endTimeMs: Long): TouchGesture {
        val dx = endX - start.x
        val dy = endY - start.y
        val duration = endTimeMs - start.timeMs
        val diagonal = start.y <= 0.20f && endY >= 0.80f && abs(dx) >= 0.60f && abs(dy) >= 0.60f &&
            duration in 150L..1200L
        if (diagonal) return if (dx > 0f) TouchGesture.DIAGONAL_STOP_LEFT_TO_RIGHT else TouchGesture.DIAGONAL_STOP_RIGHT_TO_LEFT
        if (abs(dy) >= 0.30f && abs(dx) < 0.25f) return if (dy < 0f) TouchGesture.SWIPE_UP else TouchGesture.SWIPE_DOWN
        return TouchGesture.NONE
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun DiagnosticsScreen(state: DiagnosticState, onRefresh: () -> Unit, onClearLog: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Controller Input Diagnostics") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Text(state.status, style = MaterialTheme.typography.bodyMedium) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRefresh) { Text("再検索") }
                    OutlinedButton(onClick = onClearLog) { Text("履歴を消去") }
                }
            }
            item { SectionCard("接続コントローラ") {
                if (state.controllers.isEmpty()) Text("未接続。Android の Bluetooth 設定からコントローラを接続してください。")
                state.controllers.forEach { device ->
                    Text("${device.name}  (ID: ${device.id})", fontWeight = FontWeight.Bold)
                    Text("source: ${device.sources}  [0x${device.sourceMask.toString(16)}]")
                }
            } }
            item { SectionCard("最後のモーションイベント") { Text(state.lastMotion) } }
            item { AxisCard(state.lastAxes) }
            item { SectionCard("タッチパッド・ジェスチャー") {
                Text("最後の判定: ${state.lastGesture.label}", fontWeight = FontWeight.Bold)
                Text("TOUCHPAD source の DOWN/MOVE/UP を検出した場合のみ判定します。マウスとして届く場合は履歴で source=MOUSE を確認してください。", style = MaterialTheme.typography.bodySmall)
            } }
            item { Text("イベント履歴（新しい順）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(state.events) { line -> Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@androidx.compose.runtime.Composable
private fun AxisCard(axes: AxisValues) = SectionCard("軸の生値") {
    AxisRow("左スティック候補", "X=${axes.x.fmt()}  Y=${axes.y.fmt()}")
    AxisRow("右スティック候補 A", "Z=${axes.z.fmt()}  RZ=${axes.rz.fmt()}")
    AxisRow("右スティック候補 B", "RX=${axes.rx.fmt()}  RY=${axes.ry.fmt()}")
    AxisRow("L2 / R2", "LTRIGGER=${axes.leftTrigger.fmt()}  RTRIGGER=${axes.rightTrigger.fmt()}")
    AxisRow("トリガー代替候補", "BRAKE=${axes.brake.fmt()}  GAS=${axes.gas.fmt()}")
    AxisRow("十字キー", "HAT_X=${axes.hatX.fmt()}  HAT_Y=${axes.hatY.fmt()}")
}

@androidx.compose.runtime.Composable
private fun AxisRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, fontWeight = FontWeight.Bold)
        Text(value, fontFamily = FontFamily.Monospace)
    }
}

@androidx.compose.runtime.Composable
private fun SectionCard(title: String, content: @androidx.compose.runtime.Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

private fun Float.fmt(): String = String.format(Locale.US, "%.3f", this)

private fun motionActionName(action: Int): String = when (action) {
    MotionEvent.ACTION_DOWN -> "DOWN"
    MotionEvent.ACTION_UP -> "UP"
    MotionEvent.ACTION_MOVE -> "MOVE"
    MotionEvent.ACTION_CANCEL -> "CANCEL"
    MotionEvent.ACTION_HOVER_MOVE -> "HOVER_MOVE"
    else -> action.toString()
}

private fun sourceName(source: Int): String = buildList {
    if (source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) add("GAMEPAD")
    if (source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) add("JOYSTICK")
    if (source and InputDevice.SOURCE_TOUCHPAD == InputDevice.SOURCE_TOUCHPAD) add("TOUCHPAD")
    if (source and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE) add("MOUSE")
    if (source and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD) add("KEYBOARD")
}.ifEmpty { listOf("0x${source.toString(16)}") }.joinToString("|")
