package com.tuangiavu.uwbassetmanagement.model

import com.tuangiavu.uwbassetmanagement.presenter.Observer

interface Observable {
    fun addObserver(observer: Observer)
    fun deleteObserver(observer: Observer)
}