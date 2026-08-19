package com.logipanel.plc

enum class PointType(val label: String, val writable: Boolean) {
    COIL("Coil (Q/M قابل للكتابة)", true),
    DISCRETE_INPUT("Discrete Input (I للقراءة فقط)", false),
    HOLDING_REGISTER("Holding Register (قابل للكتابة)", true),
    INPUT_REGISTER("Input Register (AI للقراءة فقط)", false),
}

data class PlcPoint(
    val name: String,
    val type: PointType,
    val address: Int,
    var boolValue: Boolean = false,
    var intValue: Int = 0
)
