package org.fossify.gallery.rosenweg

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * Auswahl fuer andere Apps aus der Nextcloud (Stefan 27.11.2026: "Auf privaten Telefonen nur die
 * Fotos von Nextcloud anzeigen"). "Foto auswaehlen" aus WhatsApp, Messenger, Hintergrundbild usw.
 * (ACTION_PICK / ACTION_GET_CONTENT) landet in der MainActivity; die reicht mit diesen Extras an die
 * NextcloudGalerieActivity weiter (FLAG_ACTIVITY_FORWARD_RESULT). Gewaehlte Dateien werden als lokale
 * Kopie geladen ([ch.rosenweg4303.cloud.Ablage.lokaleKopie]) und als content-URI des eigenen
 * FileProviders mit Leserecht zurueckgegeben.
 */
data class NcAuswahl(
    val fotos: Boolean,
    val videos: Boolean,
    val mehrere: Boolean,
    /** EXTRA_OUTPUT der aufrufenden App (mit Schreibrecht): die Datei dorthin kopieren. */
    val ausgabe: Uri?,
) {
    fun passt(name: String): Boolean {
        val endung = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return (fotos && endung in FOTO) || (videos && endung in VIDEO)
    }

    /** Kopiert bei [ausgabe] die Datei dorthin (blockiert) - im Hintergrund aufrufen. */
    fun ergebnis(c: Context, dateien: List<File>): Intent {
        val ziel = ausgabe
        if (ziel != null && dateien.size == 1) {
            c.contentResolver.openOutputStream(ziel)?.use { raus -> dateien[0].inputStream().use { it.copyTo(raus) } }
            return Intent().setData(ziel).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val uris = dateien.map { FileProvider.getUriForFile(c, "${c.packageName}.provider", it) }
        val typen = dateien.map { mime(it.name) }.distinct().toTypedArray()
        val clip = ClipData("Rosenweg", typen, ClipData.Item(uris[0]))
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        return Intent().setDataAndType(uris[0], mime(dateien[0].name)).also {
            it.clipData = clip
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun extras(i: Intent): Intent = i.putExtra(AUSWAHL, true)
        .putExtra(FOTOS, fotos)
        .putExtra(VIDEOS, videos)
        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, mehrere)
        .also { if (ausgabe != null) it.putExtra(MediaStore.EXTRA_OUTPUT, ausgabe) }

    companion object {
        private const val AUSWAHL = "rosenweg_nc_auswahl"
        private const val FOTOS = "rosenweg_nc_auswahl_fotos"
        private const val VIDEOS = "rosenweg_nc_auswahl_videos"
        val FOTO = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "avif")
        val VIDEO = setOf("mp4", "3gp", "mkv", "webm", "mov")

        /** Aus den Extras der NextcloudGalerieActivity; null = normales Anschauen. */
        fun aus(i: Intent): NcAuswahl? {
            if (!i.getBooleanExtra(AUSWAHL, false)) return null
            @Suppress("DEPRECATION")
            return NcAuswahl(
                fotos = i.getBooleanExtra(FOTOS, true),
                videos = i.getBooleanExtra(VIDEOS, true),
                mehrere = i.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false),
                ausgabe = i.getParcelableExtra(MediaStore.EXTRA_OUTPUT),
            )
        }

        fun mime(name: String): String {
            val endung = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(endung)
                ?: if (endung in VIDEO) "video/*" else "image/*"
        }
    }
}
