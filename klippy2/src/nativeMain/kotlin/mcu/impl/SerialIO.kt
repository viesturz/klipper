package mcu.impl

import okio.FileSystem
import okio.Path.Companion.toPath


class SerialIO(path: String) {
    val file = FileSystem.SYSTEM.openReadWrite(path.toPath(), mustExist = true)
    init {
        file.read()
    }
}