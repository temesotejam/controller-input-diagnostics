package jp.arika.controllerdiagnostics

internal data class TouchStart(
    val x: Float,
    val y: Float,
    val timeMs: Long
)

internal enum class TouchGesture(val label: String) {
    NONE("なし"),
    SWIPE_UP("上スワイプ（速度 +5% 候補）"),
    SWIPE_DOWN("下スワイプ（速度 -5% 候補）"),
    DIAGONAL_STOP_LEFT_TO_RIGHT("対角急停止: 左上→右下"),
    DIAGONAL_STOP_RIGHT_TO_LEFT("対角急停止: 右上→左下")
}

/**
 * 正規化済み（0.0～1.0）のタッチ座標からジェスチャーを判定する。
 * ACTION_CANCEL は呼び出し側で破棄し、このクラスには渡さない。
 */
internal object GestureClassifier {
    fun classify(
        start: TouchStart,
        endX: Float,
        endY: Float,
        endTimeMs: Long
    ): TouchGesture {
        val dx = endX - start.x
        val dy = endY - start.y
        val duration = endTimeMs - start.timeMs

        val diagonalStop =
            duration in 0L..MAX_DIAGONAL_DURATION_MS &&
                start.y <= DIAGONAL_START_MAX_Y &&
                endY >= DIAGONAL_END_MIN_Y &&
                kotlin.math.abs(dx) >= DIAGONAL_MIN_DELTA &&
                kotlin.math.abs(dy) >= DIAGONAL_MIN_DELTA

        if (diagonalStop) {
            return if (dx > 0f) {
                TouchGesture.DIAGONAL_STOP_LEFT_TO_RIGHT
            } else {
                TouchGesture.DIAGONAL_STOP_RIGHT_TO_LEFT
            }
        }

        val verticalSwipe =
            kotlin.math.abs(dy) >= VERTICAL_MIN_DELTA &&
                kotlin.math.abs(dx) < VERTICAL_MAX_HORIZONTAL_DRIFT

        if (verticalSwipe) {
            return if (dy < 0f) TouchGesture.SWIPE_UP else TouchGesture.SWIPE_DOWN
        }

        return TouchGesture.NONE
    }

    private const val DIAGONAL_START_MAX_Y = 0.20f
    private const val DIAGONAL_END_MIN_Y = 0.80f
    private const val DIAGONAL_MIN_DELTA = 0.60f
    private const val MAX_DIAGONAL_DURATION_MS = 1_200L
    private const val VERTICAL_MIN_DELTA = 0.30f
    private const val VERTICAL_MAX_HORIZONTAL_DRIFT = 0.25f
}
