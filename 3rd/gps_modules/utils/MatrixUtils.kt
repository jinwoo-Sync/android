package com.example.myapplication.gps_modules.utils

import org.apache.commons.math3.dfp.Dfp.copysign
import kotlin.math.*

/**
 * 행렬, 벡터, 쿼터니언 연산을 위한 유틸리티 객체입니다.
 * 중복 함수를 제거하고, 안정성 및 정밀도를 위해 더 나은 구현을 선택하여 리팩토링되었습니다.
 * 각 함수는 입력값의 유효성을 검사하여 런타임 안정성을 높였습니다.
 */
object MatrixUtils {

    // --- 기본 행렬 생성 ---

    /** n x n 단위 행렬을 생성합니다. */
    fun identity(n: Int): Array<DoubleArray> = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }

    /** 모든 요소가 0인 rows x cols 행렬을 생성합니다. */
    fun zeros(rows: Int, cols: Int): Array<DoubleArray> = Array(rows) { DoubleArray(cols) { 0.0 } }

    // --- 행렬 대수 연산 ---

    /** 두 행렬 A와 B를 더합니다. */
    fun add(A: Array<DoubleArray>, B: Array<DoubleArray>): Array<DoubleArray> {
        require(A.size == B.size && A[0].size == B[0].size) { "Matrix dimensions must agree for addition." }
        val C = Array(A.size) { DoubleArray(A[0].size) }
        for (i in A.indices) for (j in A[0].indices) C[i][j] = A[i][j] + B[i][j]
        return C
    }

    fun crossProduct(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    fun dotProduct(a: DoubleArray, b: DoubleArray): Double = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    fun vectorNorm(v: DoubleArray): Double = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    /** 행렬 A에서 행렬 B를 뺍니다. */
    fun subtract(A: Array<DoubleArray>, B: Array<DoubleArray>): Array<DoubleArray> {
        require(A.size == B.size && A[0].size == B[0].size) { "Matrix dimensions must agree for subtraction." }
        val C = Array(A.size) { DoubleArray(A[0].size) }
        for (i in A.indices) for (j in A[0].indices) C[i][j] = A[i][j] - B[i][j]
        return C
    }

    /** 단위 행렬에서 행렬 A를 뺍니다. (I - A) */
    fun subtractFromIdentity(A: Array<DoubleArray>): Array<DoubleArray> {
        require(A.size == A[0].size) { "Matrix must be square." }
        return subtract(identity(A.size), A)
    }

    /** 행렬 A의 모든 요소에 스칼라 s를 곱합니다. */
    fun scale(A: Array<DoubleArray>, s: Double): Array<DoubleArray> = A.map { row -> row.map { it * s }.toDoubleArray() }.toTypedArray()

    /**
     * 두 행렬 A와 B를 곱합니다.
     * 안정성을 위해 차원 검사를 포함합니다.
     */
    fun multiply(A: Array<DoubleArray>, B: Array<DoubleArray>): Array<DoubleArray> {
        require(A.isNotEmpty() && B.isNotEmpty() && A[0].size == B.size) {
            "Matrix dimensions are not valid for multiplication: A(${A.size}x${A[0].size}), B(${B.size}x${B[0].size})"
        }
        val C = Array(A.size) { DoubleArray(B[0].size) }
        for (i in A.indices) {
            for (j in B[0].indices) {
                for (k in B.indices) { // or A[0].indices
                    C[i][j] += A[i][k] * B[k][j]
                }
            }
        }
        return C
    }

    /**
     * 행렬 A와 벡터 v를 곱합니다.
     * 안정성을 위해 차원 검사를 포함합니다.
     */
    fun multiply(A: Array<DoubleArray>, v: DoubleArray): DoubleArray {
        require(A.isNotEmpty() && A[0].size == v.size) {
            "Matrix and vector dimensions are not valid for multiplication: A(${A.size}x${A[0].size}), v(${v.size})"
        }
        val result = DoubleArray(A.size)
        for (i in A.indices) {
            for (j in v.indices) {
                result[i] += A[i][j] * v[j]
            }
        }
        return result
    }

    /** 행렬 A의 전치 행렬을 반환합니다. */
    fun transpose(A: Array<DoubleArray>): Array<DoubleArray> {
        val result = Array(A[0].size) { DoubleArray(A.size) }
        for (i in A.indices) for (j in A[0].indices) result[j][i] = A[i][j]
        return result
    }

    /** 두 행렬 A와 B의 전치 행렬을 곱합니다. (A * B^T) */
    fun multiplyABT(A: Array<DoubleArray>, B: Array<DoubleArray>): Array<DoubleArray> = multiply(A, transpose(B))

    /**
     * 행렬 A의 역행렬을 계산합니다. (가우스-조르단 소거법과 부분 피벗팅 사용)
     * 행렬이 특이 행렬일 경우 예외를 발생시킵니다.
     */
    fun invert(A: Array<DoubleArray>): Array<DoubleArray> {
        require(A.size == A[0].size) { "Matrix must be square to be inverted." }
        val n = A.size
        val augmented = Array(n) { i -> DoubleArray(2 * n).also { System.arraycopy(A[i], 0, it, 0, n); it[i+n] = 1.0 } }
        for (i in 0 until n) {
            var maxRow = i
            for (k in i + 1 until n) if (abs(augmented[k][i]) > abs(augmented[maxRow][i])) maxRow = k
            val temp = augmented[i]; augmented[i] = augmented[maxRow]; augmented[maxRow] = temp
            val pivot = augmented[i][i]
            if(abs(pivot) < 1e-12) throw IllegalStateException("Matrix is singular and cannot be inverted.")
            for (j in i until 2 * n) augmented[i][j] /= pivot
            for (k in 0 until n) if (k != i) {
                val factor = augmented[k][i]
                for (j in i until 2 * n) augmented[k][j] -= factor * augmented[i][j]
            }
        }
        return Array(n) { i -> DoubleArray(n).also { System.arraycopy(augmented[i], n, it, 0, n) } }
    }

    /** 행렬 A를 대칭 행렬로 만듭니다. (A_ij = A_ji = (A_ij + A_ji)/2) */
    fun enforceSymmetry(A: Array<DoubleArray>) {
        require(A.size == A[0].size) { "Matrix must be square." }
        for (i in A.indices) {
            for (j in i + 1 until A.size) {
                val avg = (A[i][j] + A[j][i]) / 2.0
                A[i][j] = avg
                A[j][i] = avg
            }
        }
    }

    // --- SO(3) / Lie Group 연산 ---

    /** 3차원 벡터 v에 대한 왜대칭 행렬(skew-symmetric matrix)을 생성합니다. */
    fun skew(v: DoubleArray): Array<DoubleArray> {
        require(v.size == 3) { "Vector must have 3 elements for skew-symmetric matrix." }
        return arrayOf(doubleArrayOf(0.0,-v[2],v[1]), doubleArrayOf(v[2],0.0,-v[0]), doubleArrayOf(-v[1],v[0],0.0))
    }

    /** 회전 벡터(theta)에 대한 지수 맵(exponential map)을 계산하여 회전 행렬을 반환합니다. (로드리게스 회전 공식) */
    fun expmap(theta: DoubleArray): Array<DoubleArray> {
        require(theta.size == 3) { "Rotation vector must have 3 elements." }
        val theta_mag = sqrt(theta.sumOf { it*it })
        if (theta_mag < 1e-8) return add(identity(3), skew(theta)) // Small angle approximation
        val axis = theta.map { it / theta_mag }.toDoubleArray()
        val K = skew(axis)
        val K2 = multiply(K, K)
        val I = identity(3)
        val sin_theta = sin(theta_mag)
        val cos_theta = cos(theta_mag)
        val result = Array(3) { DoubleArray(3) }
        for (i in 0..2) for (j in 0..2) result[i][j] = I[i][j] + sin_theta * K[i][j] + (1 - cos_theta) * K2[i][j]
        return result
    }

    /** SO(3)의 역 오른쪽 자코비안(Inverse Right Jacobian)을 계산합니다. */
    fun computeHinv(theta: DoubleArray): Array<DoubleArray> {
        require(theta.size == 3) { "Rotation vector must have 3 elements." }
        val theta_mag_sq = theta.sumOf { it * it }
        if (theta_mag_sq < 1e-8) return add(identity(3), scale(skew(theta), 0.5)) // Small angle approximation
        val theta_mag = sqrt(theta_mag_sq)
        val theta_skew = skew(theta)
        val term1 = scale(theta_skew, 0.5)
        val theta_skew_sq = multiply(theta_skew, theta_skew)
        val term2_factor = (1.0 / theta_mag_sq) * (1.0 - (theta_mag / 2.0) / tan(theta_mag / 2.0))
        val term2 = scale(theta_skew_sq, term2_factor)
        return subtract(add(identity(3), term1), term2)
    }

    // --- 쿼터니언 연산 (w, x, y, z 순서) ---

    /** 두 쿼터니언 q1, q2를 곱합니다. */
    fun q_multiply(q1: DoubleArray, q2: DoubleArray): DoubleArray {
        require(q1.size == 4 && q2.size == 4) { "Quaternions must have 4 elements." }
        val w1=q1[0]; val x1=q1[1]; val y1=q1[2]; val z1=q1[3]
        val w2=q2[0]; val x2=q2[1]; val y2=q2[2]; val z2=q2[3]
        return doubleArrayOf(
            w1*w2 - x1*x2 - y1*y2 - z1*z2,
            w1*x2 + x1*w2 + y1*z2 - z1*y2,
            w1*y2 - x1*z2 + y1*w2 + z1*x2,
            w1*z2 + x1*y2 - y1*x2 + z1*w2
        )
    }

    /** 쿼터니언 q를 정규화합니다. */
    fun q_normalize(q: DoubleArray): DoubleArray {
        require(q.size == 4) { "Quaternion must have 4 elements." }
        val norm = sqrt(q.sumOf { it * it })
        return if (norm > 1e-9) q.map { it / norm }.toDoubleArray() else q
    }

    /** 축-각도(axis-angle) 표현을 쿼터니언으로 변환합니다. */
    fun q_from_axis_angle(axisAngle: DoubleArray): DoubleArray {
        require(axisAngle.size == 3) { "Axis-angle must have 3 elements." }
        val angle = sqrt(axisAngle.sumOf { it * it })
        if (angle < 1e-10) return doubleArrayOf(1.0, 0.0, 0.0, 0.0) // Zero rotation
        val sinHalf = sin(angle / 2.0)
        val cosHalf = cos(angle / 2.0)
        val axis_norm = axisAngle.map { it / angle * sinHalf }
        return doubleArrayOf(cosHalf, axis_norm[0], axis_norm[1], axis_norm[2])
    }

    /** 쿼터니언 q를 3x3 회전 행렬 R로 변환합니다. */
    fun q_to_R(q: DoubleArray): Array<DoubleArray> {
        require(q.size == 4) { "Quaternion must have 4 elements." }
        val w=q[0]; val x=q[1]; val y=q[2]; val z=q[3]
        return arrayOf(
            doubleArrayOf(1 - 2*(y*y + z*z),   2*(x*y - w*z),   2*(x*z + w*y)),
            doubleArrayOf(2*(x*y + w*z),   1 - 2*(x*x + z*z),   2*(y*z - w*x)),
            doubleArrayOf(2*(x*z - w*y),   2*(y*z + w*x),   1 - 2*(x*x + y*y))
        )
    }

    /** 3x3 회전 행렬 R을 쿼터니언으로 변환합니다. (Sheppard's method) */
    fun R_to_q(R: Array<DoubleArray>): DoubleArray {
        require(R.size == 3 && R.all { it.size == 3 }) { "Rotation matrix must be 3x3." }
        val trace = R[0][0] + R[1][1] + R[2][2]
        val q = DoubleArray(4)
        if (trace > 0) {
            val s = 0.5 / sqrt(trace + 1.0)
            q[0] = 0.25 / s
            q[1] = (R[2][1] - R[1][2]) * s
            q[2] = (R[0][2] - R[2][0]) * s
            q[3] = (R[1][0] - R[0][1]) * s
        } else {
            if (R[0][0] > R[1][1] && R[0][0] > R[2][2]) {
                val s = 2.0 * sqrt(1.0 + R[0][0] - R[1][1] - R[2][2])
                q[0] = (R[2][1] - R[1][2]) / s
                q[1] = 0.25 * s
                q[2] = (R[0][1] + R[1][0]) / s
                q[3] = (R[0][2] + R[2][0]) / s
            } else if (R[1][1] > R[2][2]) {
                val s = 2.0 * sqrt(1.0 + R[1][1] - R[0][0] - R[2][2])
                q[0] = (R[0][2] - R[2][0]) / s
                q[1] = (R[0][1] + R[1][0]) / s
                q[2] = 0.25 * s
                q[3] = (R[1][2] + R[2][1]) / s
            } else {
                val s = 2.0 * sqrt(1.0 + R[2][2] - R[0][0] - R[1][1])
                q[0] = (R[1][0] - R[0][1]) / s
                q[1] = (R[0][2] + R[2][0]) / s
                q[2] = (R[1][2] + R[2][1]) / s
                q[3] = 0.25 * s
            }
        }
        return q_normalize(q)
    }

    /** 쿼터니언을 오일러 각(롤, 피치, 요)으로 변환합니다. */
    fun q_to_euler(q: DoubleArray): Triple<Double, Double, Double> {
        require(q.size == 4) { "Quaternion must have 4 elements." }
        val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]

        // Roll (x-axis rotation)
        val sinr_cosp = 2 * (w * x + y * z)
        val cosr_cosp = 1 - 2 * (x * x + y * y)
        val roll = atan2(sinr_cosp, cosr_cosp)

        // Pitch (y-axis rotation)
        val sinp = 2 * (w * y - z * x)
        // asin의 입력값은 [-1, 1] 범위여야 하므로, 부동소수점 오류로 범위를 벗어나는 경우를 방지합니다.
        // 이를 통해 짐벌락(gimbal lock) 상황에서 안정적으로 값을 계산합니다.
        val pitch = asin(sinp.coerceIn(-1.0, 1.0))

        // Yaw (z-axis rotation)
        val siny_cosp = 2 * (w * z + x * y)
        val cosy_cosp = 1 - 2 * (y * y + z * z)
        val yaw = atan2(siny_cosp, cosy_cosp)

        return Triple(roll, pitch, yaw)
    }
}
