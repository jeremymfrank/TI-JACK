package com.tijack.evo

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.WindowInsets
import android.widget.LinearLayout

/**
 * Root layout that keeps TI-JACK clear of Android 15 edge-to-edge system UI.
 *
 * Android 15 enforces edge-to-edge for apps targeting API 35, so both the
 * status-bar/display-cutout area at the top and the navigation area at the
 * bottom must be added to the layout's normal padding. Older Android releases
 * continue to use the platform's traditional fitted-window behavior.
 */
class SafeAreaLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var baseTopPadding: Int? = null
    private var baseBottomPadding: Int? = null

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (baseTopPadding == null) baseTopPadding = paddingTop
        if (baseBottomPadding == null) baseBottomPadding = paddingBottom

        val edgeToEdge = Build.VERSION.SDK_INT >= 35
        val safeTop = if (edgeToEdge) {
            val statusTop = insets.getInsets(WindowInsets.Type.statusBars()).top
            val cutoutTop = insets.displayCutout?.safeInsetTop ?: 0
            maxOf(statusTop, cutoutTop)
        } else {
            0
        }
        val navBottom = if (edgeToEdge) {
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        } else {
            0
        }

        val targetTop = requireNotNull(baseTopPadding) + safeTop
        val targetBottom = requireNotNull(baseBottomPadding) + navBottom
        if (paddingTop != targetTop || paddingBottom != targetBottom) {
            setPadding(paddingLeft, targetTop, paddingRight, targetBottom)
        }

        return super.onApplyWindowInsets(insets)
    }
}
