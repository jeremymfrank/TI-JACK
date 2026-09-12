package com.tijack.evo

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView

/**
 * Keeps connection guidance readable for non-technical users while allowing
 * the transport layer to retain its more precise internal wording.
 */
class FriendlyDiagnosticTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : TextView(context, attrs, defStyleAttr) {

    override fun setText(text: CharSequence?, type: BufferType?) {
        val value = text?.toString()
        val friendly = when (value) {
            "Connect with the OTG/host adapter if Android does not enumerate the Evo." ->
                "Make sure the USB adapter is plugged into your phone, then reconnect the calculator."
            else -> text
        }
        super.setText(friendly, type)
    }
}
