package app.sokyiptv.update

import android.content.Context
import android.content.Intent
import android.app.Application
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class UpdateRelaunchTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        UpdateRelaunch.disarm(context)
    }

    @After fun tearDown() { UpdateRelaunch.disarm(context) }

    @Test fun expectedVersionConsumesExactlyOnce() {
        UpdateRelaunch.arm(context, "0.1.11.100-live-movies-browser-parity")
        assertTrue(UpdateRelaunch.consume(context, "0.1.11.100-live-movies-browser-parity"))
        assertFalse(UpdateRelaunch.consume(context, "0.1.11.100-live-movies-browser-parity"))
    }

    @Test fun unrelatedInstallCannotConsumeAnUpdateMarker() {
        UpdateRelaunch.arm(context, "0.1.11.100-live-movies-browser-parity")
        assertFalse(UpdateRelaunch.consume(context, "0.1.11.99-live-catalog-system-ime-relaunch"))
        assertTrue(UpdateRelaunch.consume(context, "0.1.11.100-live-movies-browser-parity"))
    }

    @Test fun cancellationCannotRelaunchApp() {
        UpdateRelaunch.arm(context, "0.1.11.100-live-movies-browser-parity")
        UpdateRelaunch.disarm(context)
        assertFalse(UpdateRelaunch.consume(context, "0.1.11.100-live-movies-browser-parity"))
    }

    @Test fun invalidBroadcastDoesNothing() {
        val receiver = UpdateRelaunchReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
        // Even a real replacement broadcast is inert without a successful
        // app-initiated installer marker. No notification or alarm is sent.
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertFalse(UpdateRelaunch.consume(context, "0.1.11.100-live-movies-browser-parity"))
    }
}
