package com.szc.learning

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机/模拟器冒烟测试：确认 applicationId 与包名一致。
 */
@RunWith(AndroidJUnit4::class)
class SmokeInstrumentedTest {

    @Test
    fun applicationId_isCorrect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.szc.learning", context.packageName)
    }
}
