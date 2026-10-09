package com.alalay.demomessenger

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Thread list. Each row is a single clickable element whose
 * contentDescription is the contact name, so Alalay's accessibility
 * dump sees one target per contact.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scroll.addView(list)

        val header = TextView(this).apply {
            text = "Messages"
            textSize = 28f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 24)
        }
        list.addView(header)

        Seed.threads.forEachIndexed { i, thread ->
            list.addView(threadRow(thread.name, thread.messages.last().text) {
                startActivity(
                    Intent(this, ThreadActivity::class.java)
                        .putExtra(ThreadActivity.EXTRA_INDEX, i)
                )
            })
        }
        setContentView(scroll)
    }

    private fun threadRow(name: String, preview: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 24, 16, 24)
            isClickable = true
            isFocusable = true
            contentDescription = name
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = name
            textSize = 20f
            setTextColor(Color.BLACK)
        })
        row.addView(TextView(this).apply {
            text = preview
            textSize = 16f
            setTextColor(Color.DKGRAY)
        })
        return row
    }
}
