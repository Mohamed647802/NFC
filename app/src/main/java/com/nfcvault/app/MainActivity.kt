package com.nfcvault.app

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var nfc: NfcAdapter? = null
    private lateinit var dir: File
    private lateinit var status: TextView
    private lateinit var home: View
    private lateinit var panel: View
    private lateinit var list: ListView

    private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        nfc = NfcAdapter.getDefaultAdapter(this)
        dir = File(filesDir, "cards").apply { mkdirs() }
        status = findViewById(R.id.status)
        home = findViewById(R.id.home)
        panel = findViewById(R.id.panel)
        list = findViewById(R.id.list)

        findViewById<View>(R.id.tileScan).setOnClickListener {
            when {
                nfc == null -> toast("الجهاز لا يدعم NFC")
                nfc?.isEnabled == false -> {
                    toast("فعّل NFC أولاً")
                    startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                }
                else -> status.text = "📡 قرّب الكارت من ظهر الجهاز..."
            }
        }
        findViewById<View>(R.id.tileSaved).setOnClickListener { showList() }
        findViewById<View>(R.id.tileExport).setOnClickListener { export() }
        findViewById<View>(R.id.tileClear).setOnClickListener {
            confirm("حذف كل الكروت؟") {
                dir.listFiles()?.forEach { it.delete() }
                refreshStatus()
            }
        }
        findViewById<View>(R.id.back).setOnClickListener { showHome() }

        list.setOnItemClickListener { _, _, i, _ -> details(cards()[i]) }
        list.setOnItemLongClickListener { _, _, i, _ ->
            val f = cards()[i]
            AlertDialog.Builder(this)
                .setItems(arrayOf("إعادة تسمية", "حذف")) { _, w ->
                    if (w == 0) askName(load(f).optString("name")) { n ->
                        f.writeText(load(f).put("name", n).toString(2)); fillList()
                    } else confirm("حذف الكارت؟") { f.delete(); fillList(); refreshStatus() }
                }.show()
            true
        }
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
        nfc?.enableForegroundDispatch(this, pi, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag: Tag? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag != null) readTag(tag)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (panel.visibility == View.VISIBLE) showHome() else super.onBackPressed()
    }

    private fun readTag(tag: Tag) {
        val o = JSONObject()
        o.put("uid", tag.id.joinToString(":") { "%02X".format(it) })
        o.put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        o.put("techs", JSONArray(tag.techList.map { it.substringAfterLast('.') }))

        NfcA.get(tag)?.let { o.put("atqa", it.atqa.hex()); o.put("sak", it.sak) }

        Ndef.get(tag)?.let { n ->
            o.put("ndef_type", n.type)
            o.put("ndef_max_size", n.maxSize)
            val recs = JSONArray()
            n.cachedNdefMessage?.records?.forEach { r ->
                recs.put(r.toUri()?.toString() ?: String(r.payload, Charsets.UTF_8))
            }
            o.put("ndef_records", recs)
        }

        MifareClassic.get(tag)?.let { m ->
            try {
                m.connect()
                val arr = JSONArray()
                for (s in 0 until m.sectorCount) {
                    val ok = m.authenticateSectorWithKeyA(s, MifareClassic.KEY_DEFAULT) ||
                            m.authenticateSectorWithKeyA(s, MifareClassic.KEY_MIFARE_APPLICATION_DIRECTORY) ||
                            m.authenticateSectorWithKeyB(s, MifareClassic.KEY_DEFAULT)
                    if (ok) {
                        val first = m.sectorToBlock(s)
                        for (b in first until first + m.getBlockCountInSector(s))
                            arr.put("block $b: " + m.readBlock(b).hex())
                    } else arr.put("sector $s: locked")
                }
                o.put("mifare_classic", arr)
            } catch (e: Exception) {
                o.put("mifare_error", e.message ?: "error")
            } finally {
                try { m.close() } catch (_: Exception) {}
            }
        }

        (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            .vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))

        val def = "كارت " + SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())
        askName(def) { name ->
            o.put("name", name)
            File(dir, System.currentTimeMillis().toString() + ".json").writeText(o.toString(2))
            refreshStatus()
            if (panel.visibility == View.VISIBLE) fillList()
            toast("تم الحفظ: $name")
        }
    }

    private fun askName(def: String, ok: (String) -> Unit) {
        val et = EditText(this).apply { setText(def); selectAll() }
        AlertDialog.Builder(this)
            .setTitle("اسم الكارت / العملية")
            .setView(et)
            .setPositiveButton("حفظ") { _, _ -> ok(et.text.toString().ifBlank { def }) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun cards() = (dir.listFiles()?.toList() ?: emptyList()).sortedByDescending { it.name }
    private fun load(f: File) = JSONObject(f.readText())

    private fun fillList() {
        val names = cards().map { val j = load(it); j.optString("name") + "\n" + j.optString("uid") }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
    }

    private fun details(f: File) {
        AlertDialog.Builder(this).setTitle(load(f).optString("name"))
            .setMessage(f.readText()).setPositiveButton("تم", null).show()
    }

    private fun export() {
        val all = JSONArray().also { a -> cards().forEach { a.put(load(it)) } }
        if (all.length() == 0) return toast("لا توجد بيانات")
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, all.toString(2))
        }, "تصدير"))
    }

    private fun confirm(msg: String, yes: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("نعم") { _, _ -> yes() }
            .setNegativeButton("لا", null).show()
    }

    private fun showList() { fillList(); home.visibility = View.GONE; panel.visibility = View.VISIBLE }
    private fun showHome() { panel.visibility = View.GONE; home.visibility = View.VISIBLE }
    private fun refreshStatus() { status.text = "الكروت المحفوظة: ${cards().size}" }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
