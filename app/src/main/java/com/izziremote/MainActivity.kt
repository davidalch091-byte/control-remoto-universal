package com.izziremote

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

private val C_BG = 0xFF0E1116.toInt()
private val C_CARD = 0xFF171C24.toInt()
private val C_BTN = 0xFF252C36.toInt()
private val C_ACCENT = 0xFF4F8CFF.toInt()
private val C_RED = 0xFFE5484D.toInt()
private val C_GREEN = 0xFF3DD68C.toInt()
private val C_ORANGE = 0xFFF5A524.toInt()
private val C_TEXT = 0xFFFFFFFF.toInt()
private val C_MUTED = 0xFF8B96A5.toInt()

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var statusDot: View
    private lateinit var connCard: LinearLayout
    private lateinit var devicesBox: LinearLayout
    private lateinit var ipInput: EditText
    private lateinit var pairAgainBtn: TextView
    private lateinit var changeBtn: TextView
    private lateinit var remotePanel: LinearLayout

    private var client: RemoteClient? = null
    private var currentHost: String? = null
    private var generation = 0

    private val found = LinkedHashSet<String>()
    private lateinit var nsd: NsdManager
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolveQueue = mutableListOf<NsdServiceInfo>()
    private var resolving = false

    private val handler = Handler(Looper.getMainLooper())
    private var repeater: Runnable? = null

    private val prefs by lazy { getSharedPreferences("izzi", MODE_PRIVATE) }

    // ------------------------------------------------------------ helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun rounded(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    private fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply {
            setShape(GradientDrawable.OVAL)
            setColor(color)
        }

    private fun pressState(normal: Drawable, pressed: Drawable): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }

    private fun lighten(c: Int): Int {
        fun up(x: Int) = minOf(255, x + 45)
        return Color.rgb(up(Color.red(c)), up(Color.green(c)), up(Color.blue(c)))
    }

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun label(text: String, size: Float, color: Int = C_TEXT, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun row(heightDp: Int, vararg views: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEach {
                addView(it, LinearLayout.LayoutParams(0, dp(heightDp), 1f).apply {
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                })
            }
        }

    // ------------------------------------------- botones con pulsación

    @Suppress("ClickableViewAccessibility")
    private fun attachPress(v: View, code: Int, repeat: Boolean) {
        v.isClickable = true
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    sendKey(code, true)
                    if (repeat) startRepeat(code)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    stopRepeat()
                }
            }
            true
        }
    }

    private fun startRepeat(code: Int) {
        stopRepeat()
        val r = object : Runnable {
            override fun run() {
                sendKey(code, false)
                handler.postDelayed(this, 110)
            }
        }
        repeater = r
        handler.postDelayed(r, 420)
    }

    private fun stopRepeat() {
        repeater?.let { handler.removeCallbacks(it) }
        repeater = null
    }

    private fun keyBtn(
        text: String,
        code: Int,
        bg: Int = C_BTN,
        repeat: Boolean = false,
        size: Float = 15f
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(C_TEXT)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = pressState(rounded(bg, dp(16).toFloat()), rounded(lighten(bg), dp(16).toFloat()))
        attachPress(this, code, repeat)
    }

    private fun flatBtn(text: String, code: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 28f
        setTextColor(C_TEXT)
        gravity = Gravity.CENTER
        background = pressState(
            rounded(Color.TRANSPARENT, dp(26).toFloat()),
            rounded(C_ACCENT, dp(26).toFloat())
        )
        attachPress(this, code, true)
    }

    private fun pill(name: String, up: Int, down: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C_BTN, dp(30).toFloat())
            addView(flatBtn("+", up), LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(
                label(name, 12f, C_MUTED, true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(MATCH, WRAP)
            )
            addView(flatBtn("−", down), LinearLayout.LayoutParams(MATCH, 0, 1f))
        }

    private fun dpad(): FrameLayout {
        val f = FrameLayout(this)
        f.background = oval(C_CARD)

        fun arrow(text: String, code: Int, grav: Int) {
            val t = TextView(this).apply {
                this.text = text
                textSize = 24f
                setTextColor(C_TEXT)
                gravity = Gravity.CENTER
                background = pressState(
                    rounded(Color.TRANSPARENT, dp(42).toFloat()),
                    rounded(C_BTN, dp(42).toFloat())
                )
                attachPress(this, code, true)
            }
            f.addView(t, FrameLayout.LayoutParams(dp(84), dp(84), grav))
        }
        arrow("▲", KeyEvent.KEYCODE_DPAD_UP, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        arrow("▼", KeyEvent.KEYCODE_DPAD_DOWN, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        arrow("◀", KeyEvent.KEYCODE_DPAD_LEFT, Gravity.START or Gravity.CENTER_VERTICAL)
        arrow("▶", KeyEvent.KEYCODE_DPAD_RIGHT, Gravity.END or Gravity.CENTER_VERTICAL)

        val ok = TextView(this).apply {
            text = "OK"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C_TEXT)
            gravity = Gravity.CENTER
            background = pressState(oval(C_ACCENT), oval(lighten(C_ACCENT)))
            attachPress(this, KeyEvent.KEYCODE_DPAD_CENTER, false)
        }
        f.addView(ok, FrameLayout.LayoutParams(dp(98), dp(98), Gravity.CENTER))
        return f
    }

    // -------------------------------------------------------- pestañas

    private fun buildControlTab(): View {
        val col = vertical()
        col.addView(row(54,
            keyBtn("Apagar", KeyEvent.KEYCODE_POWER, C_RED),
            keyBtn("Fuente", KeyEvent.KEYCODE_TV_INPUT),
            keyBtn("Guía", KeyEvent.KEYCODE_GUIDE)
        ))
        col.addView(dpad(), LinearLayout.LayoutParams(dp(264), dp(264)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, dp(12), 0, dp(12))
        })
        col.addView(row(54,
            keyBtn("Atrás", KeyEvent.KEYCODE_BACK),
            keyBtn("Inicio", KeyEvent.KEYCODE_HOME),
            keyBtn("Menú", KeyEvent.KEYCODE_MENU)
        ))

        val vc = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        vc.addView(
            pill("VOL", KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN),
            LinearLayout.LayoutParams(0, dp(190), 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
        )
        val mid = vertical()
        mid.addView(keyBtn("Silencio", KeyEvent.KEYCODE_VOLUME_MUTE, size = 14f),
            LinearLayout.LayoutParams(MATCH, 0, 1f).apply { bottomMargin = dp(6) })
        mid.addView(keyBtn("Play / Pausa", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, size = 14f),
            LinearLayout.LayoutParams(MATCH, 0, 1f).apply { bottomMargin = dp(6) })
        mid.addView(keyBtn("Info", KeyEvent.KEYCODE_INFO, size = 14f),
            LinearLayout.LayoutParams(MATCH, 0, 1f))
        vc.addView(mid, LinearLayout.LayoutParams(0, dp(190), 1f).apply {
            setMargins(dp(5), dp(5), dp(5), dp(5))
        })
        vc.addView(
            pill("CANAL", KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN),
            LinearLayout.LayoutParams(0, dp(190), 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
        )
        col.addView(vc)
        return col
    }

    private fun buildNumbersTab(): View {
        val col = vertical()
        val keys = listOf(
            listOf("1" to KeyEvent.KEYCODE_1, "2" to KeyEvent.KEYCODE_2, "3" to KeyEvent.KEYCODE_3),
            listOf("4" to KeyEvent.KEYCODE_4, "5" to KeyEvent.KEYCODE_5, "6" to KeyEvent.KEYCODE_6),
            listOf("7" to KeyEvent.KEYCODE_7, "8" to KeyEvent.KEYCODE_8, "9" to KeyEvent.KEYCODE_9)
        )
        for (r in keys) {
            col.addView(row(68, *r.map { keyBtn(it.first, it.second, size = 24f) }.toTypedArray()))
        }
        col.addView(row(68,
            keyBtn("Guía", KeyEvent.KEYCODE_GUIDE),
            keyBtn("0", KeyEvent.KEYCODE_0, size = 24f),
            keyBtn("Info", KeyEvent.KEYCODE_INFO)
        ))
        col.addView(row(60,
            keyBtn("Canal −", KeyEvent.KEYCODE_CHANNEL_DOWN, repeat = true),
            keyBtn("OK", KeyEvent.KEYCODE_DPAD_CENTER, C_ACCENT),
            keyBtn("Canal +", KeyEvent.KEYCODE_CHANNEL_UP, repeat = true)
        ))
        return col
    }

    private fun buildKeyboardTab(): View {
        val col = vertical()
        col.addView(
            label(
                "Toca un cuadro de búsqueda en la TV (por ejemplo en YouTube) y escribe aquí. " +
                    "Lo que escribas aparece en la TV al instante.",
                13f, C_MUTED
            ),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(5), dp(4), dp(5), dp(10)) }
        )
        val input = EditText(this).apply {
            hint = "Escribe aquí…"
            setHintTextColor(C_MUTED)
            setTextColor(C_TEXT)
            textSize = 18f
            background = rounded(C_CARD, dp(16).toFloat())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun afterTextChanged(s: Editable?) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val c = client ?: return
                    if (!c.isReady || s == null) return
                    for (i in 0 until before) c.sendKey(KeyEvent.KEYCODE_DEL)
                    if (count > 0) c.sendText(s.subSequence(start, start + count).toString())
                }
            })
        }
        col.addView(input, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(dp(5), 0, dp(5), dp(10))
        })
        col.addView(row(56,
            keyBtn("Borrar", KeyEvent.KEYCODE_DEL, repeat = true),
            keyBtn("Buscar", KeyEvent.KEYCODE_SEARCH),
            keyBtn("Enter", KeyEvent.KEYCODE_ENTER, C_ACCENT)
        ))
        col.addView(row(56,
            keyBtn("◀", KeyEvent.KEYCODE_DPAD_LEFT, repeat = true),
            keyBtn("▼", KeyEvent.KEYCODE_DPAD_DOWN, repeat = true),
            keyBtn("▶", KeyEvent.KEYCODE_DPAD_RIGHT, repeat = true)
        ))
        return col
    }

    private fun buildAppsTab(): View {
        val col = vertical()
        val apps = listOf(
            Triple("YouTube", "https://www.youtube.com", 0xFFE62117.toInt()),
            Triple("Netflix", "https://www.netflix.com/title", 0xFFB20710.toInt()),
            Triple("Prime Video", "https://app.primevideo.com", 0xFF00A8E1.toInt()),
            Triple("Disney+", "https://www.disneyplus.com", 0xFF113CCF.toInt()),
            Triple("Max", "https://play.max.com", 0xFF5B2BD9.toInt()),
            Triple("Spotify", "https://open.spotify.com", 0xFF1DB954.toInt())
        )
        for (pair in apps.chunked(2)) {
            val cards = pair.map { (name, link, color) ->
                TextView(this).apply {
                    text = name
                    textSize = 17f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(C_TEXT)
                    gravity = Gravity.CENTER
                    background = pressState(
                        rounded(color, dp(18).toFloat()),
                        rounded(lighten(color), dp(18).toFloat())
                    )
                    isClickable = true
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        val c = client
                        if (c == null || !c.isReady) {
                            Toast.makeText(this@MainActivity, "No hay TV conectada", Toast.LENGTH_SHORT).show()
                        } else {
                            c.launchApp(link)
                        }
                    }
                }
            }
            col.addView(row(88, *cards.toTypedArray()))
        }
        col.addView(
            label("Si una app no abre, revisa que esté instalada en tu decodificador.", 12f, C_MUTED),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(5), dp(10), dp(5), 0) }
        )
        return col
    }

    private fun buildTabs(names: List<String>, views: List<View>): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(C_CARD, dp(18).toFloat())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val labels = names.map { TextView(this) }
        fun select(i: Int) {
            labels.forEachIndexed { idx, t ->
                t.background = if (idx == i) rounded(C_ACCENT, dp(14).toFloat()) else null
                t.setTextColor(if (idx == i) Color.WHITE else C_MUTED)
            }
            views.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        }
        labels.forEachIndexed { i, t ->
            t.text = names[i]
            t.textSize = 14f
            t.typeface = Typeface.DEFAULT_BOLD
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(11), 0, dp(11))
            t.setOnClickListener { select(i) }
            bar.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        select(0)
        return bar
    }

    // ---------------------------------------------------------- pantalla

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Identity.init(applicationContext)
        window.statusBarColor = C_BG
        window.navigationBarColor = C_BG
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = vertical().apply { setPadding(dp(16), dp(20), dp(16), dp(28)) }

        // Encabezado
        statusDot = View(this).apply { background = oval(C_MUTED) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Control Izzi", 24f, C_TEXT, true), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(statusDot, LinearLayout.LayoutParams(dp(12), dp(12)))
        }
        root.addView(header)

        statusView = label("Buscando televisores en tu WiFi…", 14f, C_MUTED).apply {
            setPadding(0, dp(4), 0, dp(12))
        }
        root.addView(statusView)

        changeBtn = label("Cambiar de TV", 14f, C_ACCENT, true).apply {
            visibility = View.GONE
            setPadding(0, 0, 0, dp(12))
            setOnClickListener {
                connCard.visibility =
                    if (connCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        root.addView(changeBtn)

        // Tarjeta de conexión
        connCard = vertical().apply {
            background = rounded(C_CARD, dp(20).toFloat())
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        connCard.addView(label("Televisores encontrados", 13f, C_MUTED, true))
        devicesBox = vertical()
        connCard.addView(devicesBox)

        ipInput = EditText(this).apply {
            hint = "O escribe la IP (ej. 192.168.0.20)"
            setHintTextColor(C_MUTED)
            setTextColor(C_TEXT)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_PHONE
            background = rounded(C_BTN, dp(14).toFloat())
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val connectBtn = label("Conectar", 14f, C_TEXT, true).apply {
            gravity = Gravity.CENTER
            background = pressState(
                rounded(C_ACCENT, dp(14).toFloat()),
                rounded(lighten(C_ACCENT), dp(14).toFloat())
            )
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener {
                val ip = ipInput.text.toString().trim()
                if (ip.isNotEmpty()) select(ip)
            }
        }
        val ipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ipInput, LinearLayout.LayoutParams(0, dp(46), 1f))
            addView(connectBtn, LinearLayout.LayoutParams(WRAP, dp(46)).apply { leftMargin = dp(8) })
        }
        connCard.addView(ipRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        pairAgainBtn = label("Emparejar de nuevo", 13f, C_MUTED, true).apply {
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
            setOnClickListener {
                val h = currentHost ?: return@setOnClickListener
                setPaired(h, false)
                startPairing(h)
            }
        }
        connCard.addView(pairAgainBtn)
        root.addView(connCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) })

        // Control remoto
        remotePanel = vertical().apply { visibility = View.GONE }
        val controlTab = buildControlTab()
        val numbersTab = buildNumbersTab()
        val keyboardTab = buildKeyboardTab()
        val appsTab = buildAppsTab()
        val tabs = buildTabs(
            listOf("Control", "Números", "Teclado", "Apps"),
            listOf(controlTab, numbersTab, keyboardTab, appsTab)
        )
        remotePanel.addView(tabs, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })
        val host = FrameLayout(this)
        listOf(controlTab, numbersTab, keyboardTab, appsTab).forEach {
            host.addView(it, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        remotePanel.addView(host)
        root.addView(remotePanel)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(C_BG)
            isFillViewport = true
            addView(root)
        })

        startDiscovery()

        // Reconectar solo a la última TV emparejada
        val last = prefs.getString("last", null)
        if (last != null && isPaired(last)) select(last)
    }

    private fun setStatus(text: String, color: Int = C_MUTED) {
        statusView.text = text
        statusDot.background = oval(color)
    }

    private fun showConnected(host: String) {
        connCard.visibility = View.GONE
        changeBtn.visibility = View.VISIBLE
        remotePanel.visibility = View.VISIBLE
        setStatus("Conectado a $host", C_GREEN)
    }

    private fun showDisconnected(msg: String, color: Int = C_ORANGE) {
        remotePanel.visibility = View.GONE
        changeBtn.visibility = View.GONE
        connCard.visibility = View.VISIBLE
        setStatus(msg, color)
    }

    // ------------------------------------------------- Búsqueda de TVs

    private fun startDiscovery() {
        nsd = getSystemService(NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(resolveQueue) { resolveQueue.add(info) }
                resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                ui { setStatus("No se pudo buscar TVs (error $errorCode). Escribe la IP a mano.", C_ORANGE) }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discovery = listener
        nsd.discoverServices("_androidtvremote2._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun resolveNext() {
        val info = synchronized(resolveQueue) {
            if (resolving || resolveQueue.isEmpty()) null
            else {
                resolving = true
                resolveQueue.removeAt(0)
            }
        } ?: return

        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(resolveQueue) { resolving = false }
                resolveNext()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val ip = serviceInfo.host?.hostAddress
                synchronized(resolveQueue) { resolving = false }
                if (ip != null) ui { addDevice(serviceInfo.serviceName, ip) }
                resolveNext()
            }
        })
    }

    private fun addDevice(name: String, ip: String) {
        if (!found.add(ip)) return
        val b = TextView(this).apply {
            text = "$name\n$ip"
            textSize = 14f
            setTextColor(C_TEXT)
            gravity = Gravity.CENTER
            background = pressState(
                rounded(C_BTN, dp(14).toFloat()),
                rounded(lighten(C_BTN), dp(14).toFloat())
            )
            isClickable = true
            setOnClickListener { select(ip) }
        }
        devicesBox.addView(b, LinearLayout.LayoutParams(MATCH, dp(62)).apply { topMargin = dp(8) })
        if (client == null) setStatus("Toca tu TV para conectar")
    }

    // ------------------------------------------ Emparejado y conexión

    private fun isPaired(host: String) = prefs.getStringSet("paired", emptySet())!!.contains(host)

    private fun setPaired(host: String, value: Boolean) {
        val set = HashSet(prefs.getStringSet("paired", emptySet())!!)
        if (value) set.add(host) else set.remove(host)
        prefs.edit().putStringSet("paired", set).apply()
    }

    private fun select(host: String) {
        currentHost = host
        pairAgainBtn.visibility = View.VISIBLE
        if (isPaired(host)) openRemote(host) else startPairing(host)
    }

    private fun startPairing(host: String) {
        client?.close()
        client = null
        remotePanel.visibility = View.GONE
        setStatus("Pidiendo código a la TV…", C_ORANGE)
        thread {
            val pairing = Pairing(host)
            try {
                pairing.start()
                ui { askCode(pairing, host) }
            } catch (e: Exception) {
                pairing.close()
                ui { showDisconnected("No se pudo emparejar: ${e.message}", C_RED) }
            }
        }
    }

    private fun askCode(pairing: Pairing, host: String) {
        setStatus("Escribe el código que aparece en la TV", C_ORANGE)
        val input = EditText(this).apply {
            hint = "Código de 6 caracteres"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        AlertDialog.Builder(this)
            .setTitle("Código de la TV")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Aceptar") { _, _ ->
                val code = input.text.toString()
                thread {
                    try {
                        pairing.finish(code)
                        setPaired(host, true)
                        ui { openRemote(host) }
                    } catch (e: Exception) {
                        ui { showDisconnected("Emparejado falló: ${e.message}. Toca la TV para intentar de nuevo.", C_RED) }
                    } finally {
                        pairing.close()
                    }
                }
            }
            .setNegativeButton("Cancelar") { _, _ ->
                pairing.close()
                showDisconnected("Emparejado cancelado")
            }
            .show()
    }

    private fun openRemote(host: String) {
        client?.close()
        remotePanel.visibility = View.GONE
        setStatus("Conectando a $host…", C_ORANGE)
        val myGeneration = ++generation
        val c = RemoteClient(
            host,
            onReady = {
                ui {
                    if (myGeneration == generation) {
                        prefs.edit().putString("last", host).apply()
                        showConnected(host)
                    }
                }
            },
            onClosed = { msg ->
                ui {
                    if (myGeneration == generation) {
                        showDisconnected(
                            "Desconectado" + (msg?.let { " ($it)" } ?: "") +
                                ". Toca la TV para reconectar.",
                            C_RED
                        )
                    }
                }
            }
        )
        client = c
        c.connect()
    }

    private fun sendKey(code: Int, warn: Boolean) {
        val c = client
        if (c == null || !c.isReady) {
            if (warn) Toast.makeText(this, "No hay TV conectada", Toast.LENGTH_SHORT).show()
        } else {
            c.sendKey(code)
        }
    }

    // Los botones de volumen del celular controlan el volumen de la TV
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val c = client
        if (c != null && c.isReady &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            c.sendKey(keyCode)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRepeat()
        generation++
        client?.close()
        try { discovery?.let { nsd.stopServiceDiscovery(it) } } catch (_: Exception) {}
    }
}
