package com.hbg.live.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.hbg.live.R
import com.hbg.live.util.StudioLogger

/**
 * Cửa sổ Chẩn đoán Nhật ký Phần cứng Studio (Studio Hardware Diagnostics Dialog)
 */
class DebugLogDialog(context: Context) : AlertDialog(context) {

    private lateinit var tvLogContent: TextView
    private lateinit var scrollViewLog: ScrollView
    private lateinit var btnCopyLog: MaterialButton
    private lateinit var btnClearLog: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_debug_log)

        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.9).toInt(),
            (context.resources.displayMetrics.heightPixels * 0.85).toInt()
        )

        tvLogContent = findViewById(R.id.tvLogContent)!!
        scrollViewLog = findViewById(R.id.scrollViewLog)!!
        btnCopyLog = findViewById(R.id.btnCopyLog)!!
        btnClearLog = findViewById(R.id.btnClearLog)!!

        StudioLogger.setLogListener { logs ->
            tvLogContent.text = logs
            scrollViewLog.post {
                scrollViewLog.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }

        btnCopyLog.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Studio Hardware Logs", StudioLogger.getAllLogs())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Đã sao chép toàn bộ Log chẩn đoán!", Toast.LENGTH_SHORT).show()
        }

        btnClearLog.setOnClickListener {
            StudioLogger.clear()
        }
    }

    override fun onStop() {
        super.onStop()
        StudioLogger.setLogListener(null)
    }
}
