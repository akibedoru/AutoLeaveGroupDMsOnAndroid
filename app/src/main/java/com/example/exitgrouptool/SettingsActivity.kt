package com.example.exitgrouptool

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val keywordEditText = findViewById<EditText>(R.id.keywordEditText)
        val saveButton = findViewById<Button>(R.id.saveButton)

        // 以前保存したキーワードを表示
        val prefs = getSharedPreferences("ExitPrefs", Context.MODE_PRIVATE)
        val savedKeywords = prefs.getString("keywords", "")
        keywordEditText.setText(savedKeywords)

        // 保存ボタンを押したとき
        saveButton.setOnClickListener {
            val keywords = keywordEditText.text.toString().trim()
            prefs.edit().putString("keywords", keywords).apply()
            Log.d("SettingsActivity", "保存されたキーワード: $keywords")
        }
    }
}
