package com.islamic.companion

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.webkit.JavascriptInterface

const val CTRL_ACTION = "com.islamic.companion.CTRL"

fun ensureChannel(ctx: Context, id: String, name: String, importance: Int = NotificationManager.IMPORTANCE_LOW) {
    if (Build.VERSION.SDK_INT >= 26) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(id, name, importance))
    }
}

fun startFgService(ctx: Context, i: Intent) {
    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
}

/** التنقل: يفتح تطبيق خرائط قوقل لبدء الملاحة، أو خرائط الويب الكاملة داخل التطبيق. */
class NavBridge(private val act: Activity) {
    @JavascriptInterface
    fun navigate(query: String) {
        act.runOnUiThread {
            val q = Uri.encode(query)
            val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q"))
                .setPackage("com.google.android.apps.maps")
            try {
                act.startActivity(nav)
            } catch (e: Exception) {
                try {
                    act.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$q&travelmode=driving")))
                } catch (e2: Exception) { }
            }
        }
    }

    @JavascriptInterface
    fun openMaps(query: String) {
        act.runOnUiThread {
            val url = if (query.isBlank()) "https://www.google.com/maps"
                      else "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(query)
            act.startActivity(Intent(act, YouTubeActivity::class.java).putExtra("url", url))
        }
    }
}

/** الهاتف: يفتح تطبيق الاتصال في النظام (المربوط بالبلوتوث إن كان الجهاز يدعم ذلك). */
class PhoneBridge(private val act: Activity, private val web: android.webkit.WebView) {
    private fun go(i: Intent) {
        act.runOnUiThread { try { act.startActivity(i) } catch (e: ActivityNotFoundException) { } }
    }
    private fun js(code: String) { act.runOnUiThread { web.evaluateJavascript(code, null) } }
    private fun granted(p: String) = Build.VERSION.SDK_INT < 23 || act.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun btPerm() = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)

    @JavascriptInterface fun dial(number: String) { go(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))) }
    @JavascriptInterface fun bluetooth() { go(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    @JavascriptInterface fun callLog() { go(Intent(Intent.ACTION_VIEW).setType(CallLog.Calls.CONTENT_TYPE)) }
    @JavascriptInterface fun contacts() { go(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)) }

    /** هل أُعطيت أذونات البلوتوث وجهات الاتصال؟ */
    @JavascriptInterface fun hasPerms(): Boolean = btPerm() && granted(Manifest.permission.READ_CONTACTS)

    @JavascriptInterface
    fun requestPerms() {
        act.runOnUiThread {
            val l = ArrayList<String>()
            if (!btPerm()) l.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (!granted(Manifest.permission.READ_CONTACTS)) l.add(Manifest.permission.READ_CONTACTS)
            if (l.isNotEmpty() && Build.VERSION.SDK_INT >= 23) act.requestPermissions(l.toTypedArray(), 4)
        }
    }

    /** يسأل النظام عن الأجهزة المتصلة (هاتف/سماعة) ويُرجع النتيجة إلى onBtState. */
    @JavascriptInterface
    fun refreshBt() {
        val ad = (act.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        val perm = btPerm()
        val names = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
        val sent = java.util.concurrent.atomic.AtomicBoolean(false)
        fun push() {
            if (!sent.compareAndSet(false, true)) return
            val o = org.json.JSONObject().put("bt", ad != null && ad.isEnabled).put("perm", perm).put("dev", org.json.JSONArray(names.toList()))
            js("window.onBtState&&onBtState(" + o.toString() + ")")
        }
        if (ad == null || !perm || !ad.isEnabled) { push(); return }
        val profiles = intArrayOf(android.bluetooth.BluetoothProfile.HEADSET, android.bluetooth.BluetoothProfile.A2DP, 16 /* HEADSET_CLIENT في وحدات السيارة */)
        val pending = java.util.concurrent.atomic.AtomicInteger(profiles.size)
        fun done() { if (pending.decrementAndGet() <= 0) push() }
        for (pr in profiles) {
            try {
                val ok = ad.getProfileProxy(act, object : android.bluetooth.BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                        try { proxy.connectedDevices.forEach { d -> names.add(d.name ?: d.address) } } catch (e: Exception) { }
                        try { ad.closeProfileProxy(profile, proxy) } catch (e: Exception) { }
                        done()
                    }
                    override fun onServiceDisconnected(profile: Int) { }
                }, pr)
                if (!ok) done()
            } catch (e: Exception) { done() }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ push() }, 2500)
    }

    /** جهات الاتصال المتوفرة في النظام (ما يُزامنه البلوتوث من الهاتف يظهر هنا إن خزّنته الوحدة). */
    @JavascriptInterface
    fun loadContacts() {
        Thread {
            val arr = org.json.JSONArray(); val seen = HashSet<String>()
            if (granted(Manifest.permission.READ_CONTACTS)) {
                try {
                    act.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                        null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC"
                    )?.use { c ->
                        while (c.moveToNext() && arr.length() < 3000) {
                            val n = c.getString(0) ?: ""; val t = c.getString(1) ?: ""
                            if (t.isNotBlank() && seen.add(n + "|" + t)) arr.put(org.json.JSONObject().put("n", n).put("t", t))
                        }
                    }
                } catch (e: Exception) { }
            }
            js("window.onContacts&&onContacts(" + arr.toString() + ")")
        }.start()
    }
}

/** الصلاة: جدولة الأذان في النظام ليعمل والتطبيق مغلق أو في الخلفية. */
class PrayerBridge(private val act: Activity) {
    @JavascriptInterface
    fun schedule(json: String) { PrayerScheduler.save(act, json); PrayerScheduler.apply(act) }

    @JavascriptInterface
    fun requestPermissions() {
        act.runOnUiThread {
            if (Build.VERSION.SDK_INT >= 33) act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    /** هل التطبيق مستثنى من توفير البطارية؟ */
    @JavascriptInterface
    fun batteryOk(): Boolean {
        val pm = act.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(act.packageName)
    }

    @JavascriptInterface
    fun requestBattery() {
        act.runOnUiThread {
            try {
                act.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + act.packageName)))
            } catch (e: Exception) {
                try { act.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { }
            }
        }
    }

    @JavascriptInterface
    fun test() {
        act.runOnUiThread {
            startFgService(act, Intent(act, AdhanService::class.java)
                .putExtra("name", "اختبار").putExtra("fajr", false))
        }
    }
}

/** الشريط العائم فوق التطبيقات. */
class FloatBridge(private val act: Activity) {
    @JavascriptInterface fun canDraw(): Boolean = Settings.canDrawOverlays(act)

    @JavascriptInterface
    fun requestPermission() {
        act.runOnUiThread {
            act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + act.packageName)))
        }
    }

    @JavascriptInterface
    fun show() { act.runOnUiThread { startFgService(act, Intent(act, FloatingService::class.java)) } }

    @JavascriptInterface
    fun hide() { act.runOnUiThread { act.stopService(Intent(act, FloatingService::class.java)) } }

    @JavascriptInterface
    fun update(title: String, sub: String, playing: Boolean) { FloatingService.instance?.setInfo(title, sub, playing) }
}
