package com.company.rtkgps.utils

class FilterComparison(private val coordinateTransform: PreciseCoordinateTransform) {
    private var comparisonCallback: ((ComparisonResult) -> Unit)? = null

    fun setComparisonCallback(cb: (ComparisonResult) -> Unit) { comparisonCallback = cb }

    fun processIMU(imu: IMUData) {}
    fun processGPS(pos: RTKPosition) {
        val fp = FilteredPosition(
            latitude = pos.latitude,
            longitude = pos.longitude,
            altitude = pos.altitude,
            accuracy = pos.accuracy,
            fixType = pos.fixType,
            timestamp = pos.timestamp,
            filterQuality = FilterQuality.GOOD
        )
        val result = ComparisonResult(fp, fp, 0.0, 0.0, System.currentTimeMillis(), coordinateTransform.getReference())
        comparisonCallback?.invoke(result)
    }
    fun processBarometer(baro: BarometerData) {}
}
