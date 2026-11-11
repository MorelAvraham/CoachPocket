package com.coachpocket.app

import android.os.SystemClock

/** The single time base of the app: microseconds since boot, the clock camera frames are stamped with. */
fun nowUs(): Long = SystemClock.elapsedRealtimeNanos() / 1000
