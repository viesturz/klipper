package parts.kinematics

import MachineTime
import io.github.oshai.kotlinlogging.KotlinLogging
import machine.CommandQueue
import machine.Planner
import machine.PartLifecycle
import utils.magnitude
import utils.squared
import kotlin.math.sqrt

/**
 * The move planner.
 * Input: multiple kinematically coupled submoves.
 * Step0: determine max speeds and accelerations for each submove based on axis involved.
 * Step1: determine cornering speed for each submove.
 * Step2: get joint max speed and cornering speed and distribute back to each submove.
 * Step3: compute actual speeds for a given move queue, determine number of flushable moves.
 * Step4: write out the flushable moves.
 * */
class MotionPlannerImpl(val config: MotionPlannerConfig): MotionPlanner, PartLifecycle,
    Planner<MovePlan> {
    private val logger = KotlinLogging.logger("MotionPlanner")
    override val name = "MotionPlanner"
    override var axis: String = ""
    private val MIN_DISTANCE = 1e-5
    private val MIN_ANGLE = 1e-5
    private val axisMapping = HashMap<Char, AxisMapping>()
    private val actuatorLastMove = HashMap<MotionActuator, MovePlanActuator>()

    data class AxisMapping(val axis: Char, val actuator: MotionActuator, val actuatorAxis: String, val index: Int)

    override fun axisPosition(axis: Char) = axisMapping.getValue(axis).let { it.actuator.commandedPosition[it.index] }

    init {
        val axisBuilder = StringBuilder()
        for (kin in config.mapping) {
            val allAxis = kin.key
            if (allAxis.length != kin.value.size) throw IllegalArgumentException("Axis $allAxis not matching the number of dimensions ${kin.value.size}")
            for (i in allAxis.indices) {
                val axis = allAxis[i]
                axisMapping[axis] = AxisMapping(axis, kin.value, allAxis, i)
            }
            axisBuilder.append(kin.key)
        }
        axis = axisBuilder.toString()
    }

    override fun configureAxis(vararg axisMap: Pair<String, MotionActuator>) {
        TODO("Not yet implemented")
    }

    override fun initializePosition(axis: String, position: Position) {
        for (i in axis.indices) {
            val mapping = axisMapping[axis[i]] ?: throw RuntimeException("No axis for $axis")
            val value = position[i]
            mapping.actuator.commandedPosition = mapping.actuator.commandedPosition.mapIndexed { index,current -> if (index == mapping.index) value else current }
            // TODO: schedule position set after all the moves.
        }
    }

    override fun move(queue: CommandQueue, vararg moves: KinMove) {
        if (moves.isEmpty()) return
        val plan = createPlan(moves)
        applyMaxSpeeds(plan)
        if (plan.minDuration == 0.0) {
            logger.info { "Ignoring zero distance move" }
            return
        }
        linkMovePlan(plan)
        calcMaxJunctionSpeed(plan)
        queue.addPlanned(this, plan)
    }

    override fun tryPlan(
        startTime: MachineTime,
        cmds: List<MovePlan>,
        force: Boolean
    ): List<MachineTime>? {
        var movesReady = calculateEndSpeeds(cmds)
        if (force) movesReady = cmds.size

        if (movesReady == 0) return null
        val result = ArrayList<MachineTime>(movesReady)

        var time = startTime
        for (i in 0..<movesReady) {
            time = outputMove(time, cmds[i])
            result.add(time)
        }
        return result
    }

    /** Create a basic move plan structure. */
    private fun createPlan(moves: Array<out KinMove>): MovePlan {
        val kinMoves = ArrayList<MovePlanKin>(moves.size)
        val plan = MovePlan(kinMoves)
        for (kMove in moves) {
            if (kMove.axis.isEmpty()) throw IllegalArgumentException("Empty axis for $kMove")
            val actuators = ArrayList<MovePlanActuator>(1)
            val planKin = MovePlanKin(
                actuators,
                speeds = LinearSpeeds(speed = kMove.speed ?: Double.MAX_VALUE,
                accel = Double.MAX_VALUE,
                minCruiseRatio = 0.0,
                squareCornerVelocity = 1e10,
            ))
            kinMoves.add(planKin)
            for (i in kMove.axis.indices) {
                val axis = kMove.axis[i]
                val axisMapping = axisMapping[axis] ?: throw IllegalArgumentException("Axis $axis not configured")
                val actuator = axisMapping.actuator
                val moveActuator = if (actuator.size == 1) {
                    // Simple case of one axis
                    val start = actuator.commandedPosition[0]
                    val end = kMove.position[i]
                    if (!start.isFinite()) {
                        throw IllegalArgumentException("Invalid start position for $axis = $start")
                    }
                    if (!end.isFinite()) {
                        throw IllegalArgumentException("Invalid target position for $axis = $end")
                    }
                    MovePlanActuator(
                        movePlan = plan,
                        kin = planKin,
                        actuator = actuator,
                        startPosition = listOf(start),
                        endPosition = listOf(end),
                    )
                } else {
                    // Check if already added
                    if (actuators.find { it.actuator == actuator } != null) continue
                    // Build a full set of axis for this actuator.
                    val startPosition = actuator.commandedPosition
                    val endPosition = ArrayList<Double>(axisMapping.actuatorAxis.length)
                    for (actuatorIndex in axisMapping.actuatorAxis.indices) {
                        val axis = axisMapping.actuatorAxis[actuatorIndex]
                        val start = startPosition[actuatorIndex]
                        if (!start.isFinite()) {
                            throw IllegalArgumentException("Invalid start position for $axis = $start")
                        }
                        val moveIndex = kMove.axis.indexOf(axis)
                        val end = if (moveIndex == -1) {
                            // No position specified, use existing position.
                            start
                        } else {
                            // We have a new position, use it
                            kMove.position[moveIndex].also {
                                if (!it.isFinite()) throw IllegalArgumentException("Invalid target position for $axis = $it")
                            }
                        }
                        endPosition.add(end)
                    }
                    MovePlanActuator(
                        movePlan = plan,
                        kin = planKin,
                        actuator = actuator,
                        startPosition = startPosition,
                        endPosition = endPosition,
                    )
                }
                actuators.add(moveActuator)
            }
        }
        return plan
    }

    private fun linkMovePlan(plan: MovePlan) {
        plan.kinMoves.forEach { kinMove -> kinMove.actuatorMoves.forEach { move ->
                val actuator = move.actuator
                val previous = actuatorLastMove[actuator]
                move.previous = previous
                previous?.next = move
                actuatorLastMove[actuator] = move
            }
        }
    }

    /** Apply maximum speeds and accelerations. */
    private fun applyMaxSpeeds(plan: MovePlan) {
        var minDuration = 0.0
        for (move in plan.kinMoves) {
            var distanceSq = 0.0
            // Get total distance
            for (aMove in move.actuatorMoves) {
                var adistanceSq = 0.0
                for (i in aMove.startPosition.indices) {
                    adistanceSq += (aMove.startPosition[i] - aMove.endPosition[i]).squared()
                }
                aMove.distance = sqrt(adistanceSq)
                distanceSq += adistanceSq
            }
            move.distance = sqrt(distanceSq)
            // Query for limits
            for (aMove in move.actuatorMoves) {
                val speeds = aMove.actuator.checkMove(aMove.startPosition, aMove.endPosition)
                if (aMove.distance < MIN_DISTANCE) continue
                move.speeds.limit(speeds.scale(aMove.distance / move.distance))
                // TODO: handle angular speeds separately.
            }
            if (move.distance < MIN_DISTANCE) continue
            move.minDuration = move.distance / move.speeds.speed
            minDuration = minDuration.coerceAtLeast(move.minDuration)
        }
        plan.minDuration = minDuration
    }

    /** Calculate end speeds for each move assuming full stop at the end of the moves.
     *  Return the number of moves that are not speed limited by the stopping. */
    private fun calculateEndSpeeds(cmds: List<MovePlan>): Int {
        for (index in cmds.indices.reversed()) {
            val cmd = cmds[index]
            calcSpeedsBackwards(cmd)
        }
        return 0
    }

    /** Calculate maximum junction speed for each kin move. */
    internal fun calcMaxJunctionSpeed(plan: MovePlan) {
        for (move in plan.kinMoves) {
            val v1 = ArrayList<Double>(2)
            val v2 = ArrayList<Double>(2)
            val prevMoveSpeeds = LinearSpeeds()
            for (aMove in move.actuatorMoves) {
                v2.addAll(aMove.startPosition.zip(aMove.endPosition) { s, e -> e-s})
                val prev = aMove.previous
                if (prev != null && prev.distance > 0.0) {
                    v1.addAll(prev.startPosition.zip(aMove.startPosition) { s, e -> e-s})
                    prevMoveSpeeds.limit( prev.kin.speeds.scale(prev.distance / prev.kin.distance) )
                } else {
                    v1.addAll(aMove.startPosition)
                }
            }
            move.maxJunctionSpeedSq = calculateJunctionSpeedSq(v1, v2, v1.magnitude(), move.distance, prevMoveSpeeds, move.speeds)
            for (aMove in move.actuatorMoves) {
                aMove.kin.maxJunctionSpeedSq = move.maxJunctionSpeedSq * aMove.distance / move.distance
            }
        }
    }

    /** Calculate junction speeds for the move, processing backwards from end to start. */
    internal fun calcSpeedsBackwards(plan: MovePlan) {
        var endSpeed = 1e10
        var startSpeed = 1e10
        for (move in plan.kinMoves) {
            if (move.distance == 0.0) {
                continue
            }
            var kEndSpeedSq = 0.0
            for (aMove in move.actuatorMoves) {
                val next = aMove.next
                if (next != null && next.distance > 0) {
                    aMove.endSpeedSq = next.startSpeedSq
                    kEndSpeedSq += next.startSpeedSq
                }
                // TODO: the actuator end speeds need to be aligned to match overall speed proportion.
            }

            move.endSpeedSq = kEndSpeedSq
            move.startSpeedSq = (kEndSpeedSq + 2.0 * move.distance * move.speeds.accel)
                .coerceAtMost(move.speeds.speed.squared())
                .coerceAtMost(move.maxJunctionSpeedSq)

            endSpeed += move.endSpeedSq * move.distance
            startSpeedSq += move.startSpeedSq * move.distance
        }

        // Align the speeds to sync the axis.
        for (move in plan.kinMoves) {

        }
    }

    private fun outputMove(startTime: Double, move: MovePlan): MachineTime {
        return startTime
    }

    private fun outputActuator(plan: MovePlan, aPlan: MovePlanActuator) {
        val motion = aPlan.actuator
        if (aPlan.startPosition != motion.commandedPosition) {
            motion.initializePosition(plan.startTime, aPlan.startPosition)
        }
//
//        // Do a basic trapezoid
//        val distance = aPlan.distance
//        val duration = plan.endTime - plan.startTime
//        val accel = aPlan.accel
//        val speed = min(aPlan.speed, distance / accel)
//        val accelDuration = speed / accel
//        val accelDist = accelDuration * accel * 0.5
//        val coastDist = distance.absoluteValue - accelDist * 2
//        plan.motion.moveTo(
//            plan.startTime + accelDuration,
//            plan.startPosition + distance.sign * accelDist,
//            speed
//        )
//        plan.motion.moveTo(
//            plan.endTime - accelDuration,
//            plan.endPosition - distance.sign * accelDist,
//            speed
//        )
//        plan.motion.moveTo(plan.endTime, plan.endPosition, plan.endSpeed)
//        // axis.flush(time + totalDuration)
    }
}

private fun LinearSpeeds.scale(scale: Double) = LinearSpeeds(
    speed = speed * scale,
    accel = accel * scale,
    minCruiseRatio = minCruiseRatio,
    squareCornerVelocity = squareCornerVelocity,
)

private fun LinearSpeeds.limit(speeds: LinearSpeeds) {
    speed = speed.coerceAtMost(speeds.speed)
    accel = accel.coerceAtMost(speeds.accel)
    minCruiseRatio = minCruiseRatio.coerceAtLeast(speeds.minCruiseRatio)
    squareCornerVelocity = squareCornerVelocity.coerceAtMost(speeds.squareCornerVelocity)
}

/** Storage class for planning a single move. */
data class MovePlan(
    var kinMoves: List<MovePlanKin>,
    var minDuration: Double = 0.0,
    var startTime: Double = 0.0,
    var endTime: Double = 0.0,
)

data class MovePlanKin(
    val actuatorMoves: List<MovePlanActuator>,
    var speeds: LinearSpeeds,
    var distance: Double = 0.0,
    var minDuration: Double = 0.0,
    /** Maximum cornering speed squared, assuming can accelerate to max cruise speed. */
    var maxJunctionSpeedSq: Double = 0.0,
    /** Move start and end speeds. */
    var startSpeedSq: Double = 0.0,
    var endSpeedSq: Double = 0.0,
)

data class MovePlanActuator(
    val movePlan: MovePlan,
    val kin: MovePlanKin,
    val actuator: MotionActuator,
    val startPosition: List<Double>,
    val endPosition: List<Double>,
) {
    var distance: Double = 0.0
    var previous: MovePlanActuator? = null
    var next: MovePlanActuator? = null
    /** Maximum cornering speed squared, assuming can accelerate to max cruise speed. */
    var maxJunctionSpeedSq: Double = 0.0
    /** Move start and end speeds. */
    var startSpeedSq: Double = 0.0
    var endSpeedSq: Double = 0.0
}

//val MOVE_BATCH_TIME = 0.500
//val STEPCOMPRESS_FLUSH_TIME = 0.050
//val SDS_CHECK_TIME = 0.001 /// step+dir+step filter in stepcompress.c
//val MOVE_HISTORY_EXPIRE = 30.0
//
//
//var lastFlushTime = 0.0
//val printTime = 0.0
//var flushDelay = SDS_CHECK_TIME
//
//fun advanceMoveTime(time: MachineTime) {
//    val pt_delay = flushDelay + STEPCOMPRESS_FLUSH_TIME
//    val flush_time = max(lastFlushTime, self.print_time - pt_delay)
//    self.print_time = max(self.print_time, next_print_time)
//    val want_flush_time = max(flush_time, self.print_time - pt_delay)
//    while (true) {
//        flush_time = min(flush_time + MOVE_BATCH_TIME, want_flush_time)
//        self._advance_flush_time(flush_time)
//        if (flush_time >= want_flush_time) break
//    }
//}
//
//private fun advanceFlushTime(flushTime: MachineTime) {
//    val flush_time = max(flushTime, lastFlushTime)
//    // Generate steps via itersolve
//    val sg_flush_want = min(flush_time + STEPCOMPRESS_FLUSH_TIME,  printTime - kin_flush_delay)
//    val sg_flush_time = max(sg_flush_want, flush_time)
//    for (sg in self.step_generators) {
//        sg(sg_flush_time)
//    }
//    min_restart_time = max(min_restart_time, sg_flush_time)
//    // Free trapq entries that are no longer needed
//
//    val clear_history_time = flush_time - MOVE_HISTORY_EXPIRE
//    val free_time = sg_flush_time - flushDelay
//    trapq_finalize_moves(self.trapq, free_time, clear_history_time)
//    extruder.update_move_time(free_time, clear_history_time)
//    //  Flush stepcompress and mcu steppersync
//    for (m in self.all_mcus) {
//        m.flush_moves(flush_time, clear_history_time)
//    }
//    lastFlushTime = flush_time
//}