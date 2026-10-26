package com.zeroconvert.ime

import androidx.activity.ComponentActivity
import android.os.Bundle
import android.widget.EditText
import android.view.WindowManager

/** Controlled editor for device tests. This activity is absent from release builds. */
class PortTestActivity : ComponentActivity() {
    lateinit var field: EditText
        private set
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        field = EditText(this).apply {
            hint = "ZeroConvert 検証用入力欄"
            showSoftInputOnFocus = false
            setSingleLine(false)
        }
        setContentView(field)
        field.requestFocus()
    }
}
