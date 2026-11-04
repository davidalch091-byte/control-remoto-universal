package com.izziremote

import android.app.Activity
import android.app.AlertDialog
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var devicesBox: LinearLayout
    private lateinit var ipInput: EditText
    private lateinit var pairAgainBtn: Button
    private lateinit var remotePanel: LinearLayout

    private var client: RemoteClient? = null
    private var currentHost: String? = null
    private var generation = 0

    private val found = LinkedHashSet<String>()
    private lateinit var nsd: NsdManager
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolveQueue = mutableListOf<NsdServiceInfo>()
    private var resolving = false

    private val prefs by lazy { getSharedPreferences("izzi", MODE_PRIVATE) }

    // ---------------------------------------------------------------- UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Identity.init(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "Control Izzi TV"
            textSize = 24f
        })

        statusView = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
            text = "Buscando televisores en tu WiFi…"
        }
        root.addView(statusView)

        root.addView(TextView(this).apply {
            text = "Televisores encontrados"
            textSize = 13f
        })
        devicesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(devicesBox)

        ipInput = EditText(this).apply {
            hint = "O escribe la IP (ej. 192.168.1.50)"
            inputType = InputType.TYPE_CLASS_PHONE
            textSize = 14f
        }
        val connectBtn = Button(this).apply {
            text = "Conectar"
            setOnClickListener {
                val ip = ipInput.text.toString().trim()
                if (ip.isNotEmpty()) select(ip)
            }
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ipInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(connectBtn)
        })

        pairAgainBtn = Button(this).apply {
            text = "Emparejar de nuevo"
            visibility = View.GONE
            setOnClickListener {
                val h = currentHost ?: return@setOnClickListener
                setPaired(h, false)
                startPairing(h)
            }
        }
        root.addView(pairAgainBtn)

        remotePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(16), 0, 0)
        }
        buildRemote(remotePanel)
        root.addView(remotePanel)

        setContentView(ScrollView(this).apply { addView(root) })

        startDiscovery()
    }

    private fun buildRemote(p: LinearLayout) {
        p.addView(row(
            key("⏻", KeyEvent.KEYCODE_POWER),
            key("TV", KeyEvent.KEYCODE_TV),
            key("Guía", KeyEvent.KEYCODE_GUIDE)
        ))
        p.addView(row(View(this), key("▲", KeyEvent.KEYCODE_DPAD_UP), View(this)))
        p.addView(row(
            key("◀", KeyEvent.KEYCODE_DPAD_LEFT),
            key("OK", KeyEvent.KEYCODE_DPAD_CENTER),
            key("▶", KeyEvent.KEYCODE_DPAD_RIGHT)
        ))
        p.addView(row(View(this), key("▼", KeyEvent.KEYCODE_DPAD_DOWN), View(this)))
        p.addView(row(
            key("Atrás", KeyEvent.KEYCODE_BACK),
            key("Inicio", KeyEvent.KEYCODE_HOME),
            key("Menú", KeyEvent.KEYCODE_MENU)
        ))
        p.addView(row(
            key("Vol +", KeyEvent.KEYCODE_VOLUME_UP),
            key("Mute", KeyEvent.KEYCODE_VOLUME_MUTE),
            key("Canal +", KeyEvent.KEYCODE_CHANNEL_UP)
        ))
        p.addView(row(
            key("Vol −", KeyEvent.KEYCODE_VOLUME_DOWN),
            key("⏯", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
            key("Canal −", KeyEvent.KEYCODE_CHANNEL_DOWN)
        ))
        p.addView(row(
            key("1", KeyEvent.KEYCODE_1), key("2", KeyEvent.KEYCODE_2), key("3", KeyEvent.KEYCODE_3)
        ))
        p.addView(row(
            key("4", KeyEvent.KEYCODE_4), key("5", KeyEvent.KEYCODE_5), key("6", KeyEvent.KEYCODE_6)
        ))
        p.addView(row(
            key("7", KeyEvent.KEYCODE_7), key("8", KeyEvent.KEYCODE_8), key("9", KeyEvent.KEYCODE_9)
        ))
        p.addView(row(
            key("Info", KeyEvent.KEYCODE_INFO),
            key("0", KeyEvent.KEYCODE_0),
            key("Fuente", KeyEvent.KEYCODE_TV_INPUT)
        ))
    }

    private fun key(label: String, code: Int): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            sendKey(code)
        }
    }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach {
            addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun ui(block: () -> Unit) = runOnUiThread(block)
    private fun setStatus(t: String) { statusView.text = t }

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
                ui { setStatus("No se pudo buscar TVs (error $errorCode). Escribe la IP a mano.") }
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
        devicesBox.addView(Button(this).apply {
            text = "$name\n$ip"
            isAllCaps = false
            setOnClickListener { select(ip) }
        })
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
        setStatus("Pidiendo código a la TV…")
        thread {
            val pairing = Pairing(host)
            try {
                pairing.start()
                ui { askCode(pairing, host) }
            } catch (e: Exception) {
                pairing.close()
                ui { setStatus("No se pudo emparejar: ${e.message}") }
            }
        }
    }

    private fun askCode(pairing: Pairing, host: String) {
        setStatus("Escribe el código que aparece en la TV")
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
                        ui { setStatus("Emparejado falló: ${e.message}. Toca la TV para intentar de nuevo.") }
                    } finally {
                        pairing.close()
                    }
                }
            }
            .setNegativeButton("Cancelar") { _, _ ->
                pairing.close()
                setStatus("Emparejado cancelado")
            }
            .show()
    }

    private fun openRemote(host: String) {
        client?.close()
        remotePanel.visibility = View.GONE
        setStatus("Conectando a $host…")
        val myGeneration = ++generation
        val c = RemoteClient(
            host,
            onReady = {
                ui {
                    if (myGeneration == generation) {
                        setStatus("Conectado a $host")
                        remotePanel.visibility = View.VISIBLE
                    }
                }
            },
            onClosed = { msg ->
                ui {
                    if (myGeneration == generation) {
                        remotePanel.visibility = View.GONE
                        setStatus(
                            "Desconectado" + (msg?.let { " ($it)" } ?: "") +
                                ". Toca la TV para reconectar o usa \"Emparejar de nuevo\"."
                        )
                    }
                }
            }
        )
        client = c
        c.connect()
    }

    private fun sendKey(code: Int) {
        val c = client
        if (c == null || !c.isReady) {
            Toast.makeText(this, "No hay TV conectada", Toast.LENGTH_SHORT).show()
        } else {
            c.sendKey(code)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        generation++
        client?.close()
        try { discovery?.let { nsd.stopServiceDiscovery(it) } } catch (_: Exception) {}
    }
}
