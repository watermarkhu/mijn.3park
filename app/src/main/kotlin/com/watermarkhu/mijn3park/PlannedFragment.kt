package com.watermarkhu.mijn3park

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch

/**
 * "Gepland" tab: list and cancel future planned parking sessions for the
 * selected product. Create/edit open the full-screen [PlanEditFragment].
 * Sessions that cross midnight are shown as one row but stored as consecutive
 * same-day legs (see [Planning]).
 */
class PlannedFragment : Fragment(R.layout.fragment_planned) {

    private val vm: AppViewModel by activityViewModels()
    private val prefs get() = vm.prefs
    private val api get() = TwoParkApi.instance

    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private lateinit var permitNotice: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var planFab: ExtendedFloatingActionButton
    private val adapter = PlannedAdapter()

    private val isPermitProduct: Boolean
        get() {
            val state = vm.state.value
            return state.selectedProduct?.hasFixedPlate == true || state.details?.fixedPlate != null
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recycler = view.findViewById(R.id.list)
        empty = view.findViewById(R.id.empty)
        permitNotice = view.findViewById(R.id.permitNotice)
        progress = view.findViewById(R.id.progress)
        planFab = view.findViewById(R.id.planFab)
        empty.setText(R.string.planned_empty)
        permitNotice.setText(R.string.planned_permit_unavailable)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        adapter.onEdit = { openEditor(it) }
        adapter.onCancel = { confirmCancel(it) }

        planFab.setOnClickListener { openEditor(null) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { load() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun openEditor(group: List<PlannedAction>?) {
        val host = activity as? MainActivity ?: return
        if (group == null) {
            host.openCreatePlan()
        } else {
            host.openEditPlan(
                plate = group.first().plate,
                startAt = Planning.parseTimestamp(group.first().timeStart),
                endAt = Planning.parseTimestamp(group.last().timeEnd),
                legIds = group.map { it.id },
            )
        }
    }

    private fun load() {
        val permit = isPermitProduct
        permitNotice.isVisible = permit
        planFab.isVisible = !permit
        if (permit) {
            recycler.isVisible = false
            empty.isVisible = false
            progress.isVisible = false
            return
        }
        recycler.isVisible = true
        val productId = prefs.productId
        if (productId.isBlank()) return
        progress.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val planned = api.getPlanned(productId)
                if (productId != prefs.productId) return@launch
                val groups = Planning.mergeGroups(planned)
                adapter.submit(groups)
                empty.isVisible = groups.isEmpty()
            } catch (_: AuthFailedException) {
                vm.reportSessionExpired()
            } catch (_: SessionExpiredException) {
                vm.reportSessionExpired()
            } catch (e: ApiUnavailableException) {
                vm.reportApiFailure(e)
            } catch (e: ApiIncompatibleException) {
                vm.reportApiFailure(e)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
            } finally {
                progress.isVisible = false
            }
        }
    }

    private fun confirmCancel(group: List<PlannedAction>) {
        val plate = group.first().plate
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.planned_confirm_cancel, plate))
            .setPositiveButton(R.string.planned_cancel_yes) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        group.forEach { api.cancelPlanned(prefs.productId, it.id) }
                        Toast.makeText(requireContext(), R.string.planned_removed, Toast.LENGTH_SHORT).show()
                        load()
                    } catch (_: AuthFailedException) {
                        vm.reportSessionExpired()
                    } catch (_: SessionExpiredException) {
                        vm.reportSessionExpired()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private class PlannedAdapter : RecyclerView.Adapter<PlannedAdapter.ViewHolder>() {

        private val items = mutableListOf<List<PlannedAction>>()
        var onEdit: ((List<PlannedAction>) -> Unit)? = null
        var onCancel: ((List<PlannedAction>) -> Unit)? = null

        fun submit(newItems: List<List<PlannedAction>>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_planned, parent, false)
            )

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val plate: TextView = view.findViewById(R.id.plate)
            private val period: TextView = view.findViewById(R.id.period)
            private val editButton: MaterialButton = view.findViewById(R.id.editButton)
            private val cancelButton: MaterialButton = view.findViewById(R.id.cancelButton)

            fun bind(group: List<PlannedAction>) {
                val context = itemView.context
                val first = group.first()
                val last = group.last()
                plate.text = first.plate
                period.text = context.getString(
                    R.string.history_period,
                    prettyTime(first.timeStart),
                    prettyTime(last.timeEnd),
                )
                editButton.setOnClickListener { onEdit?.invoke(group) }
                cancelButton.setOnClickListener { onCancel?.invoke(group) }
            }
        }
    }
}
