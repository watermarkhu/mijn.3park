package dev.watermarkhu.mijn3park

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlinx.coroutines.launch

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private val vm: AppViewModel by activityViewModels()
    private val prefs get() = vm.prefs

    private lateinit var accountEmail: TextView
    private lateinit var productDropdown: MaterialAutoCompleteTextView
    private lateinit var defaultProductStar: MaterialButton
    private lateinit var themeSystem: MaterialRadioButton
    private lateinit var themeLight: MaterialRadioButton
    private lateinit var themeDark: MaterialRadioButton

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        accountEmail = view.findViewById(R.id.accountEmail)
        productDropdown = view.findViewById(R.id.productDropdown)
        defaultProductStar = view.findViewById(R.id.defaultProductStar)
        themeSystem = view.findViewById(R.id.themeSystem)
        themeLight = view.findViewById(R.id.themeLight)
        themeDark = view.findViewById(R.id.themeDark)

        accountEmail.text = prefs.email

        productDropdown.setOnItemClickListener { _, _, position, _ ->
            vm.state.value.products.getOrNull(position)?.let { vm.selectProduct(it) }
        }
        defaultProductStar.setOnClickListener {
            vm.setDefaultProduct(defaultProductStar.isChecked)
            if (defaultProductStar.isChecked) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.default_product_set, prefs.productName),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        setUpTheme()

        view.findViewById<MaterialButton>(R.id.logoutButton).setOnClickListener {
            (activity as? MainActivity)?.performLogout()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    productDropdown.setAdapter(
                        ArrayAdapter(
                            requireContext(),
                            android.R.layout.simple_list_item_1,
                            state.products.map { it.displayName },
                        ),
                    )
                    productDropdown.setText(state.selectedProduct?.displayName ?: prefs.productName, false)
                    defaultProductStar.isVisible = state.products.size > 1
                    defaultProductStar.isChecked =
                        prefs.productId.isNotBlank() && prefs.productId == prefs.defaultProductId
                }
            }
        }
    }

    private fun setUpTheme() {
        val themePrefs = ThemePrefs(requireContext())
        when (themePrefs.theme) {
            ThemePrefs.THEME_LIGHT -> themeLight.isChecked = true
            ThemePrefs.THEME_DARK -> themeDark.isChecked = true
            else -> themeSystem.isChecked = true
        }
        val listener = { theme: String ->
            if (themePrefs.theme != theme) {
                themePrefs.theme = theme
                AppCompatDelegate.setDefaultNightMode(ThemePrefs.nightMode(theme))
            }
        }
        themeSystem.setOnClickListener { listener(ThemePrefs.THEME_SYSTEM) }
        themeLight.setOnClickListener { listener(ThemePrefs.THEME_LIGHT) }
        themeDark.setOnClickListener { listener(ThemePrefs.THEME_DARK) }
    }
}
