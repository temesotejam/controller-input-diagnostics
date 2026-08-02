package jp.arika.controllerdiagnostics

/** Kotlin 標準ライブラリにない IntArray 向けの mapNotNull。 */
internal inline fun <R : Any> IntArray.mapNotNull(transform: (Int) -> R?): List<R> {
    val result = ArrayList<R>()
    for (value in this) {
        transform(value)?.let(result::add)
    }
    return result
}
