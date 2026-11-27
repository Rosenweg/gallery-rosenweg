package org.fossify.gallery.activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import java.io.File
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import ch.rosenweg4303.cloud.Ablage
import ch.rosenweg4303.cloud.AnmeldenActivity
import ch.rosenweg4303.cloud.Eintrag
import ch.rosenweg4303.cloud.Hochladen
import ch.rosenweg4303.cloud.NextcloudFehler
import ch.rosenweg4303.cloud.NextcloudZugang
import ch.rosenweg4303.cloud.RosenwegAnmeldeSchirm
import ch.rosenweg4303.cloud.RosenwegAnmeldung
import ch.rosenweg4303.cloud.WebDav
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ActivityNextcloudGalerieBinding
import org.fossify.gallery.databinding.ItemNcBildBinding
import org.fossify.gallery.extensions.launchAbout
import org.fossify.gallery.extensions.launchSettings
import org.fossify.gallery.helpers.alsOrtChip
import org.fossify.gallery.rosenweg.NcAuswahl
import java.util.Locale

/**
 * Rosenweg-Galerie: Fotos und Videos der Person in der Rosenweg-Nextcloud
 * (Regel 6: die App zeigt ihre Daten aus der Nextcloud).
 *
 * Start: Persönlich/Kamera mit allen Monaten, neueste zuerst ("Kamera"). "Ordner" zeigt
 * Persönlich und von dort jeden Ordner. Vorschaubilder liefert die Nextcloud; geoeffnet
 * wird im Bildbetrachter der Galerie (Kopie im Cache).
 *
 * Stefan 27.11.2026: "Auf privaten Telefonen nur die Fotos von Nextcloud anzeigen" - diese
 * Ansicht ist auf jedem Geraet die Galerie, die lokale Ordneransicht gibt es nicht mehr.
 * Ohne Anmeldung kommt der Rosenweg-Anmeldeschirm. Mit [NcAuswahl] (Foto auswaehlen fuer
 * andere Apps) gibt ein Tippen die Datei als content-URI zurueck.
 */
class NextcloudGalerieActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityNextcloudGalerieBinding::inflate)
    private var dav: WebDav? = null
    /** true: alle Monate von Persönlich/Kamera flach; false: Ordner ab Persönlich ([pfad]). */
    private var kamera = true
    private var pfad = Hochladen.PERSOENLICH
    private var nachAnsichtLaden = false
    private var eintraege = listOf<Eintrag>()
    /** Liste schon einmal geladen (vorher kein "leer"-Hinweis). */
    private var geladen = false
    private var anmeldenOffen = false
    private val auswahl: NcAuswahl? by lazy { NcAuswahl.aus(intent) }
    /** Bei Mehrfachauswahl: gewaehlte Pfade in der Reihenfolge des Tippens. */
    private val gewaehlt = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        kamera = savedInstanceState?.getBoolean(KAMERA, true) ?: true
        pfad = savedInstanceState?.getString(PFAD) ?: Hochladen.PERSOENLICH
        savedInstanceState?.getStringArrayList(GEWAEHLT)?.let { gewaehlt.addAll(it) }
        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(ncList))
            setupMaterialScrollListener(ncList, ncAppbar)
            ncList.adapter = Kacheln()
            ncRefresh.setOnRefreshListener { laden() }
            ncLogin.paintFlags = ncLogin.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            ncLogin.setOnClickListener { startActivity(Intent(this@NextcloudGalerieActivity, AnmeldenActivity::class.java)) }
            ncOrtKamera.setOnClickListener { ortWechseln(kameraAnsicht = true) }
            ncOrtOrdner.setOnClickListener { ortWechseln(kameraAnsicht = false) }
            ncWartet.setOnClickListener { wartendeAnstossen() }
        }
        menueAufbauen()
        // wie bisher nur auf dem eigenen Handy (geteilte Telefone: Rosenweg Geraet regelt das)
        if (savedInstanceState == null && auswahl == null && !RosenwegAnmeldung.geraetDa(this)) {
            ch.rosenweg4303.cloud.Hinweis.beimErstenStart(this, "Deine Fotos und Videos aus der Nextcloud")
            ch.rosenweg4303.cloud.Einstellungen.abgleichen(this) { recreate() }
        }
    }

    /** Normal: Einstellungen, Über. Mehrfachauswahl: "Fertig" (erst sichtbar, wenn etwas gewaehlt ist). */
    private fun menueAufbauen() {
        binding.ncToolbar.menu.apply {
            clear()
            if (auswahl == null) {
                add(0, MENUE_EINSTELLUNGEN, 0, org.fossify.commons.R.string.settings)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                add(0, MENUE_UEBER, 1, org.fossify.commons.R.string.about)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            } else if (auswahl?.mehrere == true) {
                add(0, MENUE_FERTIG, 0, R.string.nc_auswahl_fertig)
                    .setIcon(org.fossify.commons.R.drawable.ic_check_vector)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
            }
        }
        binding.ncToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENUE_EINSTELLUNGEN -> launchSettings()
                MENUE_UEBER -> launchAbout()
                MENUE_FERTIG -> if (gewaehlt.isNotEmpty()) zurueckgeben(gewaehlt.toList())
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
    }

    private fun menueZeigen() {
        val fertig = binding.ncToolbar.menu.findItem(MENUE_FERTIG) ?: return
        fertig.isVisible = gewaehlt.isNotEmpty()
        fertig.title = getString(R.string.nc_auswahl_fertig_anzahl, gewaehlt.size)
    }

    private fun ortWechseln(kameraAnsicht: Boolean) {
        if (kamera == kameraAnsicht && (kamera || pfad == Hochladen.PERSOENLICH)) return
        kamera = kameraAnsicht
        pfad = Hochladen.PERSOENLICH
        eintraege = emptyList()
        geladen = false
        anzeigen()
        laden()
    }

    /**
     * Ohne Rosenweg Geraet und ohne Anmeldung: Anmeldeschirm (Karte, Konto, QR), danach holt er den
     * Nextcloud-Zugang. "Zurueck" dort schliesst die App bzw. bricht die Auswahl fuer die andere App ab.
     */
    private fun anmeldungZeigen(): Boolean {
        if (!RosenwegAnmeldung.anmeldungNoetig(this)) {
            NextcloudZugang.nachholenFallsNoetig(this)
            return false
        }
        if (!anmeldenOffen) {
            anmeldenOffen = true
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(this, RosenwegAnmeldeSchirm::class.java)
                    .putExtra(RosenwegAnmeldeSchirm.EXTRA_NEXTCLOUD, true)
                    .putExtra(RosenwegAnmeldeSchirm.EXTRA_ZURUECK_SCHLIESST_APP, auswahl == null),
                ANMELDEN,
            )
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        if (anmeldungZeigen()) return
        pfeil()
        binding.ncPlaceholder.setTextColor(getProperTextColor())
        binding.ncLogin.setTextColor(getProperPrimaryColor())
        if (auswahl == null) {
            wartendeZeigen()
            handler.removeCallbacks(wartendePruefen)
            handler.postDelayed(wartendePruefen, 10_000)
        }
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
        outState.putBoolean(KAMERA, kamera)
        outState.putString(PFAD, pfad)
        outState.putStringArrayList(GEWAEHLT, ArrayList(gewaehlt))
    }

    // Zurueck (Taste und Wischgeste) laeuft ueber den OnBackPressedDispatcher von BaseSimpleActivity;
    // onBackPressed() wird bei der Zurueck-Geste nicht mehr aufgerufen.
    override fun onBackPressedCompat(): Boolean = hoch()

    /** Startseite (Kamera): die Galerie selbst - Zurueck schliesst (Stefan 25.11.2026). */
    private fun oben() = kamera

    /**
     * Kein Pfeil auf der Startseite (Stefan 25.11.2026: "direkt Nextcloud mit einem Pfeil zurueck -
     * da ist was schief"); unter "Ordner" geht es eine Ebene hoch, von Persönlich zurueck zur Kamera.
     * Bei der Auswahl fuer eine andere
     * App oben ein Kreuz zum Abbrechen.
     */
    private fun pfeil() {
        val symbol = when {
            !oben() -> NavigationIcon.Arrow
            auswahl != null -> NavigationIcon.Cross
            else -> NavigationIcon.None
        }
        setupTopAppBar(binding.ncAppbar, symbol)
        if (symbol == NavigationIcon.None) binding.ncToolbar.navigationIcon = null
        binding.ncToolbar.setSubtitleTextColor(getRequiredTopBarColor().getContrastColor())
        binding.ncToolbar.setNavigationOnClickListener { if (!hoch()) finish() }
        menueZeigen()
    }

    private fun hoch(): Boolean {
        if (dav == null || oben()) return false
        if (pfad == Hochladen.PERSOENLICH) {
            ortWechseln(kameraAnsicht = true)
            return true
        }
        pfad = pfad.substringBeforeLast('/', Hochladen.PERSOENLICH)
        eintraege = emptyList()
        geladen = false
        laden()
        return true
    }

    /** Normal "Nextcloud · Kamera" bzw. der Ordner; bei der Auswahl fuer eine andere App, was gewaehlt wird. */
    private fun titel() {
        val a = auswahl
        if (a == null) {
            binding.ncToolbar.title = if (oben()) getString(R.string.nc_titel) + " · " + ortName() else ortName()
            binding.ncToolbar.subtitle = null
            return
        }
        binding.ncToolbar.title = getString(
            when {
                a.fotos && !a.videos -> if (a.mehrere) R.string.nc_auswahl_fotos else R.string.nc_auswahl_foto
                a.videos && !a.fotos -> R.string.nc_auswahl_video
                else -> R.string.nc_auswahl_beides
            }
        )
        binding.ncToolbar.subtitle = getString(R.string.nc_titel) + " · " + ortName()
    }

    private fun ortName() = if (kamera) getString(R.string.nc_ort_kamera) else pfad.substringAfterLast('/')

    private fun anzeigen() {
        val angemeldet = dav != null
        binding.apply {
            pfeil()
            titel()
            ncOrte.beVisibleIf(angemeldet)
            ncOrtKamera.alsOrtChip(aktiv = kamera, symbol = org.fossify.commons.R.drawable.ic_camera_vector)
            ncOrtOrdner.alsOrtChip(aktiv = !kamera, symbol = org.fossify.commons.R.drawable.ic_folder_vector)
            ncRefresh.isEnabled = angemeldet
            ncLogin.beVisibleIf(!angemeldet)
            ncPlaceholder.beVisibleIf(!angemeldet || (geladen && eintraege.isEmpty()))
            ncPlaceholder.text = getString(
                when {
                    !angemeldet -> R.string.nc_nicht_angemeldet
                    kamera && auswahl == null -> R.string.nc_leer_kamera
                    kamera -> R.string.nc_leer_kamera_auswahl
                    else -> R.string.nc_leer
                }
            )
            ncList.adapter?.notifyDataSetChanged()
        }
    }

    private fun laden() {
        val d = dav ?: return
        val flach = kamera
        val ziel = if (flach) START else pfad
        binding.ncRefresh.isRefreshing = true
        ensureBackgroundThread {
            try {
                val liste = if (flach) {
                    // Kamera: alle Monate zusammen, neueste zuerst; noch kein Ordner = noch keine Fotos
                    val alle = try {
                        d.alleDateien(ziel)
                    } catch (e: NextcloudFehler) {
                        if (e.code != NICHT_GEFUNDEN) throw e
                        emptyList()
                    }
                    alle.filter { istMedium(it) }.sortedByDescending { it.geaendert }
                } else {
                    d.liste(ziel).filter { (it.istOrdner && !it.name.startsWith(".")) || istMedium(it) }
                        .sortedWith(compareBy<Eintrag> { !it.istOrdner }.thenByDescending { if (it.istOrdner) 0 else it.geaendert }
                            .thenBy { it.name.lowercase(Locale.ROOT) })
                }
                runOnUiThread {
                    if (flach == kamera && (flach || ziel == pfad)) {
                        eintraege = liste
                        geladen = true
                    }
                    binding.ncRefresh.isRefreshing = false
                    anzeigen()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.ncRefresh.isRefreshing = false
                    geladen = true
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
            geladen = false
            anzeigen()
            laden()
            return
        }
        if (auswahl != null) {
            waehlen(e)
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

    // ─── Auswahl fuer andere Apps ───────────────────────────────────────────
    private fun waehlen(e: Eintrag) {
        if (auswahl?.mehrere != true) {
            zurueckgeben(listOf(e.pfad))
            return
        }
        if (!gewaehlt.remove(e.pfad)) gewaehlt.add(e.pfad)
        val i = eintraege.indexOf(e)
        if (i >= 0) binding.ncList.adapter?.notifyItemChanged(i)
        menueZeigen()
    }

    /** Lokale Kopien holen und als content-URI (Leserecht) an die aufrufende App geben. */
    private fun zurueckgeben(pfade: List<String>) {
        val a = auswahl ?: return
        toast(if (pfade.size == 1) getString(R.string.nc_wird_geladen, pfade[0].substringAfterLast('/'))
            else getString(R.string.nc_auswahl_laedt, pfade.size))
        ensureBackgroundThread {
            try {
                val ergebnis = a.ergebnis(this, pfade.map { Ablage.lokaleKopie(this, it) })
                runOnUiThread {
                    setResult(RESULT_OK, ergebnis)
                    finish()
                }
            } catch (ex: Exception) {
                runOnUiThread { fehler(ex) }
            }
        }
    }

    private fun aktionen(e: Eintrag) {
        if (auswahl != null) {
            waehlen(e)
            return
        }
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
        when (requestCode) {
            ANMELDEN -> anmeldungZurueck()
            BEARBEITEN -> bearbeitetZurueck()
        }
    }

    private fun anmeldungZurueck() {
        anmeldenOffen = false
        // Zurueck auf dem Anmeldeschirm ohne Anmeldung: Auswahl abbrechen bzw. App schliessen
        if (RosenwegAnmeldung.anmeldungNoetig(this)) {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun bearbeitetZurueck() {
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
    private fun istMedium(e: Eintrag) =
        !e.istOrdner && (auswahl?.passt(e.name) ?: (endung(e) in FOTO || endung(e) in VIDEO))

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
                    e.pfad in gewaehlt -> {
                        ncTyp.setImageResource(org.fossify.commons.R.drawable.ic_check_vector)
                        ncTyp.setColorFilter(getProperPrimaryColor())
                    }
                    istVideo(e) -> {
                        ncTyp.setImageResource(org.fossify.commons.R.drawable.ic_play_vector)
                        ncTyp.clearColorFilter()
                    }
                    else -> ncTyp.setImageDrawable(null)
                }
                ncBild.alpha = if (e.pfad in gewaehlt) GEWAEHLT_ALPHA else 1f
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
                ncMehr.beVisibleIf(!e.istOrdner && auswahl == null)
                ncMehr.setOnClickListener { aktionen(e) }
            }
        }
    }

    private companion object {
        const val PFAD = "pfad"
        const val KAMERA = "kamera"
        const val GEWAEHLT = "gewaehlt"
        val START = "${Hochladen.PERSOENLICH}/Kamera"
        val FOTO = NcAuswahl.FOTO
        val VIDEO = NcAuswahl.VIDEO
        val BEARBEITBAR = setOf("jpg", "jpeg", "png", "webp")
        val HOCHLADER = listOf("ch.rosenweg4303.camera")
        const val BEARBEITEN = 4711
        const val ANMELDEN = 4712
        const val NICHT_GEFUNDEN = 404
        const val GEWAEHLT_ALPHA = 0.5f
        const val MENUE_EINSTELLUNGEN = 1
        const val MENUE_UEBER = 2
        const val MENUE_FERTIG = 3
    }
}
