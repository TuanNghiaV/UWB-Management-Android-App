package com.tuannghiav.uwbassetmanagement.presenter.positioning

interface KalmanFilter {
    fun configure(initialLocationData: LocationData)
    fun predict(accelerationData: AccelerationData)
    fun update(locationData: LocationData, accelerationData: AccelerationData, orientationData: OrientationData)
}