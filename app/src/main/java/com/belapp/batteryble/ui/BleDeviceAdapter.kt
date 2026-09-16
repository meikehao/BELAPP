package com.belapp.batteryble.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.belapp.batteryble.ble.BleManager
import com.belapp.batteryble.databinding.ItemBleDeviceBinding

class BleDeviceAdapter(
    private val onDeviceClick: (BleManager.BleDevice) -> Unit
) : RecyclerView.Adapter<BleDeviceAdapter.VH>() {

    private val items = mutableListOf<BleManager.BleDevice>()

    @SuppressLint("NotifyDataSetChanged")
    fun update(list: List<BleManager.BleDevice>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBleDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemBleDeviceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(dev: BleManager.BleDevice) {
            val displayName = dev.name?.takeIf { it.isNotBlank() } ?: "未知设备"
            binding.tvDeviceName.text = displayName
            binding.tvDeviceAddress.text = dev.address
            binding.tvRssi.text = "${dev.rssi} dBm"
            itemView.setOnClickListener { onDeviceClick(dev) }
        }
    }
}
