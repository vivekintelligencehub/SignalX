package com.signalX

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

data class RecentImage(
    val uri: android.net.Uri
)

class RecentImagesAdapter(
    private val items: MutableList<RecentImage>,
    private val onClick: (RecentImage) -> Unit
) : RecyclerView.Adapter<RecentImagesAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {

        val img: ImageView =
            v.findViewById(R.id.imgRecent)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): VH {

        val view =
            LayoutInflater.from(parent.context)
                .inflate(
                    R.layout.item_recent_image,
                    parent,
                    false
                )

        return VH(view)
    }

    override fun onBindViewHolder(
        holder: VH,
        position: Int
    ) {

        val item = items[position]

        Glide.with(holder.img.context)
            .load(item.uri)
            .centerCrop()
            .into(holder.img)

        holder.itemView.setOnClickListener {
            onClick(item)
        }
    }

    override fun getItemCount(): Int =
        items.size

    fun update(
        newList: List<RecentImage>
    ) {

        items.clear()

        items.addAll(newList)

        notifyDataSetChanged()
    }
}