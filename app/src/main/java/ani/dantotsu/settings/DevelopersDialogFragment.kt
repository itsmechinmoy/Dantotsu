package ani.dantotsu.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.connections.github.Contributors
import ani.dantotsu.databinding.BottomSheetDevelopersBinding
import kotlinx.coroutines.launch

class DevelopersDialogFragment : BottomSheetDialogFragment() {
    private var _binding: BottomSheetDevelopersBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDevelopersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.devsProgressBar.visibility = View.VISIBLE
        binding.devsRecyclerView.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val contributors = Contributors().getContributors()
                binding.devsRecyclerView.adapter = DevelopersAdapter(contributors)
                binding.devsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
                binding.devsRecyclerView.visibility = View.VISIBLE
                binding.devsProgressBar.visibility = View.GONE
            } catch (e: Exception) {
                e.printStackTrace()
                binding.devsProgressBar.visibility = View.GONE
            }
        }
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }
}
