package com.fayyaztech.dialathon

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.fayyaztech.dialer_core.ui.dialer.DialerActivity

class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, DialerActivity::class.java))
        finish()
    }
}
