package com.tuannghiav.uwbassetmanagement.model

import com.tuannghiav.uwbassetmanagement.presenter.Observer

interface Observable {
    fun addObserver(observer: Observer)
    fun deleteObserver(observer: Observer)
}