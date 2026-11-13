package com.example.livesignalscanner

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private val API_KEY = "4772d916a7a47686fe788852923950cc"
    private val client = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var results: TextView
    private lateinit var historyView: TextView
    private var soundEnabled = true

    private val autoScan = object : Runnable {
        override fun run() {
            scanLive()
            handler.postDelayed(this, 30000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        soundEnabled = prefs.getBoolean("sound", true)

        val root = ScrollView(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 60, 40, 40)

        val bg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#1B2A4A"), Color.parseColor("#0F172A"))
        )
        layout.background = bg

        val title = TextView(this)
        title.text = "Live Signal Scanner"
        title.textSize = 26f
        title.setTextColor(Color.WHITE)
        title.gravity = Gravity.CENTER

        status = TextView(this)
        status.text = "Автосканирование каждые 30 сек"
        status.textSize = 16f
        status.setTextColor(Color.parseColor("#9CA3AF"))
        status.gravity = Gravity.CENTER
        status.setPadding(0, 30, 0, 30)

        results = TextView(this)
        results.text = ""
        results.textSize = 14f
        results.setTextColor(Color.WHITE)

        historyView = TextView(this)
        historyView.text = loadHistory()
        historyView.textSize = 14f
        historyView.setTextColor(Color.parseColor("#FBBF24"))
        historyView.setPadding(0, 30, 0, 0)

        val scanBtn = Button(this)
        scanBtn.text = "Сканировать сейчас"
        scanBtn.setOnClickListener { scanLive() }

        val settingsBtn = Button(this)
        settingsBtn.text = "Настройки"
        settingsBtn.setOnClickListener { showSettings() }

        val clearBtn = Button(this)
        clearBtn.text = "Очистить историю"
        clearBtn.setOnClickListener { clearHistory() }

        val testBtn = Button(this)
        testBtn.text = "Тест уведомления"
        testBtn.setOnClickListener { sendNotification("Тестовое уведомление", "Проверка звука") }

        layout.addView(title)
        layout.addView(status)
        layout.addView(scanBtn)
        layout.addView(settingsBtn)
        layout.addView(clearBtn)
        layout.addView(testBtn)
        layout.addView(results)
        layout.addView(historyView)

        root.addView(layout)
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        handler.postDelayed(autoScan, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(autoScan)
    }

    private fun scanLive() {
        status.text = "Сканирование..."
        Thread {
            try {
                val req = Request.Builder()
                    .url("https://v3.football.api-sports.io/fixtures?live=all")
                    .addHeader("x-apisports-key", API_KEY)
                    .build()

                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val arr = json.getJSONArray("response")

                val sb = StringBuilder()

                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    val fixture = f.getJSONObject("fixture")
                    val st = fixture.getJSONObject("status")
                    val short = st.optString("short")
                    val elapsed = st.optInt("elapsed", 0)
                    val goals = f.getJSONObject("goals")
                    val home = goals.optInt("home", 0)
                    val away = goals.optInt("away", 0)

                    if (short == "1H" && elapsed >= 20 && home + away == 1) {
                        val id = fixture.optInt("id")
                        val teams = f.getJSONObject("teams")
                        val homeName = teams.getJSONObject("home").optString("name")
                        val awayName = teams.getJSONObject("away").optString("name")

                        val odds = getOdds(id)
                        if (odds != null) {
                            val over15 = odds.first
                            val btts = odds.second

                            if (over15 < 2.00 && btts <= 2.00) {
                                val signal = "СИГНАЛ\n$homeName — $awayName\nВремя: $elapsed'\nТБ 1.5: $over15\nОбе забьют: $btts"
                                sb.append(signal).append("\n\n")

                                saveSignal(signal)
                                runOnUiThread {
                                    historyView.text = loadHistory()
                                    sendNotification("Сигнал", "$homeName — $awayName | $elapsed'")
                                }
                            }
                        }
                    }
                }

                runOnUiThread {
                    results.text = if (sb.isEmpty()) "Сигналов нет" else sb.toString()
                    status.text = "Следующее сканирование через 30 сек"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Ошибка: ${e.message}"
                }
            }
        }.start()
    }

    private fun getOdds(fixtureId: Int): Pair<Double, Double>? {
        return try {
            val req = Request.Builder()
                .url("https://v3.football.api-sports.io/odds/live?fixture=$fixtureId")
                .addHeader("x-apisports-key", API_KEY)
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            val arr = json.getJSONArray("response")
            if (arr.length() == 0) return null

            var over15 = -1.0
            var btts = -1.0

            val bookmakers = arr.getJSONObject(0).getJSONArray("bookmakers")
            for (b in 0 until bookmakers.length()) {
                val bets = bookmakers.getJSONObject(b).getJSONArray("bets")
                for (j in 0 until bets.length()) {
                    val bet = bets.getJSONObject(j)
                    val name = bet.optString("name")
                    val values = bet.getJSONArray("values")
                    for (k in 0 until values.length()) {
                        val v = values.getJSONObject(k)
                        val value = v.optString("value")
                        val odd = v.optString("odd").toDoubleOrNull() ?: continue

                        if (name.contains("Goals Over/Under", true) && value == "Over 1.5") {
                            over15 = odd
                        }
                        if (name.contains("Both Teams Score", true) && value == "Yes") {
                            btts = odd
                        }
                    }
                }
            }

            if (over15 > 0 && btts > 0) Pair(over15, btts) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun saveSignal(signal: String) {
        val prefs = getSharedPreferences("history", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("signals", "[]"))
        val time = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        arr.put("[$time] $signal")
        prefs.edit().putString("signals", arr.toString()).apply()
    }

    private fun loadHistory(): String {
        val prefs = getSharedPreferences("history", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("signals", "[]"))
        if (arr.length() == 0) return "История пуста"
        val sb = StringBuilder("ИСТОРИЯ СИГНАЛОВ\n\n")
        for (i in arr.length() - 1 downTo 0) {
            sb.append(arr.getString(i)).append("\n\n")
        }
        return sb.toString()
    }

    private fun clearHistory() {
        getSharedPreferences("history", Context.MODE_PRIVATE)
            .edit().putString("signals", "[]").apply()
        historyView.text = "История пуста"
        Toast.makeText(this, "История очищена", Toast.LENGTH_SHORT).show()
    }

    private fun showSettings() {
        val sound = booleanArrayOf(soundEnabled)
        android.app.AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setMultiChoiceItems(arrayOf("Звук уведомлений"), sound) { _, _, isChecked ->
                sound[0] = isChecked
            }
            .setPositiveButton("Сохранить") { _, _ ->
                soundEnabled = sound[0]
                getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .edit().putBoolean("sound", soundEnabled).apply()
                Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun sendNotification(title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("signals", "Сигналы", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, "signals")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
        if (!soundEnabled) builder.setDefaults(0)
        nm.notify(1, builder.build())
    }
}
