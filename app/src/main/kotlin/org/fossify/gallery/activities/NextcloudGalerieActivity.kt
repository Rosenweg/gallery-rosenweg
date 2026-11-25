package org.fossify.gallery.activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import java.io.File
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import ch.rosenweg4303.cloud.Ablage
import ch.rosenweg4303.cloud.AnmeldenActivity
import ch.rosenweg4303.cloud.Eintrag
import ch.rosenweg4303.cloud.Hochladen
import ch.rosenweg4303.cloud.NextcloudFehler
import ch.rosenweg4303.cloud.WebDav
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ActivityNextcloudGalerieBinding
import org.fossify.gallery.databinding.ItemNcBildBinding
import org.fossify.gallery.helpers.alsOrtChip
import java.util.Locale

/**
 * Rosenweg-Galerie: Fotos und Videos der Person in der Rosenweg-Nextcloud
 * (Regel 6: die App zeigt ihre Daten aus der Nextcloud).
 *
 * Start: Persönlich/Kamera mit allen Monaten, neueste zuerst. Zurueck geht es
 * zu Persönlich und von dort in jeden Ordner. Vorschaubilder liefert die
 * Nextcloud; geoeffnet wird im Bildbetrachter der Galerie (Kopie im Cache).
 */
class NextcloudGalerieActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityNextcloudGalerieBinding::inflate)
    private var dav: WebDav? = null
    private var pfad = START
    private var nachAnsichtLaden = false
    private var eintraege = listOf<Eintrag>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        pfad = savedInstanceState?.getString(PFAD) ?: START
        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(ncList))
            setupMaterialScrollListener(ncList, ncAppbar)
            ncList.adapter = Kacheln()
            ncRefresh.setOnRefreshListener { laden() }
            ncLogin.paintFlags = ncLogin.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            ncLogin.setOnClickListener { startActivity(Intent(this@NextcloudGalerieActivity, AnmeldenActivity::class.java)) }
            ncOrtIntern.setOnClickListener {
                // ohne Geraet: zur Telefon-Ansicht (MainActivity), auch wenn wir direkt gestartet wurden
                startActivity(Intent(this@NextcloudGalerieActivity, MainActivity::class.java)
                    .putExtra(MainActivity.ROSENWEG_TELEFON_ANSICHT, true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
            // Stefan 25.11.2026: auf geteilten Telefonen (Rosenweg Geraet) gibt es nur die Nextcloud -
            // lokal liegt dort nur Zwischenspeicher, der beim Abmelden geloescht wird.
            ncOrte.beVisibleIf(!ch.rosenweg4303.cloud.RosenwegAnmeldung.geraetDa(this@NextcloudGalerieActivity))
            ncWartet.setOnClickListener { wartendeAnstossen() }
        }
    }

    override fun onResume() {
        super.onResume()
        pfeil()
        binding.ncPlaceholder.setTextColor(getProperTextColor())
        binding.ncLogin.setTextColor(getProperPrimaryColor())
        binding.ncOrtIntern.alsOrtChip(aktiv = false, symbol = org.fossify.commons.R.drawable.ic_phone_vector)
        binding.ncOrtNextcloud.alsOrtChip(aktiv = true)
        wartendeZeigen()
        handler.removeCallbacks(wartendePruefen)
        handler.postDelayed(wartendePruefen, 10_000)
        val vorher = dav
        dav = Ablage.webDav(this)
        if (dav == null) {
            eintraege = emptyList()
            anzeigen()
        } else if (vorher == null || eintraege.isEmpty() || nachAnsichtLaden) {
            nachAnsichtLaden = false   // in der Fotoansicht geloescht/umbenannt? Liste frisch holen
            laden()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(PFAD, pfad)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!hoch()) super.onBackPressed()
    }

    /**
     * Mit Rosenweg Geraet ist diese Ansicht die Galerie selbst (Stefan 25.11.2026: "direkt Nextcloud
     * mit einem Pfeil zurueck - da ist was schief"). Auf der Startseite also kein Pfeil, Zurueck
     * schliesst; nur in Unterordnern geht es mit dem Pfeil eine Ebene hoch.
     */
    private fun alsStart() = ch.rosenweg4303.cloud.RosenwegAnmeldung.geraetDa(this)

    private fun pfeil() {
        val oben = alsStart() && pfad == START
        setupTopAppBar(binding.ncAppbar, if (oben) NavigationIcon.None else NavigationIcon.Arrow)
        if (oben) binding.ncToolbar.navigationIcon = null
        binding.ncToolbar.setNavigationOnClickListener { if (!hoch()) finish() }
    }

    private fun hoch(): Boolean {
        if (dav == null || pfad == Hochladen.PERSOENLICH) return false
        if (alsStart() && pfad == START) return false
        pfad = if (pfad == START) Hochladen.PERSOENLICH else pfad.substringBeforeLast('/', Hochladen.PERSOENLICH)
        eintraege = emptyList()
        laden()
        return true
    }

    private fun anzeigen() {
        val angemeldet = dav != null
        binding.apply {
            pfeil()
            ncToolbar.title = if (pfad == START) getString(R.string.nc_titel) + " · Kamera" else pfad.substringAfterLast('/')
            ncRefresh.isEnabled = angemeldet
            ncLogin.beVisibleIf(!angemeldet)
            ncPlaceholder.beVisibleIf(!angemeldet || eintraege.isEmpty())
            ncPlaceholder.text = getString(if (!angemeldet) R.string.nc_nicht_angemeldet else R.string.nc_leer)
            ncList.adapter?.notifyDataSetChanged()
        }
    }

    private fun laden() {
        val d = dav ?: return
        val ziel = pfad
        binding.ncRefresh.isRefreshing = true
        ensureBackgroundThread {
            try {
                val liste = if (ziel == START) {
                    // Kamera: alle Monate zusammen, neueste zuerst
                    d.alleDateien(ziel).filter { istMedium(it) }.sortedByDescending { it.geaendert }
                } else {
                    d.liste(ziel).filter { (it.istOrdner && !it.name.startsWith(".")) || istMedium(it) }
                        .sortedWith(compareBy<Eintrag> { !it.istOrdner }.thenByDescending { if (it.istOrdner) 0 else it.geaendert }
                            .thenBy { it.name.lowercase(Locale.ROOT) })
                }
                runOnUiThread {
                    if (ziel == pfad) eintraege = liste
                    binding.ncRefresh.isRefreshing = false
                    anzeigen()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.ncRefresh.isRefreshing = false
                    fehler(e)
                    anzeigen()
                }
            }
        }
    }

    private fun oeffnen(e: Eintrag) {
        if (e.istOrdner) {
            pfad = e.pfad
            eintraege = emptyList()
            anzeigen()
            laden()
            return
        }
        toast(getString(R.string.nc_wird_geladen, e.name))
        ensureBackgroundThread {
            try {
                val datei = Ablage.lokaleKopie(this, e.pfad)
                val uri = FileProvider.getUriForFile(this, "$packageName.provider", datei)
                val video = istVideo(e)
                val ziel = if (video) VideoActivity::class.java else PhotoActivity::class.java
                runOnUiThread {
                    try {
                        startActivity(
                            Intent(this, ziel).setDataAndType(uri, if (video) "video/*" else "image/*")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .putExtra(org.fossify.gallery.rosenweg.NcDatei.PFAD, e.pfad)
                                .putExtra(org.fossify.gallery.rosenweg.NcDatei.DARF_LOESCHEN, e.darfLoeschen)
                                .putExtra(org.fossify.gallery.rosenweg.NcDatei.DARF_UMBENENNEN, e.darfUmbenennen)
                        )
                        nachAnsichtLaden = true
                    } catch (ex: ActivityNotFoundException) {
                        toast(org.fossify.commons.R.string.no_app_found)
                    }
                }
            } catch (ex: Exception) {
                runOnUiThread { fehler(ex) }
            }
        }
    }

    private fun aktionen(e: Eintrag) {
        val d = dav ?: return
        val punkte = mutableListOf<Pair<String, () -> Unit>>()
        if (!e.istOrdner && endung(e) in BEARBEITBAR) punkte += getString(R.string.nc_bearbeiten) to { bearbeiten(e) }
        if (!e.istOrdner && e.fileId > 0) punkte += getString(R.string.nc_im_browser) to {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.webAdresse(e))).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (ex: ActivityNotFoundException) {
                toast(org.fossify.commons.R.string.no_app_found)
            }
        }
        if (!e.istOrdner && e.darfUmbenennen) punkte += getString(R.string.nc_umbenennen) to {
            org.fossify.gallery.rosenweg.NcDatei.umbenennen(this, e.pfad) { laden() }
        }
        if (e.darfLoeschen) punkte += getString(R.string.nc_loeschen) to {
            org.fossify.gallery.rosenweg.NcDatei.loeschen(this, e.pfad) { laden() }
        }
        if (punkte.isEmpty()) return
        getAlertDialogBuilder().setItems(punkte.map { it.first }.toTypedArray()) { _, i -> punkte[i].second() }
            .setTitle(e.name).show()
    }

    // ─── Was noch aufs Hochladen wartet (Kamera + Galerie) ─────────────────────
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val wartendePruefen = object : Runnable {
        override fun run() {
            wartendeZeigen()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(wartendePruefen)
        super.onPause()
    }

    /** Eigene Warteschlange + die der Kamera (ueber deren HochladenStatus, nur Rosenweg-Signatur). */
    private fun wartendeZeigen() {
        ensureBackgroundThread {
            var offen = Hochladen.ausstehend(this)
            var fehler = Hochladen.fehlgeschlagen(this)
            for (paket in HOCHLADER) {
                try {
                    contentResolver.call(Uri.parse("content://$paket.rosenweg.hochladen"), "status", null, null)?.let {
                        offen += it.getInt("ausstehend"); fehler += it.getInt("fehlgeschlagen")
                    }
                } catch (e: Exception) {
                    // App nicht installiert oder alte Fassung: nichts anzeigen
                }
            }
            runOnUiThread {
                binding.ncWartet.setTextColor(getProperTextColor())
                binding.ncWartet.text = when {
                    fehler > 0 -> getString(R.string.nc_wartet_fehler, fehler)
                    offen > 0 -> getString(R.string.nc_wartet, offen)
                    else -> ""
                }
                binding.ncWartet.beVisibleIf(offen + fehler > 0)
            }
        }
    }

    private fun wartendeAnstossen() {
        Hochladen.anstossen(this, fehlerNochmal = true)
        ensureBackgroundThread {
            for (paket in HOCHLADER) {
                try { contentResolver.call(Uri.parse("content://$paket.rosenweg.hochladen"), "anstossen", null, null) } catch (e: Exception) { }
            }
        }
        toast(R.string.nc_wird_geladen_kurz)
    }

    // ─── Bearbeiten (Stefan 25.11.2026): Editor der Galerie, Ergebnis als neue Datei daneben ───
    private var bearbeitet: Pair<Eintrag, File>? = null

    private fun bearbeiten(e: Eintrag) {
        toast(getString(R.string.nc_wird_geladen, e.name))
        ensureBackgroundThread {
            try {
                val quelle = Ablage.lokaleKopie(this, e.pfad)
                val ordner = File(cacheDir, "nc-bearbeitet").apply { mkdirs() }
                val ziel = File(ordner, bearbeitetName(e.name)).apply { delete(); createNewFile() }
                val rein = FileProvider.getUriForFile(this, "$packageName.provider", quelle)
                val raus = FileProvider.getUriForFile(this, "$packageName.provider", ziel)
                runOnUiThread {
                    bearbeitet = e to ziel
                    val i = Intent(Intent.ACTION_EDIT).setClass(this, EditActivity::class.java)
                        .setDataAndType(rein, "image/*")
                        .putExtra(MediaStore.EXTRA_OUTPUT, raus)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    @Suppress("DEPRECATION")
                    startActivityForResult(i, BEARBEITEN)
                }
            } catch (ex: Exception) {
                runOnUiThread { fehler(ex) }
            }
        }
    }

    @Deprecated("Activity-Result-API ist hier nicht noetig")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != BEARBEITEN) return
        val (e, ziel) = bearbeitet ?: return
        bearbeitet = null
        // Der Editor meldet nicht immer RESULT_OK: massgebend ist, ob er etwas geschrieben hat
        if (!ziel.exists() || ziel.length() == 0L) {
            toast(R.string.nc_bearbeitet_nicht_gespeichert)
            return
        }
        val d = dav ?: return
        val ordnerPfad = e.pfad.substringBeforeLast('/', "")
        ensureBackgroundThread {
            try {
                var name = ziel.name
                var n = 2
                while (true) {
                    try {
                        d.hochladen("$ordnerPfad/$name", { ziel.inputStream() }, ziel.length(),
                            if (endung(e) == "png") "image/png" else "image/jpeg", nurNeu = true)
                        break
                    } catch (ex: NextcloudFehler) {
                        // den Namen gibt es schon: nummerieren statt ueberschreiben
                        if (ex.code != 412 || n > 20) throw ex
                        name = bearbeitetName(e.name, n++)
                    }
                }
                ziel.delete()
                runOnUiThread {
                    toast(getString(R.string.nc_bearbeitet_gespeichert, name))
                    laden()
                }
            } catch (ex: Exception) {
                runOnUiThread { fehler(ex) }
            }
        }
    }

    private fun bearbeitetName(name: String, nr: Int = 1): String {
        val basis = name.substringBeforeLast('.')
        val end = name.substringAfterLast('.', "jpg")
        return if (nr == 1) "$basis (bearbeitet).$end" else "$basis (bearbeitet $nr).$end"
    }

    private fun fehler(e: Exception) {
        if (e is NextcloudFehler && e.anmeldungNoetig) {
            dav = null
            eintraege = emptyList()
        }
        toast(getString(R.string.nc_fehler, e.message ?: e.javaClass.simpleName))
    }

    private fun endung(e: Eintrag) = e.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    private fun istVideo(e: Eintrag) = endung(e) in VIDEO
    private fun istMedium(e: Eintrag) = !e.istOrdner && (endung(e) in FOTO || endung(e) in VIDEO)

    private inner class Kacheln : RecyclerView.Adapter<Kacheln.Kachel>() {
        inner class Kachel(val b: ItemNcBildBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Kachel(ItemNcBildBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = if (dav == null) 0 else eintraege.size

        override fun onBindViewHolder(h: Kachel, position: Int) {
            val e = eintraege[position]
            h.b.apply {
                Glide.with(this@NextcloudGalerieActivity).clear(ncBild)
                ncBild.setImageDrawable(null)
                ncName.text = if (e.istOrdner) e.name else ""
                ncName.beVisibleIf(e.istOrdner)
                when {
                    e.istOrdner -> {
                        ncTyp.setImageResource(org.fossify.commons.R.drawable.ic_folder_vector)
                        ncTyp.setColorFilter(getProperPrimaryColor())
                    }
                    istVideo(e) -> {
                        ncTyp.setImageResource(org.fossify.commons.R.drawable.ic_play_vector)
                        ncTyp.clearColorFilter()
                    }
                    else -> ncTyp.setImageDrawable(null)
                }
                val d = dav
                if (!e.istOrdner && e.fileId > 0 && d != null) {
                    run {
                        val hdr = LazyHeaders.Builder()
                        d.abspielKopfzeilen().forEach { (k, v) -> hdr.addHeader(k, v) }
                        Glide.with(this@NextcloudGalerieActivity)
                            .load(GlideUrl(d.vorschauAdresse(e, 384), hdr.build()))
                            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                            .centerCrop()
                            .into(ncBild)
                    }
                }
                root.setOnClickListener { oeffnen(e) }
                root.setOnLongClickListener { aktionen(e); true }
                ncMehr.beVisibleIf(!e.istOrdner)
                ncMehr.setOnClickListener { aktionen(e) }
            }
        }
    }

    private companion object {
        const val PFAD = "pfad"
        val START = "${Hochladen.PERSOENLICH}/Kamera"
        val FOTO = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "avif")
        val VIDEO = setOf("mp4", "3gp", "mkv", "webm", "mov")
        val BEARBEITBAR = setOf("jpg", "jpeg", "png", "webp")
        val HOCHLADER = listOf("ch.rosenweg4303.camera")
        const val BEARBEITEN = 4711
    }
}
