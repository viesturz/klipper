package mcu

import MachineDuration
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import MachineTime
import kotlinx.coroutines.launch
import machine.Reactor
import McuClock
import mcu.connection.CommandQueue
import mcu.connection.McuConnection
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.reflect.KMutableProperty0
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger("ClockSync")

// How many seconds in the future are we estimating before new sync is needed.
private val ESTIMATE_DURATION = 2.0

/** Converts MCU clock ticks into system time and back.
 *  clock = (time - timeOffset) * frequency
 *  time = clock/frequency + timeOffset
 *  */
// TODO: Maintain per-second estimate to ensure the conversion is preserved across estimate updates.
data class ClockEstimate(
    val frequency: Double, // Estimated frequency for time to clock conversion
    val timeOffset: Double, // Offset from system time.
    val lastClock: McuClock, // Last clock seen from MCU, to disambiguate 32 to 64 bit,
    val validUntil: MachineTime, // Time until this estimate is valid. No motions to be queued beyond this.
) {

    fun timeToClock(machineTime: MachineTime) = ((machineTime - timeOffset) * frequency).toULong()
    fun clockToTime(clock: McuClock) = (clock.toDouble() / frequency) + timeOffset
    fun clockToTime(clock: McuClock32) =
        (clock32ToClock64(clock).toDouble() / frequency) + timeOffset

    fun durationToClock(duration: MachineDuration): McuClock32 = (duration * frequency).toUInt()

    fun clock32ToClock64(clock32: McuClock32): McuClock {
        var clockDiff = (clock32 - lastClock).toUInt()
        clockDiff -= (clockDiff and 0x80000000u) shl 1
        return lastClock + clockDiff.toULong()
    }
}

internal class ClockSync(val mcu: McuImpl, val connection: McuConnection) {
    var estimate = ClockEstimate(0.0, 0.0, 0U, 0.0)
    private val commands = CommandQueue(connection, "ClockSync")
    private val roundtrip = RoundtripEstimator()
    private var lastClock: McuClock = 0u
    private var clockAverage = 0.0
    private var clockCovariance = 0.0
    private var timeAverage = 0.0
    private var timeVariance = 0.0
    private var predictionVariance = 0.0
    private var predictionVarianceTime = 0.0
    private var queriesPending = 0
    private var mcuFrequency = 0.0

    suspend fun start(reactor: Reactor) {
        mcuFrequency = connection.commands.identify.clockFreq.toDouble()
        val uptime = commands.sendWithResponse("get_uptime", responseUptimeParser)
        lastClock = uptime.clock
        clockAverage = uptime.clock.toDouble()
        timeAverage = uptime.sendTime
        estimate = ClockEstimate(
            frequency = mcuFrequency,
            timeOffset = timeAverage - clockAverage / mcuFrequency,
            lastClock = lastClock,
            validUntil = reactor.now
        )

        // Initial synchronization
        repeat(8) {
            delay(50.milliseconds)
            predictionVarianceTime = -9999.0
            val clock = commands.sendWithResponse("get_clock", responseClockParser)
            handleClock(clock)
        }
        logger.info { "Clock sync initialized: $estimate" }
        // Setup periodic background resync
        connection.setResponseHandler(responseClockParser, 0u, this::handleClock)
        reactor.scope.launch {
            while (true) {
                delay(0.9839.seconds) // Nice uneven delay to avoid resonances wth other periodic events.
                commands.send("get_clock")
                if (queriesPending > 4) {
                    mcu.shutdown("MCU not responding to clock sync")
                }
                queriesPending++
            }
        }
    }

    private fun handleClock(response: ResponseClock) {
        queriesPending = 0
        val newClock = estimate.clock32ToClock64(response.clock)
        val clock = newClock.toDouble()
        roundtrip.update(response.sentTime, response.receiveTime)
        // Filter out samples that are extreme outliers
        val expectedClock = (response.sentTime - timeAverage) * estimate.frequency + clockAverage
        val diff = clock - expectedClock
        logger.debug { "HandleClock: $newClock sent=${response.sentTime} receive=${response.receiveTime}, error=${diff/mcuFrequency}, halfRtt=${roundtrip.halfRoundtrip}" }
        val diffSq = diff.pow(2)
        if (diffSq > 25 * predictionVariance && diffSq > (0.000_500 * mcuFrequency).pow(2)) {
            // Outlier sample
            val stats = "${response.sentTime}: freq=${estimate.frequency} diff=$diff stdev=${
                sqrt(predictionVariance)
            }"
            if (clock > expectedClock && response.sentTime < predictionVarianceTime + 10) {
                logger.info { "Ignoring clock sample $stats" }
                return
            }
            logger.info { "Resetting prediction variance $stats" }
            predictionVariance = (0.001 * mcuFrequency).pow(2)
        } else {
            // Normal sample
            predictionVarianceTime = response.sentTime
            ::predictionVariance.updateWithDecay(diffSq)
        }
        // Add clock and sent_time to linear regression
        val difSentTime = response.sentTime - timeAverage
        timeAverage += DECAY * difSentTime
        ::timeVariance.updateWithDecay(difSentTime.pow(2))
        val diffClock = clock - clockAverage
        clockAverage += DECAY * diffClock
        ::clockCovariance.updateWithDecay(difSentTime * diffClock)
        logger.debug { "HandleClock: timeAvg=$timeAverage, clockAverage=$clockAverage difSentTime=$difSentTime, difClock=$diffClock, cov=$clockCovariance, time=$timeVariance" }
        // New prediction
        val estMcuFrequency = clockCovariance / timeVariance
        val estMcuTime = timeAverage + roundtrip.halfRoundtrip
        val estMcuClock = clockAverage
        setNewEstimate(
            ClockEstimate(
                frequency = estMcuFrequency,
                timeOffset = estMcuTime - estMcuClock / estMcuFrequency,
                lastClock = newClock,
                validUntil = response.sentTime + ESTIMATE_DURATION
            ),
            sqrt(predictionVariance)
        )
    }

    private fun setNewEstimate(newEstimate: ClockEstimate, stdev: Double) {
        val correction =
            estimate.clockToTime(newEstimate.lastClock) - newEstimate.clockToTime(newEstimate.lastClock)
        logger.debug { "Estimate update, $newEstimate, stdev=$stdev, correction = $correction" }
        estimate = newEstimate
        mcu.updateClocks(
            frequency = newEstimate.frequency,
            convTime = newEstimate.timeOffset + TRANSMIT_EXTRA,
            convClock = (-3 * stdev).toULong(),
            lastClock = estimate.lastClock
        )
    }

    fun KMutableProperty0<Double>.updateWithDecay(newValue: Double) {
        set((1.0 - DECAY) * (get() + newValue * DECAY))
    }

    companion object {
        const val DECAY = 1.0 / 30.0
        const val TRANSMIT_EXTRA = .001  // Send the messages out this earlier.
    }
}

class RoundtripEstimator {
    var halfRoundtrip = 99999999999.0
    private var lastUpdateTime = 0.0
    private val RTT_AGE = .000010 / (60.0 * 60.0) // 0.01ms per 1 hour

    fun update(sendTime: Double, receiveTime: Double) {
        // Update roundtrip estimate
        val halfRtt = 0.5 * (receiveTime - sendTime)
        val agedRtt = (sendTime - lastUpdateTime) * RTT_AGE
        if (halfRtt < halfRoundtrip + agedRtt) {
            halfRoundtrip = halfRtt
            lastUpdateTime = sendTime
            logger.debug { "New minimum rtt: $halfRtt" }
        }
    }
}

data class ResponseUptime(
    val clock: McuClock,
    val sendTime: MachineTime,
    val receiveTime: MachineTime
) : McuResponse

val responseUptimeParser = ResponseParser("uptime high=%u clock=%u") {
    ResponseUptime((parseU().toULong() shl 32) + parseU(), sentTime, receiveTime)
}

data class ResponseClock(
    val clock: McuClock32,
    val sentTime: MachineTime,
    val receiveTime: MachineTime
) : McuResponse

val responseClockParser = ResponseParser("clock clock=%u") {
    ResponseClock(parseU(), sentTime, receiveTime)
}
