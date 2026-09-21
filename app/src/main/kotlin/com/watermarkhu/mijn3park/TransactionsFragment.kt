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
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch

class TransactionsFragment : Fragment(R.layout.fragment_transactions) {

    private val vm: AppViewModel by activityViewModels()
    private val prefs get() = vm.prefs
    private val api get() = TwoParkApi.instance

    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private lateinit var progress: LinearProgressIndicator
    private val adapter = TransactionAdapter()

    private var nextStart = 1
    private var nextStop = PAGE
    private var loading = false
    private var endReached = false
    private var loadedProductId = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recycler = view.findViewById(R.id.list)
        empty = view.findViewById(R.id.empty)
        progress = view.findViewById(R.id.progress)
        empty.setText(R.string.transactions_empty)

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
        nextStart = 1
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
                val page = api.getMutationHistory(productId, nextStart, nextStop)
                if (productId != loadedProductId) return@launch
                adapter.addAll(page.mutations)
                endReached = page.mutations.isEmpty() || page.stopIndex >= page.maxIndex
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

    private class TransactionAdapter : RecyclerView.Adapter<TransactionAdapter.ViewHolder>() {

        private val items = mutableListOf<Mutation>()

        fun clear() {
            val size = items.size
            items.clear()
            notifyItemRangeRemoved(0, size)
        }

        fun addAll(newItems: List<Mutation>) {
            if (newItems.isEmpty()) return
            val start = items.size
            items.addAll(newItems)
            notifyItemRangeInserted(start, newItems.size)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(
                android.view.LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_transaction, parent, false)
            )

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val type: TextView = view.findViewById(R.id.type)
            private val plate: TextView = view.findViewById(R.id.plate)
            private val amount: TextView = view.findViewById(R.id.amount)
            private val date: TextView = view.findViewById(R.id.date)

            fun bind(mutation: Mutation) {
                val context = itemView.context
                type.text = mutation.type
                val color = MaterialColors.getColor(
                    itemView,
                    if (mutation.isDebit) androidx.appcompat.R.attr.colorError
                    else androidx.appcompat.R.attr.colorPrimary,
                )
                type.setTextColor(color)
                amount.setTextColor(color)
                amount.text = formatAmount(mutation.amount, mutation.unit)
                date.text = prettyTime(mutation.date)
                plate.isVisible = mutation.plate.isNotBlank()
                if (mutation.plate.isNotBlank()) {
                    plate.text = context.getString(R.string.transaction_plate, mutation.plate)
                }
            }
        }
    }

    private companion object {
        const val PAGE = 10
    }
}
