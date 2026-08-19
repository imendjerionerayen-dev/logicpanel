package com.logipanel.plc

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.net.SocketTimeoutException

class ModbusTcpClient(private val host: String, private val port: Int = 502, private val unitId: Int = 1) {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var transactionId: Int = 0

    val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    @Throws(Exception::class)
    fun connect(timeoutMs: Int = 4000) {
        val s = Socket()
        s.connect(java.net.InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = 3000
        s.tcpNoDelay = true
        socket = s
        input = DataInputStream(s.getInputStream())
        output = DataOutputStream(s.getOutputStream())
    }

    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null
    }

    private fun nextTransactionId(): Int {
        transactionId = (transactionId + 1) and 0xFFFF
        return transactionId
    }

    @Throws(Exception::class)
    private fun sendRequest(functionCode: Int, payload: ByteArray): ByteArray {
        val out = output ?: throw IllegalStateException("غير متصل")
        val inp = input ?: throw IllegalStateException("غير متصل")

        val tid = nextTransactionId()
        val pdu = ByteArray(1 + payload.size)
        pdu[0] = functionCode.toByte()
        System.arraycopy(payload, 0, pdu, 1, payload.size)

        val length = pdu.size + 1

        val header = ByteArray(7)
        header[0] = (tid shr 8).toByte(); header[1] = (tid and 0xFF).toByte()
        header[2] = 0; header[3] = 0
        header[4] = (length shr 8).toByte(); header[5] = (length and 0xFF).toByte()
        header[6] = unitId.toByte()

        out.write(header)
        out.write(pdu)
        out.flush()

        val respHeader = ByteArray(7)
        inp.readFully(respHeader)
        val respLength = ((respHeader[4].toInt() and 0xFF) shl 8) or (respHeader[5].toInt() and 0xFF)
        val respBody = ByteArray(respLength - 1)
        inp.readFully(respBody)

        val respFunc = respBody[0].toInt() and 0xFF
        if (respFunc and 0x80 != 0) {
            val excCode = if (respBody.size > 1) respBody[1].toInt() and 0xFF else -1
            throw Exception("خطأ Modbus من الجهاز (Exception code $excCode) لدالة $functionCode")
        }
        return respBody
    }

    private fun addr(userFacingAddress: Int): Int = userFacingAddress - 1

    @Throws(Exception::class)
    fun readCoils(startAddress: Int, quantity: Int): BooleanArray {
        val a = addr(startAddress)
        val payload = byteArrayOf((a shr 8).toByte(), (a and 0xFF).toByte(), (quantity shr 8).toByte(), (quantity and 0xFF).toByte())
        val resp = sendRequest(1, payload)
        val result = BooleanArray(quantity)
        for (i in 0 until quantity) {
            val byteIndex = 2 + (i / 8)
            val bitIndex = i % 8
            if (byteIndex < resp.size) {
                result[i] = ((resp[byteIndex].toInt() shr bitIndex) and 1) == 1
            }
        }
        return result
    }

    @Throws(Exception::class)
    fun readDiscreteInputs(startAddress: Int, quantity: Int): BooleanArray {
        val a = addr(startAddress)
        val payload = byteArrayOf((a shr 8).toByte(), (a and 0xFF).toByte(), (quantity shr 8).toByte(), (quantity and 0xFF).toByte())
        val resp = sendRequest(2, payload)
        val result = BooleanArray(quantity)
        for (i in 0 until quantity) {
            val byteIndex = 2 + (i / 8)
            val bitIndex = i % 8
            if (byteIndex < resp.size) {
                result[i] = ((resp[byteIndex].toInt() shr bitIndex) and 1) == 1
            }
        }
        return result
    }

    @Throws(Exception::class)
    fun readHoldingRegisters(startAddress: Int, quantity: Int): IntArray {
        val a = addr(startAddress)
        val payload = byteArrayOf((a shr 8).toByte(), (a and 0xFF).toByte(), (quantity shr 8).toByte(), (quantity and 0xFF).toByte())
        val resp = sendRequest(3, payload)
        val result = IntArray(quantity)
        for (i in 0 until quantity) {
            val hi = resp[2 + i * 2].toInt() and 0xFF
            val lo = resp[3 + i * 2].toInt() and 0xFF
            result[i] = (hi shl 8) or lo
        }
        return result
    }

    @Throws(Exception::class)
    fun readInputRegisters(startAddress: Int, quantity: Int): IntArray {
        val a = addr(startAddress)
        val payload = byteArrayOf((a shr 8).toByte(), (a and 0xFF).toByte(), (quantity shr 8).toByte(), (quantity and 0xFF).toByte())
        val resp = sendRequest(4, payload)
        val result = IntArray(quantity)
        for (i in 0 until quantity) {
            val hi = resp[2 + i * 2].toInt() and 0xFF
            val lo = resp[3 + i * 2].toInt() and 0xFF
            result[i] = (hi shl 8) or lo
        }
        return result
    }

    @Throws(Exception::class)
    fun writeSingleCoil(address: Int, value: Boolean) {
        val a = addr(address)
        val v = if (value) 0xFF00 else 0x0000
        val payload = byteArrayOf((a shr 8).toByte(), (a and 0xFF).toByte(), (v shr 8).toByte(), (v and 0xFF).toByte())
        sendRequest(5, payload)
    }

    @Throws(Exception::class)
    fun writeSingleRegister(address: Int, value: Int) {
        val a = addr(address)
        val payload = byteArrayOf((a shr 8).toByte(), (a and 0xFF).toByte(), (value shr 8).toByte(), (value and 0xFF).toByte())
        sendRequest(6, payload)
    }
}
