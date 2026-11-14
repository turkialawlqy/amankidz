package com.turkialawlqy.aman.player

import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.LoadControl

/** تشغيل أسرع: يبدأ بعد ثانية تحميل بدل ثانيتين ونص (والباقي يكمل وهو شغّال) */
object FastStart {
    fun loadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(15_000, 50_000, 1_000, 2_000)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
}
