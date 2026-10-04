package com.watermarkhu.mijn3park

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var loginForm: View
    private lateinit var emailInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var loginButton: MaterialButton
    private lateinit var progress: LinearProgressIndicator

    private lateinit var productPicker: View
    private lateinit var productGroup: RadioGroup
    private lateinit var productContinue: MaterialButton

    private lateinit var statusContainer: View
    private lateinit var statusIcon: View
    private lateinit var statusProgress: CircularProgressIndicator
    private lateinit var statusTitle: TextView
    private lateinit var statusMessage: TextView
    private lateinit var statusRetry: MaterialButton

    private var healthAlertDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        prefs = Prefs(this)

        loginForm = findViewById(R.id.loginForm)
        emailInput = findViewById(R.id.emailInput)
        passwordInput = findViewById(R.id.passwordInput)
        loginButton = findViewById(R.id.loginButton)
        progress = findViewById(R.id.loginProgress)

        productPicker = findViewById(R.id.productPicker)
        productGroup = findViewById(R.id.productGroup)
        productContinue = findViewById(R.id.productContinue)
        productContinue.setOnClickListener { confirmProductSelection() }

        statusContainer = findViewById(R.id.statusContainer)
        statusIcon = findViewById(R.id.statusIcon)
        statusProgress = findViewById(R.id.statusProgress)
        statusTitle = findViewById(R.id.statusTitle)
        statusMessage = findViewById(R.id.statusMessage)
        statusRetry = findViewById(R.id.statusRetry)

        emailInput.setText(prefs.email)

        loginButton.setOnClickListener { login() }
        statusRetry.setOnClickListener { login() }
    }

    private fun login() {
        val email = emailInput.text?.toString()?.trim().orEmpty()
        val password = passwordInput.text?.toString().orEmpty()
        if (email.isBlank() || password.isBlank()) return

        loginButton.isEnabled = false
        progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                TwoParkApi.instance.login(email, password)

                prefs.email = email
                prefs.password = password

                // First run with more than one product: let the user pick a
                // favorite before entering the app. Shown only once.
                val products = try {
                    TwoParkApi.instance.getProducts()
                } catch (_: Exception) {
                    emptyList()
                }
                if (products.size > 1 && !prefs.productPickerShown) {
                    showProductPicker(products)
                } else {
                    goToMain()
                }
            } catch (e: AuthFailedException) {
                showFormError(getString(R.string.login_failed, e.message ?: e.toString()))
            } catch (e: ApiUnavailableException) {
                showFailure(
                    R.string.health_unavailable_title,
                    R.string.health_unavailable_message,
                )
            } catch (e: ApiIncompatibleException) {
                showFailure(
                    R.string.health_unreliable_title,
                    R.string.health_unreliable_message,
                )
            } catch (e: Exception) {
                showFormError(getString(R.string.login_failed, e.message ?: e.toString()))
            } finally {
                loginButton.isEnabled = true
                progress.visibility = View.GONE
            }
        }
    }

    /** First-run: pick a favorite product when the account has several. */
    private fun showProductPicker(products: List<Product>) {
        prefs.productPickerShown = true
        loginForm.isVisible = false
        statusContainer.isVisible = false
        productPicker.isVisible = true

        productGroup.removeAllViews()
        products.forEachIndexed { index, product ->
            val radio = MaterialRadioButton(this).apply {
                id = View.generateViewId()
                text = product.displayName
                tag = product.id
            }
            if (product.id == prefs.defaultProductId ||
                (prefs.defaultProductId.isBlank() && index == 0)
            ) {
                radio.isChecked = true
            }
            productGroup.addView(radio)
        }
    }

    private fun confirmProductSelection() {
        val radio = productGroup.findViewById<MaterialRadioButton>(productGroup.checkedRadioButtonId)
        (radio?.tag as? String)?.takeIf { it.isNotBlank() }?.let {
            prefs.defaultProductId = it
            prefs.productId = it
        }
        goToMain()
    }

    private fun goToMain() {
        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
        finish()
    }

    private fun showFormError(message: String) {
        loginForm.isVisible = true
        statusContainer.isVisible = false
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showFailure(titleRes: Int, messageRes: Int) {
        loginForm.isVisible = false
        statusContainer.isVisible = true
        statusIcon.isVisible = true
        statusProgress.isVisible = false
        statusTitle.setText(titleRes)
        statusMessage.setText(messageRes)
        statusMessage.isVisible = true
        statusRetry.isVisible = true

        // Same warning as the main screen. There is no session yet, so Ok just
        // dismisses; the other button opens mijn.2park.nl.
        if (healthAlertDialog?.isShowing != true) {
            healthAlertDialog = showHealthAlertDialog(this) { }
        }
    }
}
