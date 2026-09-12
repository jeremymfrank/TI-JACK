package com.tijack.evo

import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import android.widget.TextView

/**
 * Transfer action button that keeps the user-facing terminology as TRANSMIT.
 *
 * MainActivity still uses the older internal SEND/SAVE labels while it tracks
 * transfer direction and selection counts. Normalizing here keeps both
 * directions visually consistent without changing transfer behavior.
 */
class TransmitButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {

    override fun setText(text: CharSequence?, type: TextView.BufferType?) {
        super.setText(normalizeLabel(text), type)
    }

    companion object {
        private fun normalizeLabel(text: CharSequence?): CharSequence? {
            val value = text?.toString() ?: return null
            return when {
                value.startsWith("SEND ") ->
                    "TRANSMIT " + value.removePrefix("SEND ")
                value.startsWith("← SAVE ") ->
                    "← TRANSMIT " + value.removePrefix("← SAVE ")
                else -> text
            }
        }
    }
}
