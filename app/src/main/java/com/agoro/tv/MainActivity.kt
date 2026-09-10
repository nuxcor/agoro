package com.agoro.tv

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import com.agoro.tv.player.DisplayModeSwitcher
import com.agoro.tv.ui.AppRoot
import com.agoro.tv.ui.theme.NuxTvTheme

class MainActivity : ComponentActivity() {

    /**
     * The output-mode pin, at the level that actually owns it.
     *
     * The player has its own [DisplayModeSwitcher] and releases the pin when
     * it minimises into PiP, which is the one case this cannot see — PiP does
     * not stop the activity. Everything else is here, because the pin is not
     * the player's to hold: it survives the player screen closing on purpose
     * (see the class comment there), so by the time the viewer presses HOME
     * the player screen is usually long gone and there is nothing mounted to
     * let go of a mode the whole HDMI output is still sitting on.
     *
     * Two instances of the same class agreeing about one window is safe
     * because neither remembers what it asked for: both read the pin back off
     * the window, so whichever one releases first wins and the other finds
     * nothing to do.
     */
    private val displayModes by lazy { DisplayModeSwitcher(this) }

    override fun onStart() {
        super.onStart()
        // Back in front: take back the output mode released on the way out.
        // A no-op on first launch, and on a return from PiP, where the player
        // is holding the release and puts it back itself.
        displayModes.restore()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Hold the system splash until the cached playlist has been read off
        // disk, then go straight to content. Startup used to hand the splash
        // off to an in-app boot screen that drew the same mark again and sat
        // on a 900ms floor, so every launch showed the logo twice and paid for
        // the second one in latency the disk cache exists to avoid.
        //
        // Bounded by a deadline: a source flow that somehow never emits must
        // not strand the app on a splash it can never dismiss.
        val vm = ViewModelProvider(this)[MainViewModel::class.java]
        val splashDeadline = SystemClock.uptimeMillis() + 2_000
        splash.setKeepOnScreenCondition {
            vm.sources.value == null && SystemClock.uptimeMillis() < splashDeadline
        }

        // Recording/reminder notifications are invisible on 13+ without this.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        setContent {
            NuxTvTheme {
                AppRoot(vm)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Going to the background is not the same as going away: the player
        // the viewer is watching (in PiP, say) is leased out and untouched,
        // but the spares kept for the next zap are held for a return that may
        // never come. On a 2 GB box they are held at the expense of whatever
        // the viewer opened instead.
        //
        // Not on a configuration change, for the same reason onDestroy below
        // asks whether the activity is finishing: the app is coming straight
        // back, and the first zap after it would pay a full player build for
        // a spare that was thrown away a few hundred milliseconds earlier.
        if (isChangingConfigurations) return
        com.agoro.tv.player.PlayerPool.releaseIdle()
        // And the output mode with it. `preferredDisplayModeId` is a vote on
        // the whole HDMI output rather than on this window's contents, so a
        // mode pinned for a 25fps channel is a mode the box is still in when
        // the viewer opens something else — including another video app with
        // its own opinion about refresh rate. Restored in onStart.
        displayModes.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        // The pooled players outlive the player screen on purpose (leaving
        // a channel must not block on codec teardown); a process that is
        // genuinely done — the activity finishing, not rotating — lets them
        // go. A stopped player holds no decoders, so leaking one across a
        // configuration change would cost nothing either way.
        //
        // onStop has usually emptied the pool already. This is for what lands
        // in it after that: super.onDestroy() disposes the Compose tree, and
        // the player screen hands its lease back on the way down.
        if (isFinishing) com.agoro.tv.player.PlayerPool.releaseIdle()
    }
}
