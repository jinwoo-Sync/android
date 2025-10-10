package com.example.myapplication.data.gps

data class IMUData(
    val accelerometer: Triple<Double, Double, Double>,
    val gyroscope: Triple<Double, Double, Double>,
    val magnetometer: Triple<Double, Double, Double>,
    val timestamp: Long
)

data class BarometerData(
    val pressure: Double,
    val altitude: Double,
    val timestamp: Long
)

// GPS Factor
data class GPSMeasurement(
    val position: DoubleArray,
    val accuracy: Double,
    val fixType: String
)

data class NavigationState(
    var position: DoubleArray = DoubleArray(3),           // UTM position (meters)
    var velocity: DoubleArray = DoubleArray(3),           // ENU velocity (m/s)
    var rotation: Array<DoubleArray> = MatrixUtils.identity(3), // ENU rotation matrix
    var accBias: DoubleArray = DoubleArray(3),            // IMU accelerometer bias
    var gyroBias: DoubleArray = DoubleArray(3),           // IMU gyroscope bias
    var timestamp: Long = 0L
) {
    fun copy(): NavigationState {
        return NavigationState(
            position.copyOf(),
            velocity.copyOf(),
            rotation.map { it.copyOf() }.toTypedArray(),
            accBias.copyOf(),
            gyroBias.copyOf(),
            timestamp
        )
    }

    fun toStateVector(): DoubleArray {
        val state = DoubleArray(15)
        System.arraycopy(position, 0, state, 0, 3)
        System.arraycopy(velocity, 0, state, 3, 3)
        val rotVec = MatrixUtils.R_to_q(rotation)
        System.arraycopy(rotVec, 1, state, 6, 3)  // Skip w component
        System.arraycopy(accBias, 0, state, 9, 3)
        System.arraycopy(gyroBias, 0, state, 12, 3)
        return state
    }

    fun fromStateVector(state: DoubleArray) {
        System.arraycopy(state, 0, position, 0, 3)
        System.arraycopy(state, 3, velocity, 0, 3)
        val rotVec = doubleArrayOf(0.0, state[6], state[7], state[8])
        rotation = MatrixUtils.q_to_R(MatrixUtils.q_normalize(rotVec))
        System.arraycopy(state, 9, accBias, 0, 3)
        System.arraycopy(state, 12, gyroBias, 0, 3)
    }
}


class GPSFactor {
    fun computeResidual(navState: NavigationState, gpsMeas: GPSMeasurement): DoubleArray {
        return doubleArrayOf(
            gpsMeas.position[0] - navState.position[0],
            gpsMeas.position[1] - navState.position[1],
            gpsMeas.position[2] - navState.position[2]
        )
    }

    fun computeJacobian(navState: NavigationState): Array<DoubleArray> {
        val H = Array(3) { DoubleArray(15) { 0.0 } }
        H[0][0] = 1.0  // ∂h/∂px
        H[1][1] = 1.0  // ∂h/∂py
        H[2][2] = 1.0  // ∂h/∂pz
        return H
    }
}

class IMUFactor {
    fun computeResidual(currentState: NavigationState,
                        refState: NavigationState,
                        preintMeas: PreintegratedImuMeasurements): DoubleArray {
        // IMU factor residual 계산 (9차원)
        val refQuat = MatrixUtils.R_to_q(refState.rotation)
        val (predPos, predVel, predQuat) = IMUPreintegration().apply {
            updateBias(currentState.accBias, currentState.gyroBias)
        }.predict(refState.position, refState.velocity, refQuat, doubleArrayOf(0.0, 0.0, -9.81))

        return doubleArrayOf(
            currentState.position[0] - predPos[0], currentState.position[1] - predPos[1], currentState.position[2] - predPos[2],
            currentState.velocity[0] - predVel[0], currentState.velocity[1] - predVel[1], currentState.velocity[2] - predVel[2],
            0.0, 0.0, 0.0  // rotation residual (simplified)
        )
    }

    fun computeJacobian(currentState: NavigationState,
                        refState: NavigationState,
                        preintMeas: PreintegratedImuMeasurements): Array<DoubleArray> {
        // IMU factor Jacobian (9x15)
        return Array(9) { DoubleArray(15) { 0.0 } }.apply {
            // Identity for position and velocity
            for (i in 0..5) this[i][i] = 1.0
        }
    }
}

class GNSSFactor {
    fun computeResidual(navState: NavigationState, gnssQuality: PositionQuality): DoubleArray {
        // GNSS quality based residual
        return doubleArrayOf(0.0) // Simplified
    }

    fun computeJacobian(navState: NavigationState): Array<DoubleArray> {
        return Array(1) { DoubleArray(15) { 0.0 } }
    }
}