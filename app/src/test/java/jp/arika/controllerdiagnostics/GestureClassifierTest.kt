package jp.arika.controllerdiagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureClassifierTest {
    @Test
    fun classifiesSwipeUp() {
        assertEquals(
            TouchGesture.SWIPE_UP,
            GestureClassifier.classify(TouchStart(0.5f, 0.8f, 100L), 0.52f, 0.3f, 500L)
        )
    }

    @Test
    fun classifiesSwipeDown() {
        assertEquals(
            TouchGesture.SWIPE_DOWN,
            GestureClassifier.classify(TouchStart(0.5f, 0.2f, 100L), 0.48f, 0.7f, 500L)
        )
    }

    @Test
    fun classifiesFastLeftToRightDiagonalStop() {
        assertEquals(
            TouchGesture.DIAGONAL_STOP_LEFT_TO_RIGHT,
            GestureClassifier.classify(TouchStart(0.05f, 0.05f, 100L), 0.95f, 0.95f, 180L)
        )
    }

    @Test
    fun classifiesRightToLeftDiagonalStop() {
        assertEquals(
            TouchGesture.DIAGONAL_STOP_RIGHT_TO_LEFT,
            GestureClassifier.classify(TouchStart(0.95f, 0.05f, 100L), 0.05f, 0.95f, 900L)
        )
    }

    @Test
    fun rejectsDiagonalStopThatTakesTooLong() {
        assertEquals(
            TouchGesture.NONE,
            GestureClassifier.classify(TouchStart(0.05f, 0.05f, 100L), 0.95f, 0.95f, 1_401L)
        )
    }

    @Test
    fun rejectsVerticalSwipeWithLargeHorizontalDrift() {
        assertEquals(
            TouchGesture.NONE,
            GestureClassifier.classify(TouchStart(0.1f, 0.8f, 100L), 0.5f, 0.2f, 500L)
        )
    }

    @Test
    fun rejectsSmallMovement() {
        assertEquals(
            TouchGesture.NONE,
            GestureClassifier.classify(TouchStart(0.5f, 0.5f, 100L), 0.55f, 0.45f, 300L)
        )
    }
}
