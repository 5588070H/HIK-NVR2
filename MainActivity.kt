package com.example.cctv

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

data class Device(
    var name: String,
    var ip: String,
    var port: Int,
    var user: String,
    var pass: String,
    var channels: Int
)

object Store {
    fun load(c: Context): MutableList<Device> {
        val s = c.getSharedPreferences("cctv", Context.MODE_PRIVATE)
            .getString("devices", "[]") ?: "[]"
        val arr = JSONArray(s)
        val list = mutableListOf<Device>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                Device(
                    o.getString("name"), o.getString("ip"), o.getInt("port"),
                    o.getString("user"), o.getString("pass"), o.getInt("ch")
                )
            )
        }
        return list
    }

    fun save(c: Context, list: List<Device>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("name", it.name).put("ip", it.ip).put("port", it.port)
                    .put("user", it.user).put("pass", it.pass).put("ch", it.channels)
            )
        }
        c.getSharedPreferences("cctv", Context.MODE_PRIVATE)
            .edit().putString("devices", arr.toString()).apply()
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var libVlc: LibVLC
    private val players = mutableListOf<MediaPlayer>()
    private var inLive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        libVlc = LibVLC(this, arrayListOf("--rtsp-tcp", "--network-caching=300"))
        showDevices()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    // ---------------- Device list screen ----------------
    private fun showDevices() {
        releasePlayers()
        inLive = false
        val devices = Store.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply { text = "DVR / NVR Devices"; textSize = 22f })
        root.addView(btn("+ Add Device") { deviceDialog(null) })

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        if (devices.isEmpty()) {
            list.addView(TextView(this).apply { text = "No devices yet. Tap Add Device." })
        }

        devices.forEachIndexed { idx, d ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(
                TextView(this).apply {
                    text = "${d.name}\n${d.ip}:${d.port}  (${d.channels} ch)"
                    textSize = 16f
                },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            )
            row.addView(btn("Live") { showLive(d, 2, 0) })
            row.addView(btn("Edit") { deviceDialog(idx) })
            row.addView(btn("Delete") {
                AlertDialog.Builder(this)
                    .setTitle("Delete ${d.name}?")
                    .setPositiveButton("Yes") { _, _ ->
                        devices.removeAt(idx)
                        Store.save(this, devices)
                        showDevices()
                    }
                    .setNegativeButton("No", null)
                    .show()
            })
            list.addView(row)
        }
        setContentView(root)
    }

    private fun deviceDialog(index: Int?) {
        val devices = Store.load(this)
        val d = index?.let { devices[it] }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
        }

        fun field(hint: String, value: String): EditText {
            val e = EditText(this).apply {
                this.hint = hint
                setText(value)
                setSingleLine()
            }
            box.addView(e)
            return e
        }

        val name = field("Name (e.g. Shop DVR)", d?.name ?: "")
        val ip = field("IP / DDNS", d?.ip ?: "")
        val port = field("RTSP Port", (d?.port ?: 554).toString())
        val user = field("Username", d?.user ?: "admin")
        val pass = field("Password", d?.pass ?: "")
        val ch = field("Number of channels", (d?.channels ?: 4).toString())

        AlertDialog.Builder(this)
            .setTitle(if (d == null) "Add Device" else "Edit Device")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val nd = Device(
                    name.text.toString().ifBlank { "DVR" },
                    ip.text.toString().trim(),
                    port.text.toString().toIntOrNull() ?: 554,
                    user.text.toString(),
                    pass.text.toString(),
                    (ch.text.toString().toIntOrNull() ?: 4).coerceIn(1, 128)
                )
                if (index == null) devices.add(nd) else devices[index] = nd
                Store.save(this, devices)
                showDevices()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- Live view screen ----------------
    private fun rtspUrl(d: Device, ch: Int, main: Boolean): String {
        val stream = "$ch" + if (main) "01" else "02"
        return "rtsp://${Uri.encode(d.user)}:${Uri.encode(d.pass)}@${d.ip}:${d.port}/Streaming/Channels/$stream"
    }

    private fun showLive(d: Device, n: Int, page: Int) {
        releasePlayers()
        inLive = true
        val per = n * n
        val maxPage = (d.channels - 1) / per
        val p = page.coerceIn(0, maxPage)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(btn("Back") { showDevices() })
        listOf(1, 2, 3, 4).forEach { g -> bar.addView(btn("${g}x$g") { showLive(d, g, 0) }) }
        bar.addView(btn("< Prev") { showLive(d, n, p - 1) })
        bar.addView(btn("Next >") { showLive(d, n, p + 1) })
        bar.addView(TextView(this).apply {
            text = "  ${d.name}  Page ${p + 1}/${maxPage + 1}"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        })
        root.addView(HorizontalScrollView(this).apply { addView(bar) })

        val grid = GridLayout(this).apply { rowCount = n; columnCount = n }
        root.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        for (i in 0 until per) {
            val ch = p * per + i + 1
            val cell = FrameLayout(this).apply { setBackgroundColor(Color.DKGRAY) }
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(i / n, 1f), GridLayout.spec(i % n, 1f)
            ).apply { width = 0; height = 0; setMargins(1, 1, 1, 1) }

            if (ch <= d.channels) {
                val video = VLCVideoLayout(this)
                cell.addView(video, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                cell.addView(TextView(this).apply {
                    text = "CH$ch"
                    setTextColor(Color.YELLOW)
                    setPadding(dp(4), 0, 0, 0)
                })

                val mp = MediaPlayer(libVlc)
                mp.attachViews(video, null, false, false)
                val media = Media(libVlc, Uri.parse(rtspUrl(d, ch, main = (n == 1))))
                media.setHWDecoderEnabled(true, false)
                mp.media = media
                media.release()

                // Auto-reconnect on error / stream end
                mp.setEventListener { ev ->
                    if (ev.type == MediaPlayer.Event.EncounteredError ||
                        ev.type == MediaPlayer.Event.EndReached
                    ) {
                        cell.postDelayed({
                            if (inLive && players.contains(mp)) {
                                mp.stop()
                                mp.play()
                            }
                        }, 3000)
                    }
                }
                mp.play()
                players.add(mp)

                // Tap a tile: fullscreen (main stream). Tap again: back to 2x2 grid.
                val overlay = View(this)
                overlay.setOnClickListener {
                    if (n == 1) showLive(d, 2, 0) else showLive(d, 1, ch - 1)
                }
                cell.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            grid.addView(cell, lp)
        }
        setContentView(root)
    }

    private fun releasePlayers() {
        players.forEach {
            try {
                it.stop()
                it.detachViews()
                it.release()
            } catch (_: Exception) {
            }
        }
        players.clear()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (inLive) showDevices() else super.onBackPressed()
    }

    override fun onDestroy() {
        releasePlayers()
        libVlc.release()
        super.onDestroy()
    }
}
