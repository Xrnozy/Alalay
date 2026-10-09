package com.alalay.demomessenger

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * One conversation. Exposes the node ids Alalay's Executor targets:
 * thread_title, message_list/message_text, compose_field, send_button.
 * Send appends an outgoing bubble and clears the field, so Alalay's
 * post-send verification can confirm the text landed.
 */
class ThreadActivity : Activity() {

    companion object {
        const val EXTRA_INDEX = "thread_index"
    }

    private lateinit var messageColumn: LinearLayout
    private lateinit var compose: EditText
    private lateinit var thread: Thread

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thread = Seed.threads[intent.getIntExtra(EXTRA_INDEX, 0)]

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(TextView(this).apply {
            id = R.id.thread_title
            text = thread.name
            textSize = 24f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 16)
        })

        val scroll = ScrollView(this).apply { id = R.id.message_list }
        messageColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(messageColumn)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        compose = EditText(this).apply {
            id = R.id.compose_field
            hint = "Message"
            textSize = 18f
            setSingleLine(true)
        }
        val send = Button(this).apply {
            id = R.id.send_button
            text = "Send"
            contentDescription = "Send"
            setOnClickListener { sendMessage() }
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(compose, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(send)
        })

        renderMessages()
        setContentView(root)
    }

    private fun sendMessage() {
        val text = compose.text.toString().trim()
        if (text.isEmpty()) return
        thread.messages += Message(text, true, "now")
        compose.setText("")
        renderMessages()
    }

    private fun renderMessages() {
        messageColumn.removeAllViews()
        thread.messages.forEach { m ->
            messageColumn.addView(TextView(this).apply {
                id = R.id.message_text
                text = m.text
                textSize = 18f
                setTextColor(Color.BLACK)
                setPadding(16, 12, 16, 12)
                contentDescription =
                    if (m.outgoing) "You said: ${m.text}" else "${thread.name} said: ${m.text}"
            })
        }
    }
}
