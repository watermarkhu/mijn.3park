package com.watermarkhu.mijn3park

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var loginForm: View
    private lateinit var emailInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var loginButton: MaterialButton
    private lateinit var progress: LinearProgressIndicator

    private lateinit var statusContainer: View
    private lateinit var statusIcon: View
    private lateinit var statusProgress: CircularProgressIndicator
    private lateinit var statusTitle: TextView
    private lateinit var statusMessage: TextView
    private lateinit var statusRetry: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        prefs = Prefs(this)

        loginForm = findViewById(R.id.loginForm)
        emailInput = findViewById(R.id.emailInput)
        passwordInput = findViewById(R.id.passwordInput)
        loginButton = findViewById(R.id.loginButton)
        progress = findViewById(R.id.loginProgress)

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

                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                finish()
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
    }
}
