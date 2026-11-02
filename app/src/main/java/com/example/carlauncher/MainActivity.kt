package com.example.carlauncher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject
import java.net.URL

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private lateinit var gauge: Gauge
    private lateinit var picker: View
    private lateinit var seek: SeekBar
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }

    // پکیج مسیریاب‌ها به ترتیب اولویت
    private val navApps = arrayOf("org.neshan.navigator", "ir.balad", "com.google.android.apps.maps")
    private val accents = intArrayOf(0xFFFF8C00.toInt(), 0xFFE53935.toInt(), 0xFF3D7BFF.toInt(), 0xFF00C853.toInt())
    private val LOGO_TEXT = "VW"      // متن وسط دایره در حالت لوگو

    private var modePref = Gauge.CLOCK   // -1 = AUTO
    private var speedKmh = 0f
    private var lastFix = 0L
    private var lastLoc: Location? = null
    private var seeking = false
    private var tick = 0

    private val mediaCb = object : MediaController.Callback() {
        override fun onMetadataChanged(m: MediaMetadata?) = updateMedia()
        override fun onPlaybackStateChanged(s: PlaybackState?) = updateMedia()
    }
    private val loc = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            lastLoc = l; lastFix = SystemClock.elapsedRealtime()
            speedKmh = if (l.hasSpeed()) l.speed * 3.6f else 0f
            if (l.hasBearing() && speedKmh > 2f) gauge.heading = l.bearing
        }
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }
    private val ticker = object : Runnable {
        override fun run() {
            tick++
            if (tick % 3 == 0) connectMedia()
            updateMedia(); updateGauge()
            findViewById<View>(R.id.s_gps).alpha = if (SystemClock.elapsedRealtime() - lastFix < 5000) 1f else 0.3f
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        gauge = findViewById(R.id.gauge); picker = findViewById(R.id.picker); seek = findViewById(R.id.seek)
        gauge.logo = LOGO_TEXT
        modePref = prefs.getInt("mode", -1)
        applyAccent(prefs.getInt("accent", 0))

        click(R.id.gauge) { picker.visibility = View.VISIBLE }
        val modes = mapOf(R.id.p_clock to Gauge.CLOCK, R.id.p_speed to Gauge.SPEED, R.id.p_music to Gauge.MUSIC,
            R.id.p_compass to Gauge.COMPASS, R.id.p_logo to Gauge.LOGO, R.id.p_auto to -1)
        for ((id, m) in modes) click(id) { modePref = m; prefs.edit().putInt("mode", m).apply(); picker.visibility = View.GONE; updateGauge() }
        click(R.id.p_nav) { picker.visibility = View.GONE; openNav() }
        click(R.id.p_close) { picker.visibility = View.GONE }

        click(R.id.b_radio) { openRadio() }
        click(R.id.b_navi) { openNav() }
        click(R.id.b_music) { start(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)) }
        click(R.id.b_phone) { start(Intent(Intent.ACTION_DIAL)) }
        click(R.id.b_net) { start(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)) }
        click(R.id.b_apps) { showAllApps() }
        click(R.id.b_mic) { start(Intent(Intent.ACTION_VOICE_COMMAND)) }
        click(R.id.q_bright) { start(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
        click(R.id.q_theme) {
            val i = (prefs.getInt("accent", 0) + 1) % accents.size
            prefs.edit().putInt("accent", i).apply(); applyAccent(i)
        }
        click(R.id.s_wifi) { start(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        click(R.id.s_bt) { start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        click(R.id.s_mute) {
            val a = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            a.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
            (it as TextView).text = if (a.isStreamMute(AudioManager.STREAM_MUSIC)) "🔇" else "🔊"
        }
        click(R.id.btn_prev) { controller?.transportControls?.skipToPrevious() }
        click(R.id.btn_next) { controller?.transportControls?.skipToNext() }
        click(R.id.btn_play) {
            val c = controller
            if (c?.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c?.transportControls?.play()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(s: SeekBar?) {
                seeking = false
                val d = duration()
                if (d > 0) controller?.transportControls?.seekTo(d * seek.progress / 1000)
            }
        })
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) { startLocation(); loadWeather() }
    override fun onResume() { super.onResume(); hideBars(); startLocation(); handler.post(ticker); loadWeather() }
    override fun onPause() {
        handler.removeCallbacks(ticker)
        try { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(loc) } catch (_: Exception) {}
        super.onPause()
    }
    override fun onBackPressed() { if (picker.visibility == View.VISIBLE) picker.visibility = View.GONE }
    override fun onNewIntent(i: Intent?) { super.onNewIntent(i); hideBars(); picker.visibility = View.GONE }

    private fun hideBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun applyAccent(i: Int) {
        val c = accents[i]
        gauge.accent = c
        findViewById<TextView>(R.id.weather).setTextColor(c)
        findViewById<TextView>(R.id.np_time).setTextColor(c)
        seek.progressTintList = ColorStateList.valueOf(c); seek.thumbTintList = ColorStateList.valueOf(c)
        gauge.invalidate()
    }

    private fun startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, loc)
        } catch (_: Exception) {}
    }

    private fun updateGauge() {
        gauge.mode = if (modePref == -1) (if (speedKmh > 5f) Gauge.SPEED else Gauge.CLOCK) else modePref
        gauge.speed = speedKmh
        gauge.invalidate()
    }

    // ---- میانبرها ----
    private fun click(id: Int, f: (View) -> Unit) = findViewById<View>(id).setOnClickListener { f(it) }
    private fun start(i: Intent) { try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
    private fun openNav() {
        for (p in navApps) packageManager.getLaunchIntentForPackage(p)?.let { start(it); return }
        start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")))
    }
    private fun launcherApps() = packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
    private fun openRadio() {
        val m = launcherApps().firstOrNull {
            val l = it.loadLabel(packageManager).toString().lowercase()
            l.contains("radio") || l.contains("رادیو") || l == "fm"
        }
        m?.let { packageManager.getLaunchIntentForPackage(it.activityInfo.packageName)?.let { i -> start(i); return } }
        showAllApps()
    }
    private fun showAllApps() {
        val apps = launcherApps()
        val names = apps.map { it.loadLabel(packageManager).toString() }
        AlertDialog.Builder(this).setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, names)) { _, i ->
            packageManager.getLaunchIntentForPackage(apps[i].activityInfo.packageName)?.let { start(it) }
        }.show()
    }

    // ---- پخش‌کننده ----
    private fun connectMedia() {
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val c = msm.getActiveSessions(ComponentName(this, NotifService::class.java)).firstOrNull()
            if (c?.sessionToken != controller?.sessionToken) {
                controller?.unregisterCallback(mediaCb); controller = c; controller?.registerCallback(mediaCb)
            }
        } catch (e: SecurityException) {
            val t = findViewById<TextView>(R.id.np_title)
            t.text = "دسترسی نوتیفیکیشن بدهید (لمس کنید)"
            t.setOnClickListener { start(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        }
    }
    private fun duration() = controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
    private fun position(): Long {
        val s = controller?.playbackState ?: return 0L
        if (s.state != PlaybackState.STATE_PLAYING) return s.position
        return (s.position + (SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
    }
    private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    private fun updateMedia() {
        val m = controller?.metadata
        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val d = duration(); val pos = minOf(position(), if (d > 0) d else Long.MAX_VALUE)
        if (m != null) {
            findViewById<TextView>(R.id.np_title).text = title.ifEmpty { "—" }
            findViewById<TextView>(R.id.np_artist).text = m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        } else {
            findViewById<TextView>(R.id.np_title).text = "چیزی پخش نمی‌شود"
            findViewById<TextView>(R.id.np_artist).text = ""
        }
        val t = if (m != null) "${fmt(pos)} / ${if (d > 0) fmt(d) else "--:--"}" else "0:00 / 0:00"
        findViewById<TextView>(R.id.np_time).text = t
        if (!seeking) seek.progress = if (d > 0) (pos * 1000 / d).toInt() else 0
        gauge.title = title; gauge.timeText = t; gauge.progress = if (d > 0) pos.toFloat() / d else 0f
        findViewById<TextView>(R.id.btn_play).text =
            if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) "⏸" else "▶"
    }

    // ---- آب‌وهوا (Open-Meteo) ----
    private fun loadWeather() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = lastLoc ?: lm.getProviders(true).mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }
            .maxByOrNull { it.time } ?: return
        Thread {
            try {
                val u = "https://api.open-meteo.com/v1/forecast?latitude=${l.latitude}&longitude=${l.longitude}&current=temperature_2m"
                val t = JSONObject(URL(u).readText()).getJSONObject("current").getDouble("temperature_2m")
                runOnUiThread { findViewById<TextView>(R.id.weather).text = "☁ ${t.toInt()}°" }
            } catch (_: Exception) {}
        }.start()
    }
}
