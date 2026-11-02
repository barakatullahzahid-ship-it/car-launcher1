package com.example.carlauncher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.provider.AlarmClock
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import org.json.JSONObject
import java.net.URL

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var btnPlay: TextView

    // پکیج مسیریاب‌ها به ترتیب اولویت (در صورت نیاز تغییر بدید)
    private val navApps = arrayOf("org.neshan.navigator", "ir.balad", "com.google.android.apps.maps")

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(m: MediaMetadata?) = refreshNowPlaying()
        override fun onPlaybackStateChanged(s: PlaybackState?) = refreshNowPlaying()
    }
    private val ticker = object : Runnable {
        override fun run() { connectMedia(); handler.postDelayed(this, 3000) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        npTitle = findViewById(R.id.np_title)
        npArtist = findViewById(R.id.np_artist)
        btnPlay = findViewById(R.id.btn_play)

        click(R.id.b_map) { openFirst(navApps, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))) }
        click(R.id.b_music) { start(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)) }
        click(R.id.b_call) { start(Intent(Intent.ACTION_DIAL)) }
        click(R.id.b_cal) { start(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)) }
        click(R.id.clock) { start(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
        click(R.id.q_bright) { start(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
        click(R.id.q_bt) { start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        click(R.id.q_wifi) { start(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        click(R.id.q_mute) {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
        }
        click(R.id.b_apps) { showAllApps() }
        click(R.id.btn_prev) { controller?.transportControls?.skipToPrevious() }
        click(R.id.btn_next) { controller?.transportControls?.skipToNext() }
        click(R.id.btn_play) {
            val c = controller
            if (c?.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
            else c?.transportControls?.play()
        }
        requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1)
    }

    override fun onResume() { super.onResume(); hideBars(); handler.post(ticker); loadWeather() }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }
    override fun onBackPressed() { /* launcher: back disabled */ }
    override fun onNewIntent(i: Intent?) { super.onNewIntent(i); hideBars() }

    private fun hideBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun click(id: Int, f: () -> Unit) = findViewById<View>(id).setOnClickListener { f() }
    private fun start(i: Intent) { try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
    private fun openFirst(pkgs: Array<String>, fallback: Intent) {
        for (p in pkgs) packageManager.getLaunchIntentForPackage(p)?.let { start(it); return }
        start(fallback)
    }

    private fun showAllApps() {
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(q, 0)
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        val names = apps.map { it.loadLabel(packageManager).toString() }
        AlertDialog.Builder(this).setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, names)) { _, i ->
            packageManager.getLaunchIntentForPackage(apps[i].activityInfo.packageName)?.let { start(it) }
        }.show()
    }

    private fun connectMedia() {
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val c = msm.getActiveSessions(ComponentName(this, NotifService::class.java)).firstOrNull()
            if (c?.sessionToken != controller?.sessionToken) {
                controller?.unregisterCallback(cb)
                controller = c
                controller?.registerCallback(cb)
            }
            refreshNowPlaying()
        } catch (e: SecurityException) {
            npTitle.text = "برای نمایش آهنگ، دسترسی نوتیفیکیشن بدهید (لمس کنید)"
            npTitle.setOnClickListener { start(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        }
    }

    private fun refreshNowPlaying() {
        val m = controller?.metadata
        if (m != null) {
            npTitle.text = m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "—"
            npArtist.text = m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        } else { npTitle.text = "چیزی پخش نمی‌شود"; npArtist.text = "" }
        btnPlay.text = if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) "⏸" else "▶"
    }

    private fun loadWeather() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = lm.getProviders(true).mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }
            .maxByOrNull { it.time } ?: return
        Thread {
            try {
                val u = "https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}&longitude=${loc.longitude}&current=temperature_2m"
                val t = JSONObject(URL(u).readText()).getJSONObject("current").getDouble("temperature_2m")
                runOnUiThread { findViewById<TextView>(R.id.weather).text = "${t.toInt()}°C" }
            } catch (_: Exception) {}
        }.start()
    }
}
