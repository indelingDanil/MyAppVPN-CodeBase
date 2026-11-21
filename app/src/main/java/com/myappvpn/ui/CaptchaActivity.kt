package com.myappvpn.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.myappvpn.R
import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.service.MyVpnService
import com.myappvpn.util.AppLocale
import com.myappvpn.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * OpenFlux carrier captcha ("OFNOTE captcha <url>"): shows the check page in a WebView; «Готово»
 * hands the page's cookies (CookieManager, for that URL) to the running bridge as an
 * "OFCOOKIES" line. The app is excluded from the VPN, so the page loads over the same network
 * the carrier uses.
 */
@AndroidEntryPoint
class CaptchaActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_URL = "url"

        fun intent(context: Context, url: String): Intent =
            Intent(context, CaptchaActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    @Inject lateinit var preferences: AppPreferences

    private var webView: WebView? = null

    // In-app language on Android < 13 (no-op on 13+, where the system applies it).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        if (url == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            val themeMode by preferences.themeMode.collectAsState(initial = null)
            val systemDark = isSystemInDarkTheme()
            val dark = when (themeMode) {
                ThemeMode.LIGHT.name -> false
                ThemeMode.DARK.name -> true
                else -> systemDark
            }
            MyAppVPNTheme(darkTheme = dark) {
                CaptchaContent(url = url, onDone = { submit(url) }, onBack = { finish() })
            }
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    private fun submit(url: String) {
        val cm = CookieManager.getInstance()
        cm.flush()
        // Cookies for the check page's host (plus its parent domains, as a browser would send).
        val cookies = cm.getCookie(url)?.trim().orEmpty()
        val msg = when {
            cookies.isEmpty() -> R.string.openflux_captcha_no_cookies
            !MyVpnService.submitOpenFluxCookies(cookies) -> R.string.openflux_captcha_not_running
            else -> R.string.openflux_captcha_sent
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        if (msg != R.string.openflux_captcha_no_cookies) finish()
    }

    @SuppressLint("SetJavaScriptEnabled")
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun CaptchaContent(url: String, onDone: () -> Unit, onBack: () -> Unit) {
        Scaffold(
            containerColor = AetherBackground,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.openflux_captcha_title),
                            color = AetherTextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.Default.ArrowBack,
                                contentDescription = stringResource(R.string.openflux_cd_back),
                                tint = AetherTextPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = AetherBackground)
                )
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AetherSurface)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.openflux_captcha_hint),
                        color = AetherTextSecondary,
                        fontSize = 12.sp
                    )
                    Button(
                        onClick = onDone,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AetherCyan,
                            contentColor = AetherBackground
                        )
                    ) {
                        Text(stringResource(R.string.openflux_captcha_done), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        ) { padding ->
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = WebViewClient()   // stay inside the WebView
                        loadUrl(url)
                        webView = this
                    }
                }
            )
        }
    }
}
