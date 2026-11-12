package com.example.livesignalscanner

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ВСТАВЬТЕ СЮДА ВАШ КЛЮЧ API-FOOTBALL
    private val apiKey = 4772d916a7a47686fe788852923950cc

    private lateinit var statusText: TextView
    private lateinit var resultText: TextView

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 40, 40, 40)

        val title = TextView(this)
        title.text = "Live Signal Scanner"
        title.textSize = 24f
        title.setPadding(0, 0, 0, 30)

        val info = TextView(this)
        info.text = """
            Условия:
            • Первый тайм
            • Минута от 20
            • Ровно 1 гол: 1:0 или 0:1
            • ТБ 1.5 < 2.00
            • Обе забьют — да <= 2.00
        """.trimIndent()
        info.setPadding(0, 0, 0, 30)

        val button = Button(this)
        button.text = "Сканировать матчи"

        statusText = TextView(this)
        statusText.setPadding(0, 20, 0, 20)

        resultText = TextView(this)
        resultText.textSize = 16f

        val scroll = ScrollView(this)
        scroll.addView(resultText)

        layout.addView(title)
        layout.addView(info)
        layout.addView(button)
        layout.addView(statusText)
        layout.addView(scroll)

        setContentView(layout)

        button.setOnClickListener {
            scanMatches()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun scanMatches() {
        statusText.text = "Сканирую матчи..."
        resultText.text = ""

        Thread {
            try {
                val liveFixtures = getLiveFixtures()
                val signals = StringBuilder()

                var found = 0

                for (i in 0 until liveFixtures.length()) {
                    val fixture = liveFixtures.getJSONObject(i)

                    val fixtureInfo = fixture.getJSONObject("fixture")
                    val status = fixtureInfo.getJSONObject("status")
                    val statusShort = status.optString("short")
                    val minute = status.optInt("elapsed", 0)

                    val goals = fixture.getJSONObject("goals")
                    val homeGoals = goals.optInt("home", 0)
                    val awayGoals = goals.optInt("away", 0)
                    val totalGoals = homeGoals + awayGoals

                    if (statusShort != "1H") continue
                    if (minute < 20) continue
                    if (totalGoals != 1) continue

                    val fixtureId = fixtureInfo.getLong("id")
                    val odds = getLiveOdds(fixtureId)

                    val over15 = findOdds(odds, "Goals Over/Under", "Over 1.5")
                    val bttsYes = findOdds(odds, "Both Teams", "Yes")

                    val over15Ok = over15 != null && over15 < 2.00
                    val bttsOk = bttsYes != null && bttsYes <= 2.00

                    if (over15Ok && bttsOk) {
                        found++

                        val league = fixture.getJSONObject("league")
                        val teams = fixture.getJSONObject("teams")

                        val homeTeam = teams.getJSONObject("home").getString("name")
                        val awayTeam = teams.getJSONObject("away").getString("name")

                        signals.append("МАТЧ №$found\n")
                        signals.append("$homeTeam — $awayTeam\n")
                        signals.append("Лига: ${league.optString("name")}\n")
                        signals.append("Счёт: $homeGoals:$awayGoals\n")
                        signals.append("Минута: $minute\n")
                        signals.append("ТБ 1.5: $over15\n")
                        signals.append("Обе забьют — да: $bttsYes\n")
                        signals.append("--------------------------\n\n")
                    }
                }

                runOnUiThread {
                    statusText.text = if (found == 0) {
                        "Сигналов пока нет."
                    } else {
                        "Найдено сигналов: $found"
                    }

                    resultText.text = signals.toString()
                }

            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Ошибка: ${e.message}"
                }
            }
        }.start()
    }

    private fun getLiveFixtures(): JSONArray {
        val url = "https://v3.football.api-sports.io/v3/fixtures?live=all"

        val request = Request.Builder()
            .url(url)
            .addHeader("x-apisports-key", apiKey)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw Exception("Пустой ответ API")
            val json = JSONObject(body)
            return json.getJSONArray("response")
        }
    }

    private fun getLiveOdds(fixtureId: Long): JSONObject {
        val url = "https://v3.football.api-sports.io/v3/odds/live?fixture=$fixtureId"

        val request = Request.Builder()
            .url(url)
            .addHeader("x-apisports-key", apiKey)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw Exception("Пустой ответ API")
            val json = JSONObject(body)
            val responseArray = json.getJSONArray("response")

            return if (responseArray.length() > 0) {
                responseArray.getJSONObject(0)
            } else {
                JSONObject()
            }
        }
    }

    private fun findOdds(oddsObject: JSONObject, betNamePart: String, valueName: String): Double? {
        if (!oddsObject.has("bookmakers")) return null

        val bookmakers = oddsObject.getJSONArray("bookmakers")

        for (i in 0 until bookmakers.length()) {
            val bookmaker = bookmakers.getJSONObject(i)
            val bets = bookmaker.getJSONArray("bets")

            for (j in 0 until bets.length()) {
                val bet = bets.getJSONObject(j)
                val betName = bet.optString("name")

                if (!betName.contains(betNamePart, ignoreCase = true)) continue

                val values = bet.getJSONArray("values")

                for (k in 0 until values.length()) {
                    val value = values.getJSONObject(k)

                    if (value.optString("value").equals(valueName, ignoreCase = true)) {
                        return value.optString("odd").toDoubleOrNull()
                    }
                }
            }
        }

        return null
    }
}
