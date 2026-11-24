package org.fossify.gallery.helpers

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.TextViewCompat
import android.content.res.ColorStateList
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.getDialogBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getTextSize
import org.fossify.commons.helpers.MEDIUM_ALPHA
import org.fossify.commons.views.MyTextView
import org.fossify.gallery.R

/**
 * Ort-Chip ("Telefon" / "Nextcloud") im Stil der Pfadleiste. [aktiv] = der Ort,
 * an dem man gerade ist (gefuellt in der Akzentfarbe).
 */
fun MyTextView.alsOrtChip(aktiv: Boolean, symbol: Int = R.drawable.ic_cloud_vector) {
    val primaer = context.getProperPrimaryColor()
    val text = if (aktiv) context.getDialogBackgroundColor() else context.getProperTextColor()
    (background.mutate() as? RippleDrawable)?.let { r ->
        (r.getDrawable(0) as? GradientDrawable)?.apply {
            setColor(if (aktiv) primaer else context.getDialogBackgroundColor())
            setStroke(context.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.one_dp), primaer.adjustAlpha(MEDIUM_ALPHA))
        }
    }
    setTextColor(text)
    setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getTextSize())
    elevation = context.resources.getDimension(org.fossify.commons.R.dimen.one_dp)
    setCompoundDrawablesRelativeWithIntrinsicBounds(AppCompatResources.getDrawable(context, symbol), null, null, null)
    compoundDrawablePadding = context.resources.getDimensionPixelSize(org.fossify.commons.R.dimen.small_margin)
    TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(text))
}
