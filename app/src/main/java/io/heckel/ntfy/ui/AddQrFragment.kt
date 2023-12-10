package io.heckel.ntfy.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.SparseArray
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.User
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class AddQrFragment : DialogFragment() {
    private val api = ApiService()

    private lateinit var repository: Repository
    private lateinit var subscribeListener: SubscribeListener
    private lateinit var appBaseUrl: String
    private var defaultBaseUrl: String? = null

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var subscribeCameraPreview: PreviewView

    private lateinit var subscribeView: View
    private lateinit var loginView: View
    private lateinit var positiveButton: Button
    private lateinit var negativeButton: Button

    // Subscribe page
    private lateinit var subscribeInstantDeliveryBox: View
    private lateinit var subscribeInstantDeliveryCheckbox: CheckBox
    private lateinit var subscribeInstantDeliveryDescription: View
    private lateinit var subscribeForegroundDescription: TextView
    private lateinit var subscribeProgress: ProgressBar
    private lateinit var subscribeErrorText: TextView
    private lateinit var subscribeErrorTextImage: View
    private lateinit var subscribeCameraPreviewPermissionText: TextView

    // Login page
    private lateinit var loginUsernameText: TextInputEditText
    private lateinit var loginPasswordText: TextInputEditText
    private lateinit var loginProgress: ProgressBar
    private lateinit var loginErrorText: TextView
    private lateinit var loginErrorTextImage: View

    interface SubscribeListener {
        fun onSubscribe(topic: String, baseUrl: String, instant: Boolean)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        subscribeListener = activity as SubscribeListener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (activity == null) {
            throw IllegalStateException("Activity cannot be null")
        }

        // Dependencies (Fragments need a default constructor)
        repository = Repository.getInstance(requireActivity())
        appBaseUrl = getString(R.string.app_base_url)
        defaultBaseUrl = repository.getDefaultBaseUrl()

        // Build root view
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_add_qr_dialog, null)

        // Main "pages"
        subscribeView = view.findViewById(R.id.add_dialog_subscribe_view)
        subscribeView.visibility = View.VISIBLE
        loginView = view.findViewById(R.id.add_dialog_login_view)
        loginView.visibility = View.GONE

        // Fields for "subscribe page"
        subscribeInstantDeliveryBox = view.findViewById(R.id.add_dialog_subscribe_instant_delivery_box)
        subscribeInstantDeliveryCheckbox = view.findViewById(R.id.add_dialog_subscribe_instant_delivery_checkbox)
        subscribeInstantDeliveryDescription = view.findViewById(R.id.add_dialog_subscribe_instant_delivery_description)
        subscribeForegroundDescription = view.findViewById(R.id.add_dialog_subscribe_foreground_description)
        subscribeProgress = view.findViewById(R.id.add_dialog_subscribe_progress)
        subscribeErrorText = view.findViewById(R.id.add_dialog_subscribe_error_text)
        subscribeErrorText.visibility = View.GONE
        subscribeErrorTextImage = view.findViewById(R.id.add_dialog_subscribe_error_text_image)
        subscribeErrorTextImage.visibility = View.GONE
        subscribeCameraPreview = view.findViewById(R.id.add_dialog_subscribe_camera_preview)
        subscribeCameraPreviewPermissionText = view.findViewById(R.id.add_dialog_subscribe_camera_preview_denied_text)

        // Fields for "login page"
        loginUsernameText = view.findViewById(R.id.add_dialog_login_username)
        loginPasswordText = view.findViewById(R.id.add_dialog_login_password)
        loginProgress = view.findViewById(R.id.add_dialog_login_progress)
        loginErrorText = view.findViewById(R.id.add_dialog_login_error_text)
        loginErrorTextImage = view.findViewById(R.id.add_dialog_login_error_text_image)

        // Set foreground description text
        subscribeForegroundDescription.text = getString(R.string.add_dialog_foreground_description, shortUrl(appBaseUrl))

        // Show/hide based on flavor (faster shortcut for validateInputSubscribeView, which can only run onShow)
        if (!BuildConfig.FIREBASE_AVAILABLE) {
            subscribeInstantDeliveryBox.visibility = View.GONE
        }

        // Username/password validation on type
        val loginTextWatcher = AfterChangedTextWatcher {
            validateInputLoginView()
        }
        loginUsernameText.addTextChangedListener(loginTextWatcher)
        loginPasswordText.addTextChangedListener(loginTextWatcher)

        // Build dialog
        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton(R.string.add_dialog_button_subscribe) { _, _ ->
                // This will be overridden below to avoid closing the dialog immediately
            }
            .setNegativeButton(R.string.add_dialog_button_cancel) { _, _ ->
                // This will be overridden below
            }
            .create()

        // Show keyboard when the dialog is shown (see https://stackoverflow.com/a/19573049/1440785)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        // Add logic to disable "Subscribe" button on invalid input
        dialog.setOnShowListener {
            positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.isEnabled = false
            positiveButton.setOnClickListener {
                positiveButtonClick()
            }
            negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            negativeButton.setOnClickListener {
                negativeButtonClick()
            }

            subscribeInstantDeliveryCheckbox.setOnCheckedChangeListener { _, _ ->
                validateInputSubscribeView()
            }

            cameraExecutor = Executors.newSingleThreadExecutor()
            checkIfCameraPermissionIsGranted()
        }

        return dialog
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun checkCameraPermissionsRaw(): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkIfCameraPermissionIsGranted() {
        if (checkCameraPermissionsRaw()) {
            startCamera()
        } else {
            subscribeCameraPreviewPermissionText.visibility = View.VISIBLE
            val requiredPermissions = arrayOf(Manifest.permission.CAMERA)
            requestPermissions(requiredPermissions, 0)
        }
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (checkCameraPermissionsRaw()) {
            startCamera()
        }
    }

    private fun vibratePhone(context: Context) {
        // This is deprecated, but we aren't using a high enough version for the new API
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) {
            // Vibrate for 500 milliseconds
            vibrator.vibrate(200)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        subscribeCameraPreviewPermissionText.visibility = View.GONE

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(subscribeCameraPreview.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, QrCodeAnalyzer { urlString ->
                        var url = URL(urlString)
                        var baseUrl = "${url.protocol}://${url.host}"
                        var route = url.path.replaceFirst("/", "")

                        subscribeBaseUrlText.setText(baseUrl)
                        subscribeTopicText.setText(route)
                        validateInputSubscribeView {
                            if (positiveButton.isEnabled) {
                                positiveButtonClick()
                                vibratePhone(requireContext())
                            }
                        }

                    })
                }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun positiveButtonClick() {
        val topic = subscribeTopicText.text.toString()
        val baseUrl = getBaseUrl()
        if (subscribeView.visibility == View.VISIBLE) {
            checkReadAndMaybeShowLogin(baseUrl, topic)
        } else if (loginView.visibility == View.VISIBLE) {
            loginAndMaybeDismiss(baseUrl, topic)
        }
    }

    private fun checkReadAndMaybeShowLogin(baseUrl: String, topic: String) {
        subscribeProgress.visibility = View.VISIBLE
        subscribeErrorText.visibility = View.GONE
        subscribeErrorTextImage.visibility = View.GONE
        enableSubscribeView(false)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val user = repository.getUser(baseUrl) // May be null
                val authorized = api.checkAuth(baseUrl, topic, user)
                if (authorized) {
                    Log.d(TAG, "Access granted to topic ${topicUrl(baseUrl, topic)}")
                    dismissDialog()
                } else {
                    if (user != null) {
                        Log.w(TAG, "Access not allowed to topic ${topicUrl(baseUrl, topic)}, but user already exists")
                        showErrorAndReenableSubscribeView(getString(R.string.add_dialog_login_error_not_authorized, user.username))
                    } else {
                        Log.w(TAG, "Access not allowed to topic ${topicUrl(baseUrl, topic)}, showing login dialog")
                        val activity = activity ?: return@launch // We may have pressed "Cancel"
                        activity.runOnUiThread {
                            showLoginView(activity)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Connection to topic failed: ${e.message}", e)
                showErrorAndReenableSubscribeView(e.message)
            }
        }
    }

    private fun showErrorAndReenableSubscribeView(message: String?) {
        val activity = activity ?: return // We may have pressed "Cancel"
        activity.runOnUiThread {
            subscribeProgress.visibility = View.GONE
            subscribeErrorText.visibility = View.VISIBLE
            subscribeErrorText.text = message
            subscribeErrorTextImage.visibility = View.VISIBLE
            enableSubscribeView(true)
        }
    }

    private fun loginAndMaybeDismiss(baseUrl: String, topic: String) {
        loginProgress.visibility = View.VISIBLE
        loginErrorText.visibility = View.GONE
        loginErrorTextImage.visibility = View.GONE
        enableLoginView(false)
        val user = User(
            baseUrl = baseUrl,
            username = loginUsernameText.text.toString(),
            password = loginPasswordText.text.toString()
        )
        lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Checking read access for user ${user.username} to topic ${topicUrl(baseUrl, topic)}")
            try {
                val authorized = api.checkAuth(baseUrl, topic, user)
                if (authorized) {
                    Log.d(TAG, "Access granted for user ${user.username} to topic ${topicUrl(baseUrl, topic)}, adding to database")
                    repository.addUser(user)
                    dismissDialog()
                } else {
                    Log.w(TAG, "Access not allowed for user ${user.username} to topic ${topicUrl(baseUrl, topic)}")
                    showErrorAndReenableLoginView(getString(R.string.add_dialog_login_error_not_authorized, user.username))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Connection to topic failed during login: ${e.message}", e)
                showErrorAndReenableLoginView(e.message)
            }
        }
    }

    private fun showErrorAndReenableLoginView(message: String?) {
        val activity = activity ?: return // We may have pressed "Cancel"
        activity.runOnUiThread {
            loginProgress.visibility = View.GONE
            loginErrorText.visibility = View.VISIBLE
            loginErrorText.text = message
            loginErrorTextImage.visibility = View.VISIBLE
            enableLoginView(true)
        }
    }

    private fun negativeButtonClick() {
        if (subscribeView.visibility == View.VISIBLE) {
            dialog?.cancel()
        } else if (loginView.visibility == View.VISIBLE) {
            showSubscribeView()
        }
    }

    private fun validateInputSubscribeView(onCompletion: () -> Unit = {}) {
        if (!this::positiveButton.isInitialized) return // As per crash seen in Google Play

        // Show/hide things: This logic is intentionally kept simple. Do not simplify "just because it's pretty".
        //TODO: Phil check this
        val instantToggleAllowed = if (!BuildConfig.FIREBASE_AVAILABLE) {
            false
        } else if (defaultBaseUrl == null) {
            true
        } else {
            false
        }

        if (instantToggleAllowed) {
            subscribeInstantDeliveryBox.visibility = View.VISIBLE
            subscribeInstantDeliveryDescription.visibility = if (subscribeInstantDeliveryCheckbox.isChecked) View.VISIBLE else View.GONE
            subscribeForegroundDescription.visibility = View.GONE
        } else {
            subscribeInstantDeliveryBox.visibility = View.GONE
            subscribeInstantDeliveryDescription.visibility = View.GONE
            subscribeForegroundDescription.visibility = if (BuildConfig.FIREBASE_AVAILABLE) View.VISIBLE else View.GONE
        }

        // Enable/disable "Subscribe" button
        lifecycleScope.launch(Dispatchers.IO) {
            val baseUrl = getBaseUrl()
            val topic = subscribeTopicText.text.toString()
            val subscription = repository.getSubscription(baseUrl, topic)

            activity?.let {
                it.runOnUiThread {
                    if (subscription != null || DISALLOWED_TOPICS.contains(topic)) {
                        positiveButton.isEnabled = false
                    } else if (subscribeUseAnotherServerCheckbox.isChecked) {
                        positiveButton.isEnabled = validTopic(topic) && validUrl(baseUrl)
                    } else {
                        positiveButton.isEnabled = validTopic(topic)
                    }

                    onCompletion()
                }
            }
        }
    }

    private fun validateInputLoginView() {
        if (!this::positiveButton.isInitialized || !this::loginUsernameText.isInitialized || !this::loginPasswordText.isInitialized) {
            return // As per crash seen in Google Play
        }
        if (loginUsernameText.visibility == View.GONE) {
            positiveButton.isEnabled = true
        } else {
            positiveButton.isEnabled = (loginUsernameText.text?.isNotEmpty() ?: false)
                    && (loginPasswordText.text?.isNotEmpty() ?: false)
        }
    }

    private fun dismissDialog() {
        Log.d(TAG, "Closing dialog and calling onSubscribe handler")
        val activity = activity?: return // We may have pressed "Cancel"
        activity.runOnUiThread {
            val topic = subscribeTopicText.text.toString()
            val baseUrl = getBaseUrl()
            val instant = !BuildConfig.FIREBASE_AVAILABLE || baseUrl != appBaseUrl || subscribeInstantDeliveryCheckbox.isChecked
            subscribeListener.onSubscribe(topic, baseUrl, instant)
            dialog?.dismiss()
        }
    }

    private fun getBaseUrl(): String {
        return if (subscribeUseAnotherServerCheckbox.isChecked) {
            subscribeBaseUrlText.text.toString()
        } else {
            return defaultBaseUrl ?: appBaseUrl
        }
    }

    private fun showSubscribeView() {
        resetSubscribeView()
        positiveButton.text = getString(R.string.add_dialog_button_subscribe)
        negativeButton.text = getString(R.string.add_dialog_button_cancel)
        loginView.visibility = View.GONE
        subscribeView.visibility = View.VISIBLE
    }

    private fun showLoginView(activity: Activity) {
        resetLoginView()
        loginProgress.visibility = View.INVISIBLE
        positiveButton.text = getString(R.string.add_dialog_button_login)
        negativeButton.text = getString(R.string.add_dialog_button_back)
        subscribeView.visibility = View.GONE
        loginView.visibility = View.VISIBLE
        if (loginUsernameText.requestFocus()) {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(loginUsernameText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun enableSubscribeView(enable: Boolean) {
        subscribeInstantDeliveryCheckbox.isEnabled = enable
        positiveButton.isEnabled = enable
    }

    private fun resetSubscribeView() {
        subscribeProgress.visibility = View.GONE
        subscribeErrorText.visibility = View.GONE
        subscribeErrorTextImage.visibility = View.GONE
        enableSubscribeView(true)
    }

    private fun enableLoginView(enable: Boolean) {
        loginUsernameText.isEnabled = enable
        loginPasswordText.isEnabled = enable
        positiveButton.isEnabled = enable
        if (enable && loginUsernameText.requestFocus()) {
            val imm = activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(loginUsernameText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun resetLoginView() {
        loginProgress.visibility = View.GONE
        loginErrorText.visibility = View.GONE
        loginErrorTextImage.visibility = View.GONE
        loginUsernameText.visibility = View.VISIBLE
        loginUsernameText.text?.clear()
        loginPasswordText.visibility = View.VISIBLE
        loginPasswordText.text?.clear()
        enableLoginView(true)
    }

    companion object {
        const val TAG = "NtfyAddFragment"
        private val DISALLOWED_TOPICS = listOf("docs", "static", "file") // If updated, also update in server
    }
}