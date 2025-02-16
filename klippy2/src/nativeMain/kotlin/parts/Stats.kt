package parts

import config.ValueSensor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import MachineRuntime
import PartLifecycle
import getPartsImplementing
import utils.format
import kotlin.time.Duration.Companion.seconds

class Stats: PartLifecycle {
    override val name = "Stats"
    val logger = KotlinLogging.logger("Stats")

    override suspend fun onStart(runtime: MachineRuntime) {
        runtime.reactor.launch {
            while(true) {
                delay(1.seconds)
                logStats(runtime)
            }
        }
    }

    private fun logStats(runtime: MachineRuntime) {
        logger.info {
            buildString {
                for (sensor in runtime.getPartsImplementing<ValueSensor<*>>()) {
                    append("${sensor.name}=${sensor.value.value.format(0,3)}, ")
                }
            }
        }
    }
}