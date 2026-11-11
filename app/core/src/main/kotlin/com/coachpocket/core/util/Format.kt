package com.coachpocket.core.util

import java.util.Locale

/** Number formatting that does not depend on the phone's language (logs and CSV must stay parseable). */
fun fmt(pattern: String, vararg args: Any?): String = String.format(Locale.US, pattern, *args)
