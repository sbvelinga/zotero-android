package org.zotero.android.pdf.data

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.pspdfkit.document.PdfDocument
import com.pspdfkit.ui.drawable.PdfDrawable
import com.pspdfkit.ui.drawable.PdfDrawableProvider

//Nutrient/PSPDFKit has no built-in sepia rendering mode (only plain color inversion for
//"night mode"), so sepia is approximated by drawing an opaque warm parchment color over each
//page with a multiply blend: a white background becomes exactly that color, while dark text
//stays dark, since multiplying by black leaves black unchanged. This is the same technique
//most e-reader "sepia" themes use.
class SepiaPageOverlayProvider : PdfDrawableProvider() {

    override fun getDrawablesForPage(
        context: Context,
        document: PdfDocument,
        pageIndex: Int
    ): List<PdfDrawable> {
        val pageSize = document.getPageSize(pageIndex)
        return listOf(SepiaPageDrawable(pageSize.width, pageSize.height))
    }

    //Page-space rect covering the whole page; PdfDrawable positions itself via the
    //PDF-to-view transformation matrix supplied by the SDK, not via the standard
    //android.graphics.drawable.Drawable bounds mechanism, so getBounds() is never populated
    //and must not be relied on here.
    private class SepiaPageDrawable(
        private val pageWidth: Float,
        private val pageHeight: Float,
    ) : PdfDrawable() {

        private val paint = Paint().apply {
            color = SEPIA_PAGE_COLOR
            xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            isAntiAlias = false
        }
        private val pageRect = RectF(0f, 0f, pageWidth, pageHeight)
        private val viewRect = RectF()

        override fun draw(canvas: Canvas) {
            viewRect.set(pageRect)
            pdfToPageTransformation.mapRect(viewRect)
            if (viewRect.isEmpty) return
            canvas.drawRect(viewRect, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat"))
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    companion object {
        private const val SEPIA_PAGE_COLOR = 0xFFE9DAB5.toInt()
    }
}
