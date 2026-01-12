package com.anonymousassociate.betterpantry

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.anonymousassociate.betterpantry.databinding.ActivityMainBinding
import com.anonymousassociate.betterpantry.ui.HomeFragment
import com.anonymousassociate.betterpantry.ui.NotificationsFragment
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var authManager: AuthManager
    private lateinit var apiService: PantryApiService
    private var isAuthenticating = false
    private var isAuthenticated = false
    private var hasShownBiometricThisSession = false
    private var isOpeningBrowser = false
    private var wasInBackground = false

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var notificationCheckRunnable: Runnable? = null
    private var scheduleRefreshRunnable: Runnable? = null
    
    private var pendingNotificationJson: String? = null

    private var lockDialog: Dialog? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authManager = AuthManager(this)
        apiService = PantryApiService(authManager)

        // Fix for purple/black bars in cutout/notch area
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = 
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        
        // Ensure system bars match background
        val background = ContextCompat.getColor(this, R.color.background_color)
        window.statusBarColor = background
        window.navigationBarColor = background

        // Initialize Notification Channels
        NotificationWorker.createNotificationChannels(this)

        askNotificationPermission()

        // Handle Window Insets for Symmetrical Centering & Full-Width Navbar
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val displayCutout = insets.displayCutout
            
            val leftInset = displayCutout?.safeInsetLeft ?: insets.systemWindowInsetLeft
            val rightInset = displayCutout?.safeInsetRight ?: insets.systemWindowInsetRight
            
            // Calculate max inset to enforce symmetry
            val maxHorizontalInset = max(leftInset, rightInset)
            
            // Apply symmetric padding to content container only
            binding.fragmentContainer.setPadding(
                maxHorizontalInset, 
                binding.fragmentContainer.paddingTop, 
                maxHorizontalInset, 
                binding.fragmentContainer.paddingBottom
            )
            
            // Pad the BottomNav CONTENT (icons), but the background (navBackground view) remains full width
            binding.bottomNavigation.setPadding(
                maxHorizontalInset,
                binding.bottomNavigation.paddingTop,
                maxHorizontalInset,
                binding.bottomNavigation.paddingBottom
            )
            
            insets
        }

        // Handle Back Stack Changes to sync Bottom Nav
        supportFragmentManager.addOnBackStackChangedListener {
            val current = supportFragmentManager.findFragmentById(binding.fragmentContainer.id)
            if (current is HomeFragment) {
                if (binding.bottomNavigation.selectedItemId != R.id.nav_home) {
                    binding.bottomNavigation.menu.findItem(R.id.nav_home).isChecked = true
                }
            } else if (current is com.anonymousassociate.betterpantry.ui.ScheduleFragment) {
                if (binding.bottomNavigation.selectedItemId != R.id.nav_schedule) {
                    binding.bottomNavigation.menu.findItem(R.id.nav_schedule).isChecked = true
                }
            } else if (current is com.anonymousassociate.betterpantry.ui.PeopleFragment) {
                if (binding.bottomNavigation.selectedItemId != R.id.nav_people) {
                    binding.bottomNavigation.menu.findItem(R.id.nav_people).isChecked = true
                }
            } else if (current is NotificationsFragment) {
                if (binding.bottomNavigation.selectedItemId != R.id.nav_notifications) {
                    binding.bottomNavigation.menu.findItem(R.id.nav_notifications).isChecked = true
                }
            }
        }

        // Parse intent immediately to check for notification deep link
        parseIntentForNotification(intent)

        // Binding listener for the old button (optional/fallback)
        binding.unlockButton.setOnClickListener {
            showBiometricPrompt()
        }

        if (savedInstanceState != null) {
            isAuthenticated = savedInstanceState.getBoolean(KEY_IS_AUTHENTICATED, false)
            hasShownBiometricThisSession = savedInstanceState.getBoolean(KEY_HAS_SHOWN_BIOMETRIC, false)
            isOpeningBrowser = savedInstanceState.getBoolean(KEY_IS_OPENING_BROWSER, false)
            wasInBackground = savedInstanceState.getBoolean(KEY_WAS_IN_BACKGROUND, false)
        }

        handleOAuthCallback(intent)

        if (isAuthenticated) {
            proceedToApp()
            return
        }

        if (authManager.isTokenValid()) {
            if (!hasShownBiometricThisSession) {
                showBiometricPrompt()
            } else {
                proceedToApp()
            }
        } else {
            lifecycleScope.launch {
                if (authManager.refreshToken()) {
                    if (!hasShownBiometricThisSession) {
                        showBiometricPrompt()
                    } else {
                        proceedToApp()
                    }
                } else {
                    showLoginScreen()
                }
            }
        }
    }

    private fun parseIntentForNotification(intent: Intent?) {
        if (intent?.getBooleanExtra("open_notifications_tab", false) == true) {
            pendingNotificationJson = intent.getStringExtra("notification_data")
            // Clear the flag so it's not reused on config change
            intent.removeExtra("open_notifications_tab")
            intent.removeExtra("notification_data")
        }
    }

    private fun setupBackgroundWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<NotificationWorker>(20, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "PantryNotificationWork",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )

        val oneTimeRequest = OneTimeWorkRequest.Builder(NotificationWorker::class.java)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueue(oneTimeRequest)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_IS_AUTHENTICATED, isAuthenticated)
        outState.putBoolean(KEY_HAS_SHOWN_BIOMETRIC, hasShownBiometricThisSession)
        outState.putBoolean(KEY_IS_OPENING_BROWSER, isOpeningBrowser)
        outState.putBoolean(KEY_WAS_IN_BACKGROUND, wasInBackground)
    }

    override fun onResume() {
        super.onResume()

        if (isOpeningBrowser) {
            isOpeningBrowser = false
            // Return to Home after browser closes
            // Use fragment transaction to ensure back stack is correct if needed, but nav handling does it
            // If user went to settings -> browser, they are still on current tab.
            // If user wants "return to our app's home page" from browser:
            // loadFragment(HomeFragment()) // REMOVED: This was causing the redirect.
            
            if (wasInBackground) {
                wasInBackground = false
            } else {
                hasShownBiometricThisSession = false
                if (isAuthenticated) {
                    isAuthenticated = false
                    showBiometricPrompt()
                }
            }
            return
        }

        wasInBackground = false

        if (isAuthenticated && !hasShownBiometricThisSession && !isAuthenticating) {
            isAuthenticated = false
            showBiometricPrompt()
        }
        
        startPeriodicChecks()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            wasInBackground = true
        }
    }

    override fun onPause() {
        super.onPause()
        if (isChangingConfigurations || isFinishing) {
            return
        }
        if (isOpeningBrowser) {
            return
        }
        hasShownBiometricThisSession = false
        stopPeriodicChecks()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthCallback(intent)
        parseIntentForNotification(intent)
        if (pendingNotificationJson != null && isAuthenticated) {
            proceedToApp()
        }
    }

    private fun handleOAuthCallback(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data != null && data.scheme == "pantry" && data.host == "login") {
            val code = data.getQueryParameter("code")
            if (code != null) {
                lifecycleScope.launch {
                    if (authManager.exchangeCodeForToken(code)) {
                        isAuthenticated = true
                        hasShownBiometricThisSession = true
                        proceedToApp()
                    } else {
                        showLoginScreen()
                    }
                }
            }
        }
    }

    private fun showBiometricPrompt() {
        // Show lock screen immediately so it's visible behind the biometric prompt
        showLockScreen()

        val biometricManager = BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

        if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
            val executor = ContextCompat.getMainExecutor(this)
            val biometricPrompt = BiometricPrompt(this, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        isAuthenticated = true
                        hasShownBiometricThisSession = true
                        proceedToApp()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                            errorCode == BiometricPrompt.ERROR_CANCELED) {
                            showLockScreen()
                        }
                    }
                })

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Better Pantry")
                .setSubtitle("Authenticate to access your schedule")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()

            biometricPrompt.authenticate(promptInfo)
        } else {
            isAuthenticated = true
            hasShownBiometricThisSession = true
            proceedToApp()
        }
    }

    private fun showLockScreen() {
        binding.loginWebView.visibility = View.GONE
        // binding.fragmentContainer.visibility = View.GONE // Keep fragments visible behind dialog so dialogs don't disappear? No, we want to hide sensitive data.
        // If we hide fragmentContainer, fragment dialogs *might* detach or hide depending on implementation.
        // But the user says "I don't want to close the popups".
        // Fragment Dialogs are independent windows. Hiding the fragment container View does not close them.
        binding.fragmentContainer.visibility = View.VISIBLE // Changed to VISIBLE to ensure dialogs rooted in fragments stay alive if dependent on view hierarchy logic? Actually GONE is safer for privacy.
        // Let's keep it visible but covered by the Lock Dialog? 
        // If I make it GONE, and the dialog is attached to the fragment's view, it might be an issue?
        // DialogFragment uses specific Fragment Manager.
        // User wants popups to persist.
        // I will keep fragmentContainer VISIBLE but covered by lockDialog.
        
        binding.bottomNavigation.visibility = View.GONE
        binding.navShadow.visibility = View.GONE
        binding.authRetryContainer.visibility = View.GONE // We use Dialog now
        
        if (lockDialog == null) {
            lockDialog = Dialog(this, android.R.style.Theme_NoTitleBar).apply {
                setContentView(R.layout.dialog_lock_screen)
                setCancelable(false)
                
                window?.apply {
                    setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                    val background = ContextCompat.getColor(context, R.color.background_color)
                    statusBarColor = background
                    navigationBarColor = background
                }
                
                findViewById<View>(R.id.unlockButton).setOnClickListener {
                    showBiometricPrompt()
                }
            }
        }
        lockDialog?.show()
    }

    private fun showLoginScreen() {
        if (isAuthenticating) return
        isAuthenticating = true
        
        lockDialog?.dismiss() // Dismiss lock screen if showing

        binding.bottomNavigation.visibility = View.GONE
        binding.navShadow.visibility = View.GONE
        binding.fragmentContainer.visibility = View.GONE
        binding.authRetryContainer.visibility = View.GONE
        binding.loginWebView.visibility = View.VISIBLE

        val webView = binding.loginWebView
        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val url = request?.url?.toString()
                println("DEBUG: WebView shouldOverrideUrlLoading: $url")
                return handleUrl(view, url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                println("DEBUG: WebView shouldOverrideUrlLoading (deprecated): $url")
                return handleUrl(view, url)
            }

            private fun handleUrl(view: WebView?, url: String?): Boolean {
                if (url != null && url.startsWith("pantry://")) {
                    println("DEBUG: WebView captured redirect scheme: $url")
                    // Stop the WebView from trying to actually load this custom scheme
                    view?.stopLoading()
                    val uri = Uri.parse(url)
                    handleOAuthCallback(Intent(Intent.ACTION_VIEW, uri))
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                println("DEBUG: WebView onPageStarted: $url")
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                println("DEBUG: WebView onPageFinished: $url")
                super.onPageFinished(view, url)
            }
            
            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                println("DEBUG: WebView onReceivedError: ${error?.description}, Code: ${error?.errorCode}, URL: ${request?.url}")
                super.onReceivedError(view, request, error)
            }
        }
        
        webView.loadUrl(authManager.getAuthorizationUrl())
    }

    private fun proceedToApp() {
        isAuthenticating = false
        lockDialog?.dismiss()
        lockDialog = null
        
        binding.loginWebView.visibility = View.GONE
        binding.authRetryContainer.visibility = View.GONE
        binding.fragmentContainer.visibility = View.VISIBLE
        binding.bottomNavigation.visibility = View.VISIBLE
        binding.navShadow.visibility = View.VISIBLE

        setupBottomNavigation()
        setupBackgroundWork()

        if (pendingNotificationJson != null) {
            val fragment = NotificationsFragment()
            val args = Bundle()
            args.putString("pending_notification_json", pendingNotificationJson)
            fragment.arguments = args
            
            // Note: Don't set selectedItemId manually here if loadFragment does it, 
            // but we want initial state correct. 
            // Better: loadFragment updates the UI via listener? No, listener reacts to commit.
            // Just loadFragment is enough if listener updates UI.
            loadFragment(fragment)
            
            pendingNotificationJson = null // Consume the intent
        } else {
            if (supportFragmentManager.findFragmentById(binding.fragmentContainer.id) == null) {
                loadFragment(HomeFragment())
            }
        }
        
        checkAndShowWhatsNew()
    }

    private fun checkAndShowWhatsNew() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val currentVersion = BuildConfig.VERSION_CODE
        val lastVersion = prefs.getInt("last_version_code", -1)

        if (lastVersion == -1) {
            // Fresh install: save current version so we don't show dialog
            prefs.edit().putInt("last_version_code", currentVersion).apply()
            return
        }

        if (currentVersion > lastVersion) {
            val releaseNotes = getString(R.string.release_notes)
            if (releaseNotes.isNotEmpty()) {
                showWhatsNewDialog(releaseNotes, currentVersion, prefs)
            }
        }
    }

    private fun showWhatsNewDialog(body: String, currentVersion: Int, prefs: android.content.SharedPreferences) {
        if (isFinishing || isDestroyed) return

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_whats_new)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        // Set width to 90% of screen
        val displayMetrics = resources.displayMetrics
        val width = (displayMetrics.widthPixels * 0.9).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        val titleText = dialog.findViewById<android.widget.TextView>(R.id.titleText)
        val notesText = dialog.findViewById<android.widget.TextView>(R.id.releaseNotesText)
        val gotItButton = dialog.findViewById<android.widget.Button>(R.id.gotItButton)
        
        // Set dynamic title
        titleText.text = "What's new in Better Pantry ${BuildConfig.VERSION_NAME}"

        // Simple Markdown-ish formatting to HTML
        // Replace Headers
        var formattedText = body
            .replace(Regex("### (.*)"), "<b>$1</b><br/>")
            .replace(Regex("## (.*)"), "<b>$1</b><br/>")
            // Bold **text** -> <b>text</b>
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
            // List items * or -
            .replace(Regex("^\\* ", RegexOption.MULTILINE), "• ")
            .replace(Regex("^- ", RegexOption.MULTILINE), "• ")
            // Newlines
            .replace("\r\n", "<br/>")
            .replace("\n", "<br/>")

        notesText.text = android.text.Html.fromHtml(formattedText, android.text.Html.FROM_HTML_MODE_COMPACT)

        // Save version on dismiss/click
        val saveVersion = {
            prefs.edit().putInt("last_version_code", currentVersion).apply()
        }

        gotItButton.setOnClickListener {
            saveVersion()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            saveVersion()
        }
        
        dialog.show()
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val currentFragment = supportFragmentManager.findFragmentById(binding.fragmentContainer.id)
            when (item.itemId) {
                R.id.nav_home -> {
                    if (currentFragment !is HomeFragment) loadFragment(HomeFragment())
                    true
                }
                R.id.nav_schedule -> {
                    if (currentFragment !is com.anonymousassociate.betterpantry.ui.ScheduleFragment) loadFragment(com.anonymousassociate.betterpantry.ui.ScheduleFragment())
                    true
                }
                R.id.nav_people -> {
                    if (currentFragment !is com.anonymousassociate.betterpantry.ui.PeopleFragment) loadFragment(com.anonymousassociate.betterpantry.ui.PeopleFragment())
                    true
                }
                R.id.nav_notifications -> {
                    if (currentFragment !is NotificationsFragment) loadFragment(NotificationsFragment())
                    true
                }
                else -> false
            }
        }

        // Disable tooltips and long-click vibration
        val menu = binding.bottomNavigation.menu
        val bottomNav = binding.bottomNavigation
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            // Clear tooltip on MenuItem
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                item.tooltipText = null
            }
            // Find the view to disable long click and haptic feedback
            bottomNav.findViewById<View>(item.itemId)?.apply {
                isHapticFeedbackEnabled = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    tooltipText = "" // Clear tooltip on View
                }
                setOnLongClickListener { 
                    true // Consume event to prevent system tooltip
                }
            }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun updateNotificationBadge(count: Int) {
        val bottomNav = binding.bottomNavigation
        bottomNav.removeBadge(R.id.nav_home)
        val badge = bottomNav.getOrCreateBadge(R.id.nav_notifications)
        
        if (count > 0) {
            badge.isVisible = true
            badge.number = count
            badge.backgroundColor = getColor(android.R.color.holo_red_dark)
            val verOffset = (4 * resources.displayMetrics.density).toInt()
            badge.verticalOffset = verOffset
        } else {
            badge.isVisible = false
        }
    }

    fun switchToHome() {
        loadFragment(HomeFragment())
    }

    fun logout() {
        authManager.clearTokens()
        // Clearing cache requires context, assume ScheduleCache handles it
        // ScheduleCache(this).clear() // If accessible
        isAuthenticated = false
        hasShownBiometricThisSession = false
        isAuthenticating = false
        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        binding.loginWebView.clearCache(true)
        binding.loginWebView.clearHistory()
        showLoginScreen()
    }

    private fun loadFragment(fragment: Fragment) {
        val fragmentManager = supportFragmentManager
        val transaction = fragmentManager.beginTransaction()
            .setReorderingAllowed(true)

        if (fragment is HomeFragment) {
            // Clear back stack to return to root state
            fragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            transaction.replace(binding.fragmentContainer.id, fragment)
        } else {
            // If we are navigating to a tab, ensure we don't stack tabs on top of each other.
            // We want Home -> Tab. Not Home -> Tab1 -> Tab2.
            // So we pop the previous tab (if any) before adding the new one.
            if (fragmentManager.backStackEntryCount > 0) {
                fragmentManager.popBackStack()
            }
            transaction.replace(binding.fragmentContainer.id, fragment)
            transaction.addToBackStack(null) // Enable system predictive back
        }
        
        transaction.commit()
    }

    fun openBrowser(url: String) {
        isOpeningBrowser = true
        val builder = androidx.browser.customtabs.CustomTabsIntent.Builder()
        val isDarkMode = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        val toolbarColor = getColor(R.color.background_color)
        builder.setToolbarColor(toolbarColor)
        builder.setShowTitle(true)
        builder.setColorScheme(
            if (isDarkMode) {
                androidx.browser.customtabs.CustomTabsIntent.COLOR_SCHEME_DARK
            } else {
                androidx.browser.customtabs.CustomTabsIntent.COLOR_SCHEME_LIGHT
            }
        )
        val customTabsIntent = builder.build()
        customTabsIntent.launchUrl(this, Uri.parse(url))
    }

    fun checkHomeFragmentNotifications() {
        val homeFragment = supportFragmentManager.fragments.find { it is HomeFragment } as? HomeFragment
        homeFragment?.checkNotifications()
    }

    private fun reloadHomeFragmentSchedule() {
        val homeFragment = supportFragmentManager.fragments.find { it is HomeFragment } as? HomeFragment
        homeFragment?.loadScheduleFromActivity()
    }

    private fun startPeriodicChecks() {
        stopPeriodicChecks() // Ensure no duplicates
        notificationCheckRunnable = object : Runnable {
            override fun run() {
                if (isAuthenticated) {
                    NotificationWorker.checkAndSendNewNotifications(this@MainActivity)
                    lifecycleScope.launch {
                        try {
                            val count = apiService.getNotificationCount()
                            updateNotificationBadge(count)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                handler.postDelayed(this, 30 * 1000)
            }
        }
        handler.post(notificationCheckRunnable!!)

        scheduleRefreshRunnable = object : Runnable {
            override fun run() {
                if (isAuthenticated) {
                    reloadHomeFragmentSchedule()
                }
                handler.postDelayed(this, 5 * 60 * 1000)
            }
        }
        handler.post(scheduleRefreshRunnable!!)
    }

    private fun stopPeriodicChecks() {
        notificationCheckRunnable?.let { handler.removeCallbacks(it) }
        scheduleRefreshRunnable?.let { handler.removeCallbacks(it) }
    }

    companion object {
        private const val KEY_IS_AUTHENTICATED = "is_authenticated"
        private const val KEY_HAS_SHOWN_BIOMETRIC = "has_shown_biometric"
        private const val KEY_IS_OPENING_BROWSER = "is_opening_browser"
        private const val KEY_WAS_IN_BACKGROUND = "was_in_background"
    }
}