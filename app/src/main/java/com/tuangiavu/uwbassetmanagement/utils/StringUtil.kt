package com.tuannghiav.uwbassetmanagement.utils

object StringUtil {

    fun inEuropeanNotation(number: Double): String {
        return String.format("%.2f", number).replace(",", ".")
    }
}