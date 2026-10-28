package org.zotero.android.pdf.data


enum class PageScrollMode {
 JUMP, CONTINUOUS
}

enum class PageLayoutMode {
    SINGLE,
    DOUBLE,
    AUTOMATIC,
}

enum class PageScrollDirection {
    HORIZONTAL, VERTICAL
}

enum class PageFitting {
    FIT, FILL
}

enum class PageAppearanceMode {
    LIGHT, DARK, AUTOMATIC
}

//Only meaningful when the resolved appearance is dark (either DARK was picked, or AUTOMATIC
//resolved to dark because the OS is in dark mode). Lets the page content stay readable without
//forcing the harsher inverted "night mode" look on every dark-mode session.
enum class DarkModePageColor {
    INVERTED, SEPIA, WHITE
}


data class PDFSettings(
    var transition: PageScrollMode,
    var pageMode: PageLayoutMode,
    var direction: PageScrollDirection,
    var pageFitting: PageFitting,
    var appearanceMode: PageAppearanceMode,
    var idleTimerDisabled: Boolean,
    //Nullable rather than defaulted, because this class is deserialized with Gson, which
    //allocates instances without running the Kotlin constructor and so does not apply
    //Kotlin default parameter values for fields absent from an older stored settings blob.
    //Use resolvedDarkModePageColor() rather than reading this directly.
    var darkModePageColor: DarkModePageColor? = null,
) {
    fun resolvedDarkModePageColor(): DarkModePageColor = darkModePageColor ?: DarkModePageColor.INVERTED

    companion object {
        fun default(): PDFSettings {
            return PDFSettings(
                transition = PageScrollMode.CONTINUOUS,
                pageMode = PageLayoutMode.AUTOMATIC,
                direction = PageScrollDirection.HORIZONTAL,
                pageFitting = PageFitting.FIT,
                appearanceMode = PageAppearanceMode.AUTOMATIC,
                idleTimerDisabled = false,
                darkModePageColor = DarkModePageColor.INVERTED,
            )
        }
    }
}