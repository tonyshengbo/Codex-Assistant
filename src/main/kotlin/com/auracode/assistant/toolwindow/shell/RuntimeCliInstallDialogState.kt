package com.auracode.assistant.toolwindow.shell

/** Stores one package-manager option rendered in the runtime install dialog. */
internal data class RuntimeCliInstallOption(
    val packageManagerId: String,
    val commandPreview: String,
    val available: Boolean,
)

/** Stores the install dialog state for one runtime engine. */
internal data class RuntimeCliInstallDialogState(
    val engineId: String,
    val options: List<RuntimeCliInstallOption>,
    val selectedPackageManagerId: String,
) {
    /** Returns the currently selected install option when one still exists. */
    fun selectedOption(): RuntimeCliInstallOption? {
        return options.firstOrNull { it.packageManagerId == selectedPackageManagerId }
    }
}
