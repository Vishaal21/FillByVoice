package com.vishal.fillbyvoice.log

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// The phone's own log can be switched off: at 08:35 its log switch stopped all logging, even Android's.
// So every line also goes to a file on the phone, which the laptop reads (./logs.sh, or
// adb shell tail -f /sdcard/Android/data/com.vishal.fillbyvoice/files/app.log).
// Same calls as android.util.Log, so each file only swaps its import.
object Log {
    private var out: FileWriter? = null
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun start(context: Context) {
        val file = File(context.getExternalFilesDir(null), "app.log")
        // A new start after 5 MB begins a fresh file.
        out = try {
            FileWriter(file, file.length() < 5_000_000)
        } catch (e: Exception) {
            android.util.Log.w("App", "No log file", e)
            null
        }
        i("App", "===== Fill by Voice started =====")
    }

    fun i(tag: String, msg: String) {
        write("I", tag, msg, null)
        android.util.Log.i(tag, msg)
    }

    fun w(tag: String, msg: String, e: Throwable? = null) {
        write("W", tag, msg, e)
        android.util.Log.w(tag, msg, e)
    }

    fun e(tag: String, msg: String, e: Throwable? = null) {
        write("E", tag, msg, e)
        android.util.Log.e(tag, msg, e)
    }

    // Called from the main thread and Gemma's thread at once, so one line at a time.
    @Synchronized
    private fun write(level: String, tag: String, msg: String, e: Throwable?) {
        val writer = out ?: return
        try {
            writer.write("${LocalTime.now().format(TIME)} $level/$tag: $msg\n")
            if (e != null) writer.write(android.util.Log.getStackTraceString(e) + "\n")
            writer.flush()
        } catch (_: Exception) {
            // A full disk must never stop the app; the phone's own log still gets the line.
        }
    }
}
