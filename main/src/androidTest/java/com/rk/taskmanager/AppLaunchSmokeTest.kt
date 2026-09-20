package com.rk.taskmanager

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launch smoke test: boots the real activity on an emulator with no daemon,
 * no root and no Shizuku — the degraded-mode path every fresh install hits
 * first. Compose inflation, nav routing (including the working-mode screen),
 * the notification-permission prompt and the daemon connect attempts must
 * all run without crashing the process.
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchSmokeTest {

    @Test
    fun mainActivityLaunchesAndSurvivesStartupPaths() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { /* forces create -> start -> resume */ }
            // Give the async startup paths time to run: compose first frame,
            // graphUpdater collector, root/Shizuku probing, optional FGS.
            Thread.sleep(4_000)
            // onActivity throws IllegalStateException if the activity was
            // destroyed, and isFinishing catches a graceful self-finish —
            // version-proof alive check (ActivityScenario.State does not
            // exist in every androidx.test:core release).
            scenario.onActivity { activity ->
                assertFalse("MainActivity is finishing after startup", activity.isFinishing)
            }
        }
    }
}
