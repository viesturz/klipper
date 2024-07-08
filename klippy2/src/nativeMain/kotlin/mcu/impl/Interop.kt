package mcu.impl

import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
class GcWrapper<Typename: CPointed> @OptIn(ExperimentalForeignApi::class) constructor(
    private var _ptr: CPointer<Typename>?,
    private val destructor: (ptr: CPointer<Typename>) -> Unit,
) {
    val ptr: CPointer<Typename>
        get() = _ptr ?: throw RuntimeException("Native pointer use after free")

    // Called on GC
    protected fun finalize() = free()

    fun free() {
        _ptr?.let {
            destructor(it)
            _ptr = null
        }
    }
}