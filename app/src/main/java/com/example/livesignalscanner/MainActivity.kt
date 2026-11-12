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
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private var soundEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        soundEnabled = prefs.getBoolean("sound", true)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.gravity = Gravity.CENTER
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
        status.text = "Готов к работе"
        status.textSize = 18f
        status.setTextColor(Color.parseColor("#9CA3AF"))
        status.gravity = Gravity.CENTER
        status.setPadding(0, 40, 0, 40)

        val scanBtn = Button(this)
        scanBtn.text = "Сканировать"
        scanBtn.setOnClickListener { status.text = "Сканирование..." }

        val settingsBtn = Button(this)
        settingsBtn.text = "Настройки"
        settingsBtn.setOnClickListener { showSettings() }

        val testBtn = Button(this)
        testBtn.text = "Тест уведомления"
        testBtn.setOnClickListener { sendTestNotification() }

        layout.addView(title)
        layout.addView(status)
        layout.addView(scanBtn)
        layout.addView(settingsBtn)
        layout.addView(testBtn)

        setContentView(layout)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
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

    private fun sendTestNotification() {
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
        builder.setContentTitle("Live Signal Scanner")
            .setContentText("Тестовое уведомление")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
        if (!soundEnabled) builder.setDefaults(0)
        nm.notify(1, builder.build())
        status.text = "Уведомление отправлено"
    }
}
