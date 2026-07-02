package com.fileuploadagent.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fileuploadagent.databinding.ItemFolderBinding
import com.fileuploadagent.settings.WatchedFolder

class FolderAdapter(
    private val onRemove: (WatchedFolder) -> Unit
) : ListAdapter<WatchedFolder, FolderAdapter.ViewHolder>(DIFF_CALLBACK) {

    inner class ViewHolder(private val binding: ItemFolderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(folder: WatchedFolder) {
            binding.textFolderPath.text = folder.displayPath
            binding.buttonRemoveFolder.setOnClickListener { onRemove(folder) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<WatchedFolder>() {
            override fun areItemsTheSame(oldItem: WatchedFolder, newItem: WatchedFolder) =
                oldItem.treeUri == newItem.treeUri

            override fun areContentsTheSame(oldItem: WatchedFolder, newItem: WatchedFolder) =
                oldItem == newItem
        }
    }
}
