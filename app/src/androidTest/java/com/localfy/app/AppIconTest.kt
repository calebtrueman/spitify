package com.localfy.app

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localfy.app.icons.AppIcons
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppIconTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun everyChoiceHasAnIconAndAnAliasThatOpensTheRealActivity() {
        val icons = AppIcons(context)
        assertFalse("The icon catalog must be included in the app", icons.choices.isEmpty())
        for (choice in icons.choices) {
            assertTrue("Missing icon for ${choice.id}", icons.previewResource(choice) != 0)
            val info = context.packageManager.getActivityInfo(icons.component(choice.id), PackageManager.MATCH_DISABLED_COMPONENTS)
            assertEquals(MainActivity::class.java.name, info.targetActivity)
            assertTrue(info.exported)
        }
    }

    @Test fun changingIconsKeepsOneLauncherEntryAndDoesNotDestroyTheOpenActivity() {
        val icons = AppIcons(context)
        val old = icons.selectedId() ?: AppIcons.DEFAULT_ID
        val alternate = icons.choices.first { it.id != old }.id
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        try {
            ActivityScenario.launch<MainActivity>(launch).use { scenario ->
                icons.select(alternate)
                assertEquals(listOf(alternate), icons.enabledIds())
                assertEquals(alternate, AppIcons(context).selectedId())
                scenario.onActivity { assertFalse(it.isDestroyed); assertEquals(MainActivity::class.java, it.javaClass) }
                val changedLaunch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
                val resolved = requireNotNull(context.packageManager.resolveActivity(changedLaunch, 0)).activityInfo
                assertEquals(icons.component(alternate).className, resolved.name)
                assertEquals(MainActivity::class.java.name, resolved.targetActivity)
                icons.select(AppIcons.DEFAULT_ID)
                assertEquals(listOf(AppIcons.DEFAULT_ID), icons.enabledIds())
                scenario.onActivity { assertFalse(it.isDestroyed) }
            }
        } finally { icons.select(old) }
    }
}
