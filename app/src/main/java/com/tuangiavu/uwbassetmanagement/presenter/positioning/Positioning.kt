package com.tuannghiav.uwbassetmanagement.presenter.positioning

interface Positioning {
    fun startIMU()
    fun stopIMU()
    fun calculateLocation(byteArray: ByteArray)
    fun resetKalmanFilter()
}