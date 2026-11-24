package org.fossify.gallery.views

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** Rosenweg: quadratische Kachel fuer die Nextcloud-Ansicht. */
class QuadrantFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(widthMeasureSpec, widthMeasureSpec)
}
