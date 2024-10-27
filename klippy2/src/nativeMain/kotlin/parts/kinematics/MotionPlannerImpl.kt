package parts.kinematics

import MachineTime
import io.github.oshai.kotlinlogging.KotlinLogging
import machine.CommandQueue
import machine.Planner
import machine.PartLifecycle
import utils.distanceTo
import kotlin.math.abs

/**
 * The move planner.
 * Input: multiple kinematically coupled submoves.
 * Step0: determine max speeds and accelerations for each submove based on axis involved.
 * Step1: determine cornering speed for each submove.
 * Step2: get joint max speed and cornering speed and distribute back to each submove.
 * Step3: compute actual speeds for a given move queue, determine number of flushable moves.
 * Step4: write out the flushable moves.
 * */
class MotionPlannerImpl(val config: MotionPlannerConfig) : MotionPlanner, PartLifecycle,
    Planner<MovePlan> {
    private val logger = KotlinLogging.logger("MotionPlanner")
    override val name = "MotionPlanner"
    override var axis: String = ""
    private val MIN_DISTANCE = 1e-5
    private val MIN_ANGLE = 1e-5
    private val axisMapping = HashMap<Char, AxisMapping>()
    private val actuatorLastMove = HashMap<MotionActuator, MovePlanActuator>()

    data class AxisMapping(
        val axis: Char,
        val actuator: MotionActuator,
        val actuatorAxis: String,
        val index: Int
    )

    override fun axisPosition(axis: Char) =
        axisMapping.getValue(axis).let { it.actuator.commandedPosition[it.index] }

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
            mapping.actuator.commandedPosition =
                mapping.actuator.commandedPosition.mapIndexed { index, current -> if (index == mapping.index) value else current }
            // TODO: schedule position set after all the moves.
        }
    }

    override fun move(queue: CommandQueue, vararg moves: KinMove) {
        if (moves.isEmpty()) return
        val plan = createPlan(moves)
        plan.calcJunctionSpeed()
        if (plan.minDuration == 0.0) {
            logger.info { "Ignoring zero distance move" }
            return
        }
        linkMovePlan(plan)
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

    /** Map the moves to the configured axis. Pull in the start positions and create a basic move plan. */
    private fun createPlan(moves: Array<out KinMove>): MovePlan {
        val actuators = ArrayList<MovePlanActuator>(moves.size + 2)
        val kinMoves = ArrayList<List<MovePlanActuator>>(moves.size)
        val plan = MovePlan(actuators, kinMoves)
        for (kMove in moves) {
            // Validate the inputs.
            if (kMove.axis.isEmpty()) throw IllegalArgumentException("Empty axis for $kMove")
            kMove.axis.indices.forEach { i ->
                val axis = axisMapping[kMove.axis[i]]
                    ?: throw IllegalArgumentException("Axis ${kMove.axis[i]} not configured")
                val actuator = axis.actuator
                val start = actuator.commandedPosition[axis.index]
                val end = kMove.position[i]
                if (!start.isFinite()) {
                    throw IllegalArgumentException("Invalid start position for $axis = $start")
                }
                if (!end.isFinite()) {
                    throw IllegalArgumentException("Invalid target position for $axis = $end")
                }
            }
            val kActuatorMoves = ArrayList<MovePlanActuator>(kMove.axis.length)
            for (i in kMove.axis.indices) {
                val axis = kMove.axis[i]
                val axisMapping =
                    axisMapping[axis] ?: throw IllegalArgumentException("Axis $axis not configured")
                val actuator = axisMapping.actuator
                val startPosition = actuator.commandedPosition
                val endPosition = ArrayList<Double>(startPosition.size)
                var distance: Double
                if (actuator.size == 1) {
                    // Simple case of one axis
                    val end = kMove.position[i]
                    endPosition.add(end)
                    distance = abs(startPosition[0] - end)
                } else {
                    // Check if already added
                    if (actuators.find { it.actuator == actuator } != null) continue
                    // Build a full set of axis for this actuator.
                    for (actuatorIndex in axisMapping.actuatorAxis.indices) {
                        val axis = axisMapping.actuatorAxis[actuatorIndex]
                        val start = startPosition[actuatorIndex]
                        val moveIndex = kMove.axis.indexOf(axis)
                        val end = if (moveIndex == -1) {
                            // No position specified, use existing position.
                            start
                        } else {
                            // We have a new position, use it
                            kMove.position[moveIndex]
                        }
                        endPosition.add(end)
                    }
                    distance = endPosition.distanceTo(startPosition)
                }
                val aSpeeds = actuator.checkMove(startPosition, endPosition)
                val aMoveSpeeds = MoveSpeeds(
                        speedPerMm = if (distance > 0) (aSpeeds.speed.coerceAtMost(kMove.speed ?: Double.MAX_VALUE) / distance) else Double.MAX_VALUE,
                        accelPerMm = if (distance > 0) aSpeeds.accel / distance else Double.MAX_VALUE,
                        minCruiseRatio = aSpeeds.minCruiseRatio,
                        squareCornerVelocity = aSpeeds.squareCornerVelocity,
                    )
                val actuatorPlan = MovePlanActuator(
                    move = plan,
                    actuator = actuator,
                    speeds = aMoveSpeeds,
                    startPosition = startPosition,
                    endPosition = endPosition,
                    distance = distance,
                )
                actuatorPlan.previous = actuatorLastMove[actuator]
                actuators.add(actuatorPlan)
                kActuatorMoves.add(actuatorPlan)
            }
            kinMoves.add(kActuatorMoves)
        }
        return plan
    }

    private fun linkMovePlan(plan: MovePlan) {
        plan.actuatorMoves.forEach { move ->
            val actuator = move.actuator
            val previous = actuatorLastMove[actuator]
            move.previous = previous
            previous?.next = move
            actuatorLastMove[actuator] = move
        }
    }

    /** Calculate end speeds for each move assuming full stop at the end of the moves.
     *  Return the number of moves that are not speed limited by the stopping. */
    private fun calculateEndSpeeds(cmds: List<MovePlan>): Int {
        // TODO: use the move tree, not the list.
        for (index in cmds.indices.reversed()) {
            val cmd = cmds[index]
            cmd.calcSpeedsBackwards()
        }
        return 0
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