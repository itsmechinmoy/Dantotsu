package ani.dantotsu.parsers

import android.graphics.drawable.Drawable
import android.view.View
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemExtensionSelectBinding
import com.bumptech.glide.Glide
import com.xwray.groupie.viewbinding.BindableItem

class ExtensionSelectItem(
    private val name: String,
    private val image: Drawable?,
    private val iconUrl: String?,
    private var isSelected: Boolean,
    val selectCallback: (String, Boolean) -> Unit
) : BindableItem<ItemExtensionSelectBinding>() {
    private lateinit var binding: ItemExtensionSelectBinding

    override fun bind(viewBinding: ItemExtensionSelectBinding, position: Int) {
        binding = viewBinding
        binding.extensionNameTextView.text = name

        Glide.with(binding.root.context).clear(binding.extensionIconImageView)
        binding.extensionIconImageView.setImageDrawable(null)

        if (image != null) {
            binding.extensionIconImageView.setImageDrawable(image)
        } else if (iconUrl != null) {
            Glide.with(binding.root.context)
                .load(iconUrl)
                .into(binding.extensionIconImageView)
        }
        binding.extensionCheckBox.setOnCheckedChangeListener(null)
        binding.extensionCheckBox.isChecked = isSelected
        binding.extensionCheckBox.setOnCheckedChangeListener { _, isChecked ->
            isSelected = isChecked
            selectCallback(name, isChecked)
        }
    }

    override fun getLayout(): Int {
        return R.layout.item_extension_select
    }

    override fun initializeViewBinding(view: View): ItemExtensionSelectBinding {
        return ItemExtensionSelectBinding.bind(view)
    }
}