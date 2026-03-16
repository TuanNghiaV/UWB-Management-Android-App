package com.tuannghiav.uwbassetmanagement.presenter

import com.tuannghiav.uwbassetmanagement.model.Observable

interface Observer {
    fun onBluetoothNotEnabled(observable: Observable)
    fun onBluetoothEnabled()
    fun onBluetoothConnectionSuccess(observable: Observable, success: Boolean)
    fun onBluetoothDisconnectionSuccess(observable: Observable, success: Boolean)
    fun onBluetoothCharacteristicChange(observable: Observable, args: Any)
}