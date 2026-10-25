package com.way.facebiometricfix

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver

/** Hides only the known ColorOS prompt icons that cover a usable confirmation button. */
internal class PromptIconGuard(
    val root: View,
    private val confirm: View,
    icons: List<View>,
    private val onDisposed: (PromptIconGuard) -> Unit,
    private val onError: (Throwable) -> Unit
) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private class Icon(val view: View, var hidden: Boolean = false) {
        fun restore() {
            // Do not overwrite a subsequent native transition to GONE or VISIBLE.
            if (hidden && view.visibility == View.INVISIBLE) view.visibility = View.VISIBLE
            hidden = false
        }
    }

    private val icons = icons.map { Icon(it) }
    private val observer = root.viewTreeObserver
    private val confirmBounds = Rect()
    private val iconBounds = Rect()
    private val location = IntArray(2)
    private var disposed = false

    init {
        root.addOnAttachStateChangeListener(this)
        observer.addOnPreDrawListener(this)
    }

    override fun onPreDraw(): Boolean {
        if (disposed) return true
        runCatching {
            if (!root.isAttachedToWindow) {
                dispose()
                return true
            }
            val canConfirm = confirm.isShown && confirm.isEnabled && confirm.alpha > 0f &&
                confirm.getGlobalVisibleRect(confirmBounds)
            for (icon in icons) {
                val view = icon.view
                // getGlobalVisibleRect() uses window-root coordinates and is empty
                // for INVISIBLE icons. Keep both rectangles in that coordinate space
                // using layout bounds, including when the window is offset on screen.
                view.getLocationInWindow(location)
                iconBounds.set(location[0], location[1],
                    location[0] + view.width, location[1] + view.height)
                if (canConfirm && Rect.intersects(confirmBounds, iconBounds)) {
                    if (view.visibility == View.VISIBLE) {
                        icon.hidden = true
                        // GONE changes ColorOS ConstraintLayout geometry. INVISIBLE
                        // also suppresses input/accessibility without changing flags.
                        view.visibility = View.INVISIBLE
                    }
                } else {
                    icon.restore()
                }
            }
        }.onFailure {
            dispose()
            onError(it)
        }
        return true
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        // Detach can replace root.viewTreeObserver; remove from the original one.
        if (observer.isAlive) observer.removeOnPreDrawListener(this)
        root.removeOnAttachStateChangeListener(this)
        icons.forEach { it.restore() }
        onDisposed(this)
    }

    override fun onViewAttachedToWindow(view: View) = Unit

    override fun onViewDetachedFromWindow(view: View) = dispose()

    companion object {
        fun attach(
            root: View,
            onDisposed: (PromptIconGuard) -> Unit,
            onError: (Throwable) -> Unit
        ): PromptIconGuard? {
            val observer = root.viewTreeObserver
            if (!root.isAttachedToWindow || !observer.isAlive) return null
            fun find(name: String): View? {
                val id = root.resources.getIdentifier(name, "id", "com.android.systemui")
                return if (id == 0) null else root.findViewById(id)
            }
            val confirm = find("button_confirm") ?: return null
            val icons = listOfNotNull(find("biometric_icon"), find("biometric_icon_overlay"))
            if (icons.isEmpty()) return null
            return PromptIconGuard(root, confirm, icons, onDisposed, onError)
        }
    }
}
