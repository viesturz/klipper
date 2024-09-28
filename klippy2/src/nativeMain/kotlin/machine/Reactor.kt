package machine

import MachineTime
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
actual fun getNow(): MachineTime = chelper.get_monotonic()