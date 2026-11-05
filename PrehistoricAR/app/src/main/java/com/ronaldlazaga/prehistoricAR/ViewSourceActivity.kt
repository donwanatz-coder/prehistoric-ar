package com.ronaldlazaga.prehistoricAR

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** In-memory hand-off so a large page isn't squeezed through an Intent extra. */
object SourceHolder {
    var url: String = ""
    var html: String = ""
}

/** Shows the raw HTML source of the page, with copy and share actions. */
class ViewSourceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_view_source)

        val title = findViewById<TextView>(R.id.sourceTitle)
        val body = findViewById<TextView>(R.id.sourceText)

        title.text = SourceHolder.url
        body.text = SourceHolder.html.ifEmpty { "(no source available yet - wait for the page to finish loading)" }

        findViewById<TextView>(R.id.copyButton).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("page source", SourceHolder.html))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }

        findViewById<TextView>(R.id.shareButton).setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, SourceHolder.html)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share)))
        }
    }
}
