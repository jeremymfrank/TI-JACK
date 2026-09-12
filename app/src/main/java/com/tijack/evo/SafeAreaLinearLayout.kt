package com.tijack.evo

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.WindowInsets
import android.widget.LinearLayout

/**
 * Root layout that keeps bottom controls above Android 15's enforced
 * edge-to-edge navigation area while preserving the compact layout on
 * older Android releases where the window is already inset for system bars.
 */
class SafeAreaLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var baseBottomPadding: Int? = null

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (baseBottomPadding == null) {
            baseBottomPadding = paddingBottom
        }

        val navBottom = if (Build.VERSION.SDK_INT >= 35) {
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        } else {
            0
        }
        val targetBottom = requireNotNull(baseBottomPadding) + navBottom
        if (paddingBottom != targetBottom) {
            setPadding(paddingLeft, paddingTop, paddingRight, targetBottom)
        }

        return super.onApplyWindowInsets(insets)
    }
}
