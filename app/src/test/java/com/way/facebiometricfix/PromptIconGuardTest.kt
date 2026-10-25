package com.way.facebiometricfix

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PromptIconGuardTest {
    private val controller = Robolectric.buildActivity(Activity::class.java)
    private lateinit var root: FrameLayout
    private lateinit var confirm: Button
    private lateinit var icon: ImageView
    private lateinit var overlay: ImageView
    private lateinit var guard: PromptIconGuard
    private var disposals = 0

    @Before fun setUp() {
        val activity = controller.setup().visible().get()
        root = FrameLayout(activity)
        confirm = Button(activity)
        icon = ImageView(activity).apply { isClickable = true; isFocusable = true }
        overlay = ImageView(activity)
        root.addView(confirm)
        root.addView(icon)
        root.addView(overlay)
        activity.setContentView(root)
        root.layout(0, 0, 400, 600)
        confirm.layout(100, 300, 300, 360)
        icon.layout(150, 280, 250, 380)
        overlay.layout(150, 280, 250, 380)
        guard = PromptIconGuard(root, confirm, listOf(icon, overlay),
            onDisposed = { disposals++ }, onError = { throw AssertionError(it) })
    }

    @After fun tearDown() {
        guard.dispose()
        controller.pause().stop().destroy()
    }

    private fun draw() { root.viewTreeObserver.dispatchOnPreDraw() }

    @Test fun hidesOverlappingIconsWithoutChangingLayoutOrInteractionFlags() {
        draw()
        assertEquals(View.INVISIBLE, icon.visibility)
        assertEquals(View.INVISIBLE, overlay.visibility)
        assertEquals(View.VISIBLE, confirm.visibility)
        assertTrue(confirm.isEnabled)
        assertEquals(100, icon.width)
        assertEquals(100, icon.height)
        assertTrue(icon.isClickable)
        assertTrue(icon.isFocusable)
        draw()
        assertEquals(View.INVISIBLE, icon.visibility)
    }

    @Test fun leavesNonOverlappingIconsVisible() {
        icon.layout(0, 0, 100, 100)
        draw()
        assertEquals(View.VISIBLE, icon.visibility)
        assertEquals(View.INVISIBLE, overlay.visibility)
    }

    @Test fun keepsTheIconWhenTheButtonCannotConfirm() {
        confirm.isEnabled = false
        draw()
        assertEquals(View.VISIBLE, icon.visibility)
        confirm.isEnabled = true
        confirm.visibility = View.INVISIBLE
        draw()
        assertEquals(View.VISIBLE, icon.visibility)
    }

    @Test fun restoresIconsWhenConfirmationEnds() {
        draw()
        confirm.visibility = View.GONE
        draw()
        assertEquals(View.VISIBLE, icon.visibility)
        assertEquals(View.VISIBLE, overlay.visibility)
    }

    @Test fun restoresIconsWhenLayoutStopsOverlapping() {
        draw()
        confirm.layout(0, 450, 200, 510)
        draw()
        assertEquals(View.VISIBLE, icon.visibility)
    }

    @Test fun suppressesNativeRedrawsWhileConfirmationIsStillVisible() {
        draw()
        icon.visibility = View.VISIBLE
        overlay.visibility = View.VISIBLE
        draw()
        assertEquals(View.INVISIBLE, icon.visibility)
        assertEquals(View.INVISIBLE, overlay.visibility)
    }

    @Test fun preservesNativeGoneStateOnCleanup() {
        draw()
        icon.visibility = View.GONE
        guard.dispose()
        assertEquals(View.GONE, icon.visibility)
        assertEquals(View.VISIBLE, overlay.visibility)
    }

    @Test fun preservesIconsThatWereAlreadyHiddenByTheSystem() {
        icon.visibility = View.INVISIBLE
        overlay.visibility = View.GONE
        draw()
        guard.dispose()
        assertEquals(View.INVISIBLE, icon.visibility)
        assertEquals(View.GONE, overlay.visibility)
    }

    @Test fun explicitCleanupRestoresIconsAndUnregistersTheListener() {
        draw()
        guard.dispose()
        guard.dispose()
        draw()
        assertEquals(View.VISIBLE, icon.visibility)
        assertEquals(1, disposals)
    }

    @Test fun detachingDialogCleansUpBeforeTheNextPrompt() {
        draw()
        val oldObserver = root.viewTreeObserver
        controller.get().setContentView(View(controller.get()))
        assertEquals(1, disposals)
        assertEquals(View.VISIBLE, icon.visibility)
        if (oldObserver.isAlive) oldObserver.dispatchOnPreDraw()
        assertEquals(View.VISIBLE, icon.visibility)
    }

    @Test fun unknownPromptResourcesLeaveTheDialogUntouched() {
        assertNull(PromptIconGuard.attach(root, {}, { throw AssertionError(it) }))
        assertEquals(View.VISIBLE, icon.visibility)
    }
}
