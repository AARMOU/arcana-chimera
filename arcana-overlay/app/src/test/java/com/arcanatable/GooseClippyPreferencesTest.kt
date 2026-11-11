package com.arcanatable

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class GooseClippyPreferencesTest {
    @Test fun defaultsToOnAndPersistsAnIndependentOffState() {
        val prefs=GooseClippyPreferences.prefs(RuntimeEnvironment.getApplication())
        prefs.edit().clear().commit()
        try {
            assertTrue(GooseClippyPreferences.read(prefs))
            GooseClippyPreferences.write(prefs,false)
            assertFalse(GooseClippyPreferences.read(prefs))
            assertFalse(GooseClippyPreferences.read(
                GooseClippyPreferences.prefs(RuntimeEnvironment.getApplication())))
            GooseClippyPreferences.write(prefs,true)
            assertTrue(GooseClippyPreferences.read(prefs))
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test fun studioPresetDoesNotChangeEnabledPreference() {
        val prefs=GooseClippyPreferences.prefs(RuntimeEnvironment.getApplication())
        prefs.edit().clear().commit()
        try {
            GooseClippyPreferences.write(prefs,false)
            val baseline=StudioControlProject()
            val preset=StudioPresets.all.first()
            StudioPresets.project(baseline,preset,false)
            assertFalse("GooseClippy toggle must not be rewritten by a skin preset",
                GooseClippyPreferences.read(prefs))
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
