package parts.kinematics

import utils.dotProduct
import utils.squared
import kotlin.math.min
import kotlin.math.sqrt

fun calculateJunctionSpeedSq(
    prevVector: List<Double>, nextVector: List<Double>,
    prevDistance: Double, nextDistance: Double,
    prevSpeeds: LinearSpeeds,
    nextSpeeds: LinearSpeeds): Double {
    if (prevDistance == 0.0 || nextDistance == 0.0) return min(prevSpeeds.squareCornerVelocity, nextSpeeds.squareCornerVelocity).squared()
    var straightSpeed = min(prevSpeeds.speed, nextSpeeds.speed).squared()
    val cosTheta = dotProduct(prevVector, nextVector) / (prevDistance * nextDistance)
    if (cosTheta > 0.999999) {
        // Straight line, just use max speed
        return straightSpeed
    } else if (cosTheta < -0.999999) {
        // 180 flip
        return 0.0
    }
    // TODO: lifed off from toolhead.py, validate this.
    val sinThetaD2 = sqrt(0.5*(1.0+cosTheta))
    val R_jd = sinThetaD2 / (1.0 - sinThetaD2)
    // Approximated circle must contact moves no further away than mid-move
    val tanThetaD2 = sinThetaD2 / sqrt(0.5*(1.0-cosTheta))
    val prevMoveCentripetalV2 = 0.5 * prevDistance * tanThetaD2 * prevSpeeds.accel
    val nextMoveCentripetalV2 = 0.5 * nextDistance * tanThetaD2 * nextSpeeds.accel
    val prevJunctionDeviation = prevSpeeds.squareCornerVelocity.squared() * (sqrt(2.0) - 1.0) / prevSpeeds.accel
    val nextJunctionDeviation = prevSpeeds.squareCornerVelocity.squared() * (sqrt(2.0) - 1.0) / prevSpeeds.accel
    val minCentripetalV2 = min(prevMoveCentripetalV2, nextMoveCentripetalV2)
    val minJunctionV2 = min(R_jd * prevJunctionDeviation * prevSpeeds.accel, R_jd * nextJunctionDeviation * nextSpeeds.accel)
    return straightSpeed.coerceAtMost(minCentripetalV2).coerceAtMost(minJunctionV2)
}