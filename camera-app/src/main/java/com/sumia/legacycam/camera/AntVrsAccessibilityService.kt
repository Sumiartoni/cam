package com.sumia.legacycam.camera

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class AntVrsAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
