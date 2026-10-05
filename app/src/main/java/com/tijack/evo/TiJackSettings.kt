package com.tijack.evo

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Build

internal object TiJackSettings {
    private const val PREFS = "ti_jack_android"
    private const val KEY_THEME = "setting_theme"
    private const val KEY_REPEAT_SEND = "setting_repeat_send"
    private const val KEY_KEEP_AWAKE = "setting_keep_awake"
    private const val KEY_COMPLETION = "setting_completion_alert"
    private const val KEY_SEND_CONFLICT = "setting_send_conflict"
    private const val KEY_RECEIVE_CONFLICT = "setting_receive_conflict"
    private const val KEY_DEFAULT_MEMORY = "setting_default_memory"
    private const val KEY_CONFIRM_DESTRUCTIVE = "setting_confirm_destructive"
    private const val KEY_SORT = "setting_calculator_sort"
    private const val KEY_SHOW_GENERATED = "setting_show_generated_frames"
    private const val KEY_SIMPLE_CLASSROOM = "setting_simple_classroom"

    val settingKeys = listOf(
        KEY_THEME, KEY_REPEAT_SEND, KEY_KEEP_AWAKE, KEY_COMPLETION,
        KEY_SEND_CONFLICT, KEY_RECEIVE_CONFLICT, KEY_DEFAULT_MEMORY,
        KEY_CONFIRM_DESTRUCTIVE, KEY_SORT, KEY_SHOW_GENERATED,
        KEY_SIMPLE_CLASSROOM
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun versionName(context: Context): String = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        info.versionName ?: "unknown"
    } catch (_: Throwable) {
        "unknown"
    }

    fun themeStyle(context: Context): Int = when (prefs(context).getString(KEY_THEME, "amber")) {
        "blue" -> R.style.Theme_TIJack_Blue
        "green" -> R.style.Theme_TIJack_Green
        "contrast" -> R.style.Theme_TIJack_HighContrast
        "light" -> R.style.Theme_TIJack_Light
        else -> R.style.Theme_TIJack_Amber
    }

    fun repeatSend(context: Context) = prefs(context).getBoolean(KEY_REPEAT_SEND, false)
    fun keepAwake(context: Context) = prefs(context).getBoolean(KEY_KEEP_AWAKE, false)
    fun completionAlert(context: Context) = prefs(context).getString(KEY_COMPLETION, "off") ?: "off"
    fun sendConflict(context: Context) = prefs(context).getString(KEY_SEND_CONFLICT, "ask") ?: "ask"
    fun receiveConflict(context: Context) = prefs(context).getString(KEY_RECEIVE_CONFLICT, "ask") ?: "ask"
    fun defaultMemory(context: Context) = prefs(context).getString(KEY_DEFAULT_MEMORY, "auto") ?: "auto"
    fun confirmDestructive(context: Context) = prefs(context).getBoolean(KEY_CONFIRM_DESTRUCTIVE, true)
    fun calculatorSort(context: Context) = prefs(context).getString(KEY_SORT, "type") ?: "type"
    fun showGeneratedFrames(context: Context) = prefs(context).getBoolean(KEY_SHOW_GENERATED, false)
    fun simpleClassroom(context: Context) = prefs(context).getBoolean(KEY_SIMPLE_CLASSROOM, false)

    fun show(activity: Activity) {
        val items = arrayOf(
            "Appearance",
            "Classroom",
            "Transfers",
            "Safety",
            "Display",
            "Help",
            "About",
            "Reset settings"
        )
        AlertDialog.Builder(activity)
            .setTitle("TI-JACK v${versionName(activity)}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showTheme(activity)
                    1 -> showClassroom(activity)
                    2 -> showTransfers(activity)
                    3 -> showSafety(activity)
                    4 -> showDisplay(activity)
                    5 -> showHelp(activity)
                    6 -> showAbout(activity)
                    7 -> confirmReset(activity)
                }
            }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun showTheme(activity: Activity) {
        val labels = arrayOf(
            "Amber Terminal",
            "TI Blue",
            "Classic Green",
            "High Contrast",
            "Light Classroom"
        )
        val values = arrayOf("amber", "blue", "green", "contrast", "light")
        val current = values.indexOf(prefs(activity).getString(KEY_THEME, "amber")).coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle("Color theme")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                prefs(activity).edit().putString(KEY_THEME, values[which]).apply()
                dialog.dismiss()
                activity.recreate()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showClassroom(activity: Activity) {
        val labels = arrayOf(
            "Repeat Send — keep Android files selected",
            "Keep screen awake",
            "Simple Classroom UI"
        )
        val checked = booleanArrayOf(
            repeatSend(activity),
            keepAwake(activity),
            simpleClassroom(activity)
        )
        AlertDialog.Builder(activity)
            .setTitle("Classroom")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("SAVE") { _, _ ->
                prefs(activity).edit()
                    .putBoolean(KEY_REPEAT_SEND, checked[0])
                    .putBoolean(KEY_KEEP_AWAKE, checked[1])
                    .putBoolean(KEY_SIMPLE_CLASSROOM, checked[2])
                    .apply()
                showCompletionAlert(activity)
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showCompletionAlert(activity: Activity) {
        val labels = arrayOf("Off", "Vibrate", "Sound + vibrate")
        val values = arrayOf("off", "vibrate", "sound")
        val current = values.indexOf(completionAlert(activity)).coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle("Transfer finished alert")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                prefs(activity).edit().putString(KEY_COMPLETION, values[which]).apply()
                dialog.dismiss()
                activity.recreate()
            }
            .setNegativeButton("SKIP") { _, _ -> activity.recreate() }
            .show()
    }

    private fun showTransfers(activity: Activity) {
        val items = arrayOf(
            "Send duplicates: ${sendConflict(activity).uppercase()}",
            "Receive duplicates: ${receiveConflict(activity).replace('_', ' ').uppercase()}",
            "Default memory: ${defaultMemory(activity).uppercase()}"
        )
        AlertDialog.Builder(activity)
            .setTitle("Transfers")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> chooseValue(
                        activity, "Send duplicates",
                        arrayOf("Ask", "Replace", "Skip"),
                        arrayOf("ask", "replace", "skip"),
                        KEY_SEND_CONFLICT,
                        sendConflict(activity)
                    )
                    1 -> chooseValue(
                        activity, "Receive duplicates",
                        arrayOf("Ask", "Replace", "Skip", "Rename Copy"),
                        arrayOf("ask", "replace", "skip", "rename_copy"),
                        KEY_RECEIVE_CONFLICT,
                        receiveConflict(activity)
                    )
                    2 -> chooseValue(
                        activity, "Default calculator memory",
                        arrayOf("Auto", "Archive", "RAM"),
                        arrayOf("auto", "archive", "ram"),
                        KEY_DEFAULT_MEMORY,
                        defaultMemory(activity)
                    )
                }
            }
            .setNegativeButton("BACK", null)
            .show()
    }

    private fun showSafety(activity: Activity) {
        val labels = arrayOf("Confirm delete / cleanup actions")
        val checked = booleanArrayOf(confirmDestructive(activity))
        AlertDialog.Builder(activity)
            .setTitle("Safety")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("SAVE") { _, _ ->
                prefs(activity).edit().putBoolean(KEY_CONFIRM_DESTRUCTIVE, checked[0]).apply()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showDisplay(activity: Activity) {
        val items = arrayOf(
            "Calculator sort: ${calculatorSort(activity).replace('_', ' ').uppercase()}",
            if (showGeneratedFrames(activity)) "JACKVIEW frame variables: SHOWN"
            else "JACKVIEW frame variables: HIDDEN"
        )
        AlertDialog.Builder(activity)
            .setTitle("Display")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> chooseValue(
                        activity, "Calculator sorting",
                        arrayOf("Type then name", "Name", "Size", "Memory"),
                        arrayOf("type", "name", "size", "memory"),
                        KEY_SORT,
                        calculatorSort(activity),
                        recreate = true
                    )
                    1 -> {
                        prefs(activity).edit()
                            .putBoolean(KEY_SHOW_GENERATED, !showGeneratedFrames(activity))
                            .apply()
                        activity.recreate()
                    }
                }
            }
            .setNegativeButton("BACK", null)
            .show()
    }

    private fun chooseValue(
        activity: Activity,
        title: String,
        labels: Array<String>,
        values: Array<String>,
        key: String,
        currentValue: String,
        recreate: Boolean = false
    ) {
        val current = values.indexOf(currentValue).coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                prefs(activity).edit().putString(key, values[which]).apply()
                dialog.dismiss()
                if (recreate) activity.recreate()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showHelp(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("TI-JACK Help")
            .setMessage(TiJackHelp.text(activity))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showAbout(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("TI-JACK v${versionName(activity)}")
            .setMessage(
                "Universal file transfer for TI calculators.\n\n" +
                    "Project: Jawatech / jeremymfrank\n" +
                    "Current transport: TI-84 Evo\n" +
                    "Next transport target: TI-Nspire CX II\n" +
                    "PC target: Windows first, portable transport core.\n\n" +
                    "Independent project; not affiliated with or endorsed by Texas Instruments."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun confirmReset(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("Reset TI-JACK settings?")
            .setMessage("Theme and classroom/transfer preferences will return to defaults. Your selected Android folder and files are not changed.")
            .setPositiveButton("RESET") { _, _ ->
                val editor = prefs(activity).edit()
                settingKeys.forEach { editor.remove(it) }
                editor.apply()
                activity.recreate()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }
}
