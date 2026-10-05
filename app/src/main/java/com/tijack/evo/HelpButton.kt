package com.tijack.evo

import android.content.Context

internal object TiJackHelp {
    fun text(context: Context): String = """
        VERSION
        ${TiJackSettings.versionName(context)}

        QUICK START
        • Connect the calculator with the phone-side USB host/OTG adapter.
        • Choose an Android folder, select files, then SEND or SAVE.
        • Use the gear for classroom, duplicate, memory, display, and theme settings.

        CLASSROOM
        • Repeat Send keeps the same Android files selected after a successful send so the next student calculator can receive them immediately.
        • Simple Classroom UI hides Archive/RAM/GIF-maintenance controls without disabling transfers.
        • Duplicate policies can Ask, Replace, or Skip. Received files can also be renamed as a copy.

        EVO MEMORY
        • Calculator rows show RAM or ARC.
        • EST FREE is a conservative estimate, not the calculator's hidden exact Memory-screen counter.
        • Select variables and tap ARCHIVE or RAM to move them with read-back verification.
        • CLEAN GIF finds groups of unreferenced TI-JACK GIF frame variables.
        • JACKVIEW media is sent directly to Archive and preflight-checked before transfer.

        TRANSFER & CONVERSION
        • Current Evo files transfer normally.
        • Supported legacy .8xp programs are converted locally and validated before transfer.
        • Ordinary PNG/JPG/JPEG/WebP files become JACKVIEW media; Image1..Image7 names use Evo background-image slots.
        • GIFs become IM8C frame AppVars plus a TIJGIF01 manifest and may be reduced to fit safe media limits.
        • Upload success requires calculator directory verification and byte-for-byte read-back.

        JACKVIEW CONTROLS
        • Browser: UP/DOWN select, ENTER open, CLEAR exit.
        • Image: LEFT/RIGHT previous/next, CLEAR return.
        • GIF: UP faster, DOWN slower, ENTER pause/resume, LEFT/RIGHT previous/next, CLEAR return.

        CONNECTION TIPS
        • Put the USB-C host/OTG adapter on the phone side.
        • If Android does not enumerate the calculator, unplug both ends and reconnect with the adapter still on the phone side.
        • A direct USB-C-to-USB-C cable may leave some phones in the wrong USB role.

        ROADMAP
        • TI-Nspire CX II / CX II CAS USB file management is the next calculator transport target.
        • A Windows TI-JACK desktop application is planned with the same calculator-backend model.
    """.trimIndent()
}
