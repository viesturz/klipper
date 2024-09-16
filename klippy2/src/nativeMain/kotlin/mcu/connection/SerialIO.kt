package mcu.connection

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import mcu.ConfigurationException
import platform.posix.*

private val logger = KotlinLogging.logger("SerialIO")

@OptIn(ExperimentalForeignApi::class)
fun connectPipe(path: String): Int {
    logger.info { "Connecting to Pipe $path" }
    val fd = open(path, O_RDWR or O_NOCTTY)
    if (fd == -1) {
        val error = errno
        throw ConfigurationException("Failed to open $path, status:$error")
    }
    val serialFd = fdopen(fd, "rb+")
    return fd
}

fun connectSerial(path: String, baud: Int): Int {
    logger.info { "Connecting to serial $path" }
    val fd = open(path, O_RDWR)
    if (fd == -1) {
        val error = errno
        throw ConfigurationException("Failed to open $path, status:$error")
    }
    configureSerial(fd, baud)
    return fd
}

@OptIn(ExperimentalForeignApi::class)
fun configureSerial(fd: Int, baud: Int, dataBits: Int = CS8, rts: Boolean = true) {
    // taken from https://blog.mbedded.ninja/programming/operating-systems/linux/linux-serial-ports-using-c-cpp/#reading-and-writing
    memScoped {
        val tty: termios = alloc<termios>()
        tcgetattr(fd, tty.ptr)

        tty.c_cflag = tty.c_cflag.remove(PARENB) // Clear parity bit, disabling parity (most common)
        tty.c_cflag = tty.c_cflag.remove(CSTOPB) // Clear stop field, only one stop bit used in communication (most common)
        tty.c_cflag = tty.c_cflag.remove(CSIZE) // Clear all bits that set the data size
        tty.c_cflag = tty.c_cflag.add(dataBits) // 8 bits per byte (most common)
        if (rts) {
            tty.c_cflag =
                tty.c_cflag.add(CRTSCTS) // Enable RTS/CTS hardware flow control
        } else {
            tty.c_cflag =
                tty.c_cflag.remove(CRTSCTS) // Disable RTS/CTS hardware flow control (most common)
        }
        tty.c_cflag =
            tty.c_cflag.add(CREAD or CLOCAL) // Turn on READ & ignore ctrl lines (CLOCAL = 1)

        tty.c_lflag = tty.c_lflag.remove(ICANON)
        tty.c_lflag = tty.c_lflag.remove(ECHO) // Disable echo
        tty.c_lflag = tty.c_lflag.remove(ECHOE) // Disable erasure
        tty.c_lflag = tty.c_lflag.remove(ECHONL) // Disable new-line echo
        tty.c_lflag = tty.c_lflag.remove(ISIG) // Disable interpretation of INTR, QUIT and SUSP
        tty.c_iflag = tty.c_iflag.remove(IXON or IXOFF or IXANY) // Turn off s/w flow ctrl
        tty.c_iflag =
            tty.c_iflag.remove(IGNBRK or BRKINT or PARMRK or ISTRIP or INLCR or IGNCR or ICRNL) // Disable any special handling of received bytes

        tty.c_oflag =
            tty.c_oflag.remove(OPOST) // Prevent special interpretation of output bytes (e.g. newline chars)
        tty.c_oflag =
            tty.c_oflag.remove(ONLCR) // Prevent conversion of newline to carriage return/line feed
        // tty.c_oflag &= ~OXTABS; // Prevent conversion of tabs to spaces (NOT PRESENT ON LINUX)
        // tty.c_oflag &= ~ONOEOT; // Prevent removal of C-d chars (0x004) in output (NOT PRESENT ON LINUX)

        tty.c_cc[VTIME] =
            0.toUByte()    // Wait for up to X deciseconds, returning as soon as any data is received.
        tty.c_cc[VMIN] =
            0.toUByte()  // never block endlessly. If Renogy spuriously doesn't send a response, the program would essentially stop working.

        // Set in/out baud rate
        cfsetispeed(tty.ptr, baud.toUInt())
        cfsetospeed(tty.ptr, baud.toUInt())
        tcsetattr(fd, TCSANOW, tty.ptr)
    }
}

/**
 * Returns a flag bitset with given [flag] removed. Same as `this &= ~flag`.
 */
fun UInt.remove(flag: Int): UInt = this.remove(flag.toUInt())

/**
 * Returns a flag bitset with given [flag] removed. Same as `this &= ~flag`.
 */
fun UInt.remove(flag: UInt): UInt = this and (flag.inv())

/**
 * Returns a flag bitset with given [flag] added. Same as `this |= flag`.
 */
fun UInt.add(flag: Int): UInt = this or (flag.toUInt())

/**
 * Returns a flag bitset with given [flag] added. Same as `this |= flag`.
 */
fun UInt.add(flag: UInt): UInt = this or (flag)