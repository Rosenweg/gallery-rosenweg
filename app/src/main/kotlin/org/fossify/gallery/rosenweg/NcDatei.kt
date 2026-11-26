package org.fossify.gallery.rosenweg

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import ch.rosenweg4303.cloud.Ablage
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R

/**
 * Loeschen und Umbenennen direkt in der Nextcloud - aus der Liste (lange druecken) und aus der
 * Fotoansicht (Stefan 25.11.2026: "loeschen und umbenennen kann ich nicht mehr", seit die Galerie
 * auf geteilten Telefonen nur noch die Nextcloud zeigt). Die Fotoansicht zeigt eine lokale Kopie;
 * gearbeitet wird immer auf der Datei in der Nextcloud.
 */
object NcDatei {
    /** Extra fuer Photo-/VideoActivity: Pfad der Datei in der Nextcloud. */
    const val PFAD = "rosenweg_nc_pfad"
    const val DARF_LOESCHEN = "rosenweg_nc_darf_loeschen"
    const val DARF_UMBENENNEN = "rosenweg_nc_darf_umbenennen"

    fun loeschen(a: Activity, pfad: String, fertig: () -> Unit) {
        val name = pfad.substringAfterLast('/')
        ConfirmationDialog(a, a.getString(R.string.nc_loeschen_frage, name),
            positive = org.fossify.commons.R.string.yes, negative = org.fossify.commons.R.string.no) {
            ensureBackgroundThread {
                try {
                    val d = Ablage.webDav(a) ?: throw IllegalStateException(a.getString(R.string.nc_nicht_angemeldet))
                    d.loeschen(pfad)
                    a.runOnUiThread { fertig() }
                } catch (e: Exception) {
                    a.runOnUiThread { a.toast(a.getString(R.string.nc_fehler, e.message ?: e.javaClass.simpleName)) }
                }
            }
        }
    }

    /** Fragt den neuen Namen ab (Endung bleibt), meldet den neuen Pfad. */
    fun umbenennen(a: Activity, pfad: String, fertig: (String) -> Unit) {
        val alt = pfad.substringAfterLast('/')
        val endung = if (alt.contains('.')) "." + alt.substringAfterLast('.') else ""
        val feld = EditText(a).apply {
            setText(alt.removeSuffix(endung))
            selectAll()
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val rahmen = FrameLayout(a).apply {
            val p = (20 * a.resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(feld)
        }
        a.getAlertDialogBuilder()
            .setTitle(R.string.nc_umbenennen)
            .setView(rahmen)
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                val neu = feld.text.toString().trim().replace('/', '-')
                if (neu.isEmpty() || neu + endung == alt) return@setPositiveButton
                val ziel = pfad.substringBeforeLast('/') + "/" + neu + endung
                ensureBackgroundThread {
                    try {
                        val d = Ablage.webDav(a) ?: throw IllegalStateException(a.getString(R.string.nc_nicht_angemeldet))
                        d.verschieben(pfad, ziel)   // ueberschreibt nie: gibt es den Namen schon, meldet der Server einen Fehler
                        a.runOnUiThread { fertig(ziel) }
                    } catch (e: Exception) {
                        a.runOnUiThread { a.toast(a.getString(R.string.nc_fehler, e.message ?: e.javaClass.simpleName)) }
                    }
                }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }
}
