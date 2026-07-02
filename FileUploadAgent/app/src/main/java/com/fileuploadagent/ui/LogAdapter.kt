package com.fileuploadagent.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fileuploadagent.R
import com.fileuploadagent.databinding.ItemLogBinding
import com.fileuploadagent.logging.LogEntry
import com.fileuploadagent.logging.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter : ListAdapter<LogEntry, LogAdapter.ViewHolder>(DIFF_CALLBACK) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    inner class ViewHolder(private val binding: ItemLogBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: LogEntry) {
            binding.textLogMessage.text = entry.message
            binding.textLogTimestamp.text = dateFormat.format(Date(entry.timestampMillis))
            val colorRes = when (entry.level) {
                LogLevel.ERROR -> R.color.log_error
                LogLevel.SUCCESS -> R.color.log_success
                LogLevel.INFO -> R.color.black
            }
            binding.textLogMessage.setTextColor(
                ContextCompat.getColor(binding.root.context, colorRes)
            )
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<LogEntry>() {
            override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry) =
                oldItem.timestampMillis == newItem.timestampMillis && oldItem.message == newItem.message

            override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry) =
                oldItem == newItem
        }
    }
}
