package com.localfy.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localfy.app.ui.theme.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtThemeTest {
    @Test fun themesShipWithArtAndSurviveRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = ThemeRepository(context)
        val previous = repository.settings.value
        try {
            val all = ArtThemes.all(context)
            assertEquals(25, all.size); assertEquals(25, all.map { it.id }.toSet().size)
            repository.update { it.copy(textSize = TextSize.Huge, reduceMotion = true) }
            for (theme in all) {
                context.assets.open("art/${theme.id}.png").use { assertTrue(it.available() > 1000) }
                repository.update(theme::apply)
                val restored = ThemeRepository(context).settings.value
                assertEquals(theme.id, restored.artThemeID)
                assertEquals(TextSize.Huge, restored.textSize); assertTrue(restored.reduceMotion)
                assertFalse(restored.hideThemeArt)
            }
            repository.update(ThemePresets.first()::apply)
            assertNull(ThemeRepository(context).settings.value.artThemeID)
        } finally { repository.update { previous } }
    }
}
