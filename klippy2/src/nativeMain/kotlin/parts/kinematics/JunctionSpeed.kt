package parts.kinematics

import utils.dotProduct
import utils.magnitude
import utils.squared
import kotlin.math.min
import kotlin.math.sqrt

fun calculateJunctionSpeedSq(
    prevVector: List<Double>, nextVector: List<Double>,
    prevSpeeds: MoveSpeeds,
    nextSpeeds: MoveSpeeds): Double {
    val prevDistance = prevVector.magnitude()
    val nextDistance = nextVector.magnitude()
    val prevAccel = prevSpeeds.accelPerMm * prevDistance
    val nextAccel = nextSpeeds.accelPerMm * nextDistance
    // TODO: add instantaneous speed change.
    if (prevDistance == 0.0 || nextDistance == 0.0) return 0.0
    val straightSpeed = min(prevSpeeds.speedPerMm*prevDistance, nextSpeeds.speedPerMm*nextDistance).squared()
    val cosTheta = dotProduct(prevVector, nextVector) / (prevDistance * nextDistance)
    if (cosTheta > 0.999999) {
        // Straight line, just use max speed
        return straightSpeed
    } else if (cosTheta < -0.999999) {
        // 180 flip
        return 0.0
    }
    // lifed off from toolhead.py
    val sinThetaD2 = sqrt(0.5*(1.0+cosTheta))
    val R_jd = sinThetaD2 / (1.0 - sinThetaD2)
    // Approximated circle must contact moves no further away than mid-move
    val tanThetaD2 = sinThetaD2 / sqrt(0.5*(1.0-cosTheta))
    val prevMoveCentripetalV2 = 0.5 * prevDistance * tanThetaD2 * prevAccel
    val nextMoveCentripetalV2 = 0.5 * nextDistance * tanThetaD2 * nextAccel
    val prevJunctionDeviation = prevSpeeds.squareCornerVelocity.squared() * (sqrt(2.0) - 1.0) / prevAccel
    val nextJunctionDeviation = prevSpeeds.squareCornerVelocity.squared() * (sqrt(2.0) - 1.0) / nextAccel
    val minCentripetalV2 = min(prevMoveCentripetalV2, nextMoveCentripetalV2)
    val minJunctionV2 = min(R_jd * prevJunctionDeviation * prevAccel, R_jd * nextJunctionDeviation * nextAccel)
    return straightSpeed.coerceAtMost(minCentripetalV2).coerceAtMost(minJunctionV2)
}