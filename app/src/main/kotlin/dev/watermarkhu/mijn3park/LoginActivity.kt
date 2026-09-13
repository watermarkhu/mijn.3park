package dev.watermarkhu.mijn3park

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        prefs = Prefs(this)

        val emailInput = findViewById<TextInputEditText>(R.id.emailInput)
        val passwordInput = findViewById<TextInputEditText>(R.id.passwordInput)
        val loginButton = findViewById<MaterialButton>(R.id.loginButton)
        val progress = findViewById<LinearProgressIndicator>(R.id.loginProgress)

        emailInput.setText(prefs.email)

        loginButton.setOnClickListener {
            val email = emailInput.text?.toString()?.trim().orEmpty()
            val password = passwordInput.text?.toString().orEmpty()
            if (email.isBlank() || password.isBlank()) return@setOnClickListener

            loginButton.isEnabled = false
            progress.visibility = View.VISIBLE

            lifecycleScope.launch {
                try {
                    val api = TwoParkApi.instance
                    api.login(email, password)

                    prefs.email = email
                    prefs.password = password

                    // Preselect the first product if none chosen yet.
                    if (prefs.productId.isBlank()) {
                        val products = api.getProducts()
                        val first = products.first()
                        prefs.productId = first.id
                        prefs.productName = first.displayName
                        prefs.productLocation = first.location.orEmpty()
                        prefs.productCategoryId = first.categoryId
                    }

                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } catch (e: Exception) {
                    Toast.makeText(
                        this@LoginActivity,
                        getString(R.string.login_failed, e.message ?: e.toString()),
                        Toast.LENGTH_LONG,
                    ).show()
                } finally {
                    loginButton.isEnabled = true
                    progress.visibility = View.GONE
                }
            }
        }
    }
}
