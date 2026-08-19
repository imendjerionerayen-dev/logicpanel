package com.logipanel.plc

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var ipInput: EditText
    private lateinit var portInput: EditText
    private lateinit var unitIdInput: EditText
    private lateinit var connectBtn: Button
    private lateinit var addPointBtn: Button
    private lateinit var statusText: TextView
    private lateinit var pointsContainer: LinearLayout

    private var client: ModbusTcpClient? = null
    private val points = mutableListOf<PlcPoint>()
    private val rowViews = mutableMapOf<String, View>()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipInput = findViewById(R.id.ipInput)
        portInput = findViewById(R.id.portInput)
        unitIdInput = findViewById(R.id.unitIdInput)
        connectBtn = findViewById(R.id.connectBtn)
        addPointBtn = findViewById(R.id.addPointBtn)
        statusText = findViewById(R.id.statusText)
        pointsContainer = findViewById(R.id.pointsContainer)

        loadPrefs()
        rebuildPointsUI()

        connectBtn.setOnClickListener { toggleConnection() }
        addPointBtn.setOnClickListener { showAddPointDialog() }
    }

    private fun toggleConnection() {
        val c = client
        if (c != null && c.isConnected) {
            pollJob?.cancel()
            scope.launch(Dispatchers.IO) { c.disconnect() }
            client = null
            statusText.text = "غير متصل"
            statusText.setTextColor(Color.parseColor("#FF5D5D"))
            connectBtn.text = "اتصال"
            return
        }

        val ip = ipInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull() ?: 502
        val unitId = unitIdInput.text.toString().trim().toIntOrNull() ?: 1
        if (ip.isEmpty()) {
            Toast.makeText(this, "أدخل عنوان IP لجهاز LOGO!", Toast.LENGTH_SHORT).show()
            return
        }
        savePrefs()

        statusText.text = "جارٍ الاتصال بـ $ip:$port ..."
        statusText.setTextColor(Color.parseColor("#FFB020"))

        scope.launch {
            val newClient = ModbusTcpClient(ip, port, unitId)
            val ok = withContext(Dispatchers.IO) {
                try { newClient.connect(); true } catch (e: Exception) {
                    lastError = e.message ?: "خطأ غير معروف"; false
                }
            }
            if (ok) {
                client = newClient
                statusText.text = "متصل ✓ ($ip:$port)"
                statusText.setTextColor(Color.parseColor("#33E28A"))
                connectBtn.text = "قطع الاتصال"
                startPolling()
            } else {
                statusText.text = "فشل الاتصال: $lastError"
                statusText.setTextColor(Color.parseColor("#FF5D5D"))
            }
        }
    }

    private var lastError: String = ""

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val c = client
                if (c != null && c.isConnected) {
                    for (p in points) {
                        try {
                            withContext(Dispatchers.IO) {
                                when (p.type) {
                                    PointType.COIL -> p.boolValue = c.readCoils(p.address, 1)[0]
                                    PointType.DISCRETE_INPUT -> p.boolValue = c.readDiscreteInputs(p.address, 1)[0]
                                    PointType.HOLDING_REGISTER -> p.intValue = c.readHoldingRegisters(p.address, 1)[0]
                                    PointType.INPUT_REGISTER -> p.intValue = c.readInputRegisters(p.address, 1)[0]
                                }
                            }
                            updateRow(p)
                        } catch (e: Exception) {
                        }
                    }
                }
                delay(1000)
            }
        }
    }

    private fun showAddPointDialog() {
        val ctx = this
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        val nameInput = EditText(ctx).apply { hint = "اسم (مثلا Q1)" }
        val addrInput = EditText(ctx).apply {
            hint = "العنوان كما في LOGO! (يبدأ من 1)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val typeSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, PointType.values().map { it.label })
        }
        layout.addView(nameInput)
        layout.addView(addrInput)
        layout.addView(typeSpinner)

        AlertDialog.Builder(ctx)
            .setTitle("إضافة نقطة PLC")
            .setView(layout)
            .setPositiveButton("إضافة") { _, _ ->
                val name = nameInput.text.toString().trim()
                val addr = addrInput.text.toString().trim().toIntOrNull()
                if (name.isEmpty() || addr == null) {
                    Toast.makeText(ctx, "أدخل اسم وعنوان صحيحين", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val type = PointType.values()[typeSpinner.selectedItemPosition]
                points.add(PlcPoint(name, type, addr))
                savePrefs()
                rebuildPointsUI()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun rebuildPointsUI() {
        pointsContainer.removeAllViews()
        rowViews.clear()
        for (p in points) buildRow(p)
    }

    private fun buildRow(p: PlcPoint) {
        val ctx = this
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 20, 0, 20)
        }
        val label = TextView(ctx).apply {
            text = "${p.name}\n${p.type.label} #${p.address}"
            setTextColor(Color.parseColor("#DBE4EC"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(label)

        when (p.type) {
            PointType.COIL -> {
                val sw = Switch(ctx)
                sw.isChecked = p.boolValue
                sw.setOnCheckedChangeListener { btn, checked ->
                    if (!btn.isPressed) return@setOnCheckedChangeListener
                    val c = client
                    if (c == null || !c.isConnected) {
                        Toast.makeText(ctx, "غير متصل", Toast.LENGTH_SHORT).show()
                        sw.isChecked = p.boolValue
                        return@setOnCheckedChangeListener
                    }
                    scope.launch(Dispatchers.IO) {
                        try { c.writeSingleCoil(p.address, checked); p.boolValue = checked }
                        catch (e: Exception) {
                            withContext(Dispatchers.Main) { Toast.makeText(ctx, "فشل الكتابة: ${e.message}", Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
                row.addView(sw)
                rowViews[p.name] = sw
            }
            PointType.DISCRETE_INPUT -> {
                val ind = TextView(ctx).apply {
                    text = if (p.boolValue) "● 1" else "○ 0"
                    setTextColor(if (p.boolValue) Color.parseColor("#33E28A") else Color.parseColor("#6B7A8A"))
                    textSize = 14f
                }
                row.addView(ind)
                rowViews[p.name] = ind
            }
            PointType.HOLDING_REGISTER -> {
                val valText = TextView(ctx).apply {
                    text = p.intValue.toString()
                    setTextColor(Color.parseColor("#FFB020"))
                    setPadding(10, 0, 10, 0)
                }
                val editBtn = Button(ctx).apply {
                    text = "كتابة"
                    setOnClickListener {
                        val input = EditText(ctx).apply { setText(p.intValue.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
                        AlertDialog.Builder(ctx)
                            .setTitle("قيمة جديدة لـ ${p.name}")
                            .setView(input)
                            .setPositiveButton("إرسال") { _, _ ->
                                val v = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                                val c = client
                                if (c == null || !c.isConnected) { Toast.makeText(ctx, "غير متصل", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                                scope.launch(Dispatchers.IO) {
                                    try { c.writeSingleRegister(p.address, v); p.intValue = v }
                                    catch (e: Exception) {
                                        withContext(Dispatchers.Main) { Toast.makeText(ctx, "فشل: ${e.message}", Toast.LENGTH_SHORT).show() }
                                    }
                                }
                            }
                            .setNegativeButton("إلغاء", null)
                            .show()
                    }
                }
                row.addView(valText)
                row.addView(editBtn)
                rowViews[p.name] = valText
            }
            PointType.INPUT_REGISTER -> {
                val valText = TextView(ctx).apply {
                    text = p.intValue.toString()
                    setTextColor(Color.parseColor("#4FB0FF"))
                }
                row.addView(valText)
                rowViews[p.name] = valText
            }
        }

        val delBtn = Button(ctx).apply {
            text = "✕"
            setOnClickListener {
                points.remove(p)
                savePrefs()
                rebuildPointsUI()
            }
        }
        row.addView(delBtn)

        pointsContainer.addView(row)
    }

    private fun updateRow(p: PlcPoint) {
        val v = rowViews[p.name] ?: return
        when (p.type) {
            PointType.COIL -> (v as? Switch)?.isChecked = p.boolValue
            PointType.DISCRETE_INPUT -> (v as? TextView)?.apply {
                text = if (p.boolValue) "● 1" else "○ 0"
                setTextColor(if (p.boolValue) Color.parseColor("#33E28A") else Color.parseColor("#6B7A8A"))
            }
            PointType.HOLDING_REGISTER -> (v as? TextView)?.text = p.intValue.toString()
            PointType.INPUT_REGISTER -> (v as? TextView)?.text = p.intValue.toString()
        }
    }

    private fun prefs() = getSharedPreferences("logipanel", Context.MODE_PRIVATE)

    private fun savePrefs() {
        val editor = prefs().edit()
        editor.putString("ip", ipInput.text.toString())
        editor.putString("port", portInput.text.toString())
        editor.putString("unitId", unitIdInput.text.toString())
        val arr = JSONArray()
        for (p in points) {
            val o = JSONObject()
            o.put("name", p.name); o.put("type", p.type.name); o.put("address", p.address)
            arr.put(o)
        }
        editor.putString("points", arr.toString())
        editor.apply()
    }

    private fun loadPrefs() {
        val sp = prefs()
        ipInput.setText(sp.getString("ip", ""))
        portInput.setText(sp.getString("port", "502"))
        unitIdInput.setText(sp.getString("unitId", "1"))
        val raw = sp.getString("points", null)
        if (raw != null) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    points.add(PlcPoint(o.getString("name"), PointType.valueOf(o.getString("type")), o.getInt("address")))
                }
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
        scope.launch(Dispatchers.IO) { client?.disconnect() }
    }
}
