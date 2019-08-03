package com.lance.realtimeobjectrecognition.activities

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import com.lance.realtimeobjectrecognition.R

class ChooserActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chooser)
    }

    fun startTensorFlowDetectorActivity(view: View) {
        startActivity(Intent(this, TFDetectorActivity::class.java))
    }

    fun startLabelerActivity(view: View) {
        startActivity(Intent(this, LabelerActivity::class.java))
    }
}
