package com.watermarkhu.mijn3park

import android.os.Bundle
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
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class HistoryFragment : Fragment(R.layout.fragment_history) {

    private val vm: AppViewModel by activityViewModels()
    private val prefs get() = vm.prefs
    private val api get() = TwoParkApi.instance

    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private lateinit var progress: LinearProgressIndicator
    private val adapter = HistoryAdapter()

    private var nextStart = 0
    private var nextStop = PAGE
    private var loading = false
    private var endReached = false
    private var loadedProductId = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recycler = view.findViewById(R.id.list)
        empty = view.findViewById(R.id.empty)
        progress = view.findViewById(R.id.progress)
        empty.setText(R.string.history_empty)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!rv.canScrollVertically(1)) loadNextPage()
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    val productId = state.selectedProduct?.id ?: prefs.productId
                    if (productId != loadedProductId) {
                        loadedProductId = productId
                        reset()
                        if (productId.isNotBlank()) loadNextPage()
                    }
                }
            }
        }
    }

    private fun reset() {
        adapter.clear()
        nextStart = 0
        nextStop = PAGE
        loading = false
        endReached = false
        renderEmpty()
    }

    private fun loadNextPage() {
        val productId = prefs.productId
        if (loading || endReached || productId.isBlank() || productId != loadedProductId) return
        loading = true
        progress.isVisible = adapter.itemCount == 0
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val page = api.getActionHistory(productId, nextStart, nextStop)
                if (productId != loadedProductId) return@launch
                adapter.addAll(page.actions)
                endReached = page.actions.isEmpty() || page.stopIndex >= page.maxIndex
                nextStart = page.stopIndex + 1
                nextStop = page.stopIndex + PAGE
                renderEmpty()
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
                loading = false
                progress.isVisible = false
                // Content may not fill the screen yet: keep loading until it
                // does. Defer until after layout so canScrollVertically is real.
                recycler.post { maybeFillScreen() }
            }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) recycler.post { maybeFillScreen() }
    }

    private fun maybeFillScreen() {
        if (isVisible && !endReached && !recycler.canScrollVertically(1)) loadNextPage()
    }

    private fun renderEmpty() {
        empty.isVisible = adapter.itemCount == 0 && endReached
    }

    private class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        private val items = mutableListOf<ParkingAction>()

        fun clear() {
            val size = items.size
            items.clear()
            notifyItemRangeRemoved(0, size)
        }

        fun addAll(newItems: List<ParkingAction>) {
            if (newItems.isEmpty()) return
            val start = items.size
            items.addAll(newItems)
            notifyItemRangeInserted(start, newItems.size)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(
                android.view.LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_history, parent, false)
            )

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val plate: TextView = view.findViewById(R.id.plate)
            private val period: TextView = view.findViewById(R.id.period)
            private val location: TextView = view.findViewById(R.id.location)
            private val cost: TextView = view.findViewById(R.id.cost)

            fun bind(action: ParkingAction) {
                val context = itemView.context
                plate.text = action.plate
                period.text = context.getString(
                    R.string.history_period,
                    prettyTime(action.timeStart),
                    prettyTime(action.timeEnd),
                )
                location.isVisible = !action.location.isNullOrBlank()
                if (!action.location.isNullOrBlank()) {
                    location.text = context.getString(R.string.history_location, action.location)
                }
                val formattedCost = formatAmount(action.cost, action.costUnit)
                cost.isVisible = formattedCost != null
                if (formattedCost != null) {
                    cost.text = context.getString(R.string.history_cost, formattedCost)
                }
            }
        }
    }

    private companion object {
        const val PAGE = 10
    }
}
