package com.ronaldlazaga.prehistoricAR

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Loads the website inside a WebView (this is what the original "web2app" APK does),
 * and adds a "</>" button that opens the raw source of the page currently shown.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var loaderCard: FrameLayout
    private lateinit var viewSourceButton: TextView

    private var pendingWebPermission: PermissionRequest? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingGeoOrigin: String? = null
    private var isOffline = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        loaderCard = findViewById(R.id.loaderCard)
        viewSourceButton = findViewById(R.id.viewSourceButton)

        setupWebView()
        setupPullToRefresh()
        setupViewSourceButton()
        setupConnectivity()
        setupBackNavigation()
        requestConfiguredPermissions()

        if (savedInstanceState == null) {
            webView.loadUrl(AppConfig.websiteUrl(this))
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false   // AR / video autoplay
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            setGeolocationEnabled(true)
            allowFileAccess = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (AppConfig.pageLoaderEnabled(this@MainActivity)) {
                    loaderCard.visibility = View.VISIBLE
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loaderCard.visibility = View.GONE
                swipeRefresh.isRefreshing = false
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                // Non-http(s) links (tel:, mailto:, intent:, ...) are handed to other apps.
                if (uri.scheme != "http" && uri.scheme != "https") {
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    } catch (e: Exception) {
                        true
                    }
                }
                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                // Only react to failures of the main page, not sub-resources.
                if (request?.isForMainFrame == true) {
                    showOfflinePage()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            // Camera / microphone requests coming from the page (needed for AR).
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val needed = mutableListOf<String>()
                    for (res in request.resources) {
                        when (res) {
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> needed += Manifest.permission.CAMERA
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> needed += Manifest.permission.RECORD_AUDIO
                        }
                    }
                    val missing = needed.filter {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) !=
                            PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) {
                        request.grant(request.resources)
                    } else {
                        pendingWebPermission = request
                        ActivityCompat.requestPermissions(
                            this@MainActivity, missing.toTypedArray(), REQ_WEB_PERMISSION
                        )
                    }
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    callback?.invoke(origin, true, false)
                } else {
                    pendingGeoCallback = callback
                    pendingGeoOrigin = origin
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        REQ_GEO_PERMISSION
                    )
                }
            }

            // Links that try to open a new window/tab are opened in the browser.
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val popup = WebView(this@MainActivity)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        request?.url?.let {
                            startActivity(Intent(Intent.ACTION_VIEW, it))
                        }
                        return true
                    }
                }
                (resultMsg?.obj as? WebView.WebViewTransport)?.webView = popup
                resultMsg?.sendToTarget()
                return true
            }
        }
    }

    private fun setupPullToRefresh() {
        swipeRefresh.isEnabled = AppConfig.pullRefresh(this)
        swipeRefresh.setColorSchemeColors(0xFF00C805.toInt())
        swipeRefresh.setOnRefreshListener {
            if (isOffline) webView.loadUrl(AppConfig.websiteUrl(this)) else webView.reload()
        }
    }

    // ------------------------------------------------------------ View source

    private fun setupViewSourceButton() {
        viewSourceButton.setOnClickListener {
            val pageUrl = webView.url ?: AppConfig.websiteUrl(this)
            // Ask the page for its current DOM, i.e. the live HTML/CSS/JS markup.
            webView.evaluateJavascript(
                "(function(){return '<!DOCTYPE html>\\n' + document.documentElement.outerHTML;})()"
            ) { jsonString ->
                val html = decodeJsString(jsonString)
                SourceHolder.url = pageUrl
                SourceHolder.html = html
                startActivity(Intent(this, ViewSourceActivity::class.java))
            }
        }
    }

    /** evaluateJavascript returns a JSON-encoded string literal; turn it back into plain text. */
    private fun decodeJsString(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return try {
            org.json.JSONArray("[$raw]").getString(0)
        } catch (e: Exception) {
            raw
        }
    }

    // ------------------------------------------------------------ Connectivity

    private fun setupConnectivity() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (isOffline) {
                        isOffline = false
                        webView.loadUrl(AppConfig.websiteUrl(this@MainActivity))
                    }
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread { showOfflinePage() }
            }
        })
    }

    private fun showOfflinePage() {
        if (isOffline) return
        isOffline = true
        webView.loadUrl("file:///android_asset/no_internet.html")
    }

    // -------------------------------------------------------------- Permissions

    private fun requestConfiguredPermissions() {
        val wanted = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (wanted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, wanted.toTypedArray(), REQ_STARTUP)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }

        when (requestCode) {
            REQ_WEB_PERMISSION -> {
                pendingWebPermission?.let {
                    if (granted) it.grant(it.resources) else it.deny()
                }
                pendingWebPermission = null
            }
            REQ_GEO_PERMISSION -> {
                pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
                pendingGeoCallback = null
                pendingGeoOrigin = null
            }
        }
    }

    // ---------------------------------------------------------------- Lifecycle

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack() && !isOffline) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val REQ_STARTUP = 100
        private const val REQ_WEB_PERMISSION = 101
        private const val REQ_GEO_PERMISSION = 102
    }
}
