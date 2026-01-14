package com.example.voice_chatbot_ct

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.voice_chatbot_ct.R

data class ChatMessage(
    val content: String,
    val isUser: Boolean,
    var isStreaming: Boolean = false
)

class ChatAdapter(private val messages: MutableList<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val layoutBot: View = view.findViewById(R.id.layoutBot)
        val layoutUser: View = view.findViewById(R.id.layoutUser)
        val tvBot: TextView = view.findViewById(R.id.tvBotMessage)
        val tvUser: TextView = view.findViewById(R.id.tvUserMessage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val msg = messages[position]
        if (msg.isUser) {
            holder.layoutUser.visibility = View.VISIBLE
            holder.layoutBot.visibility = View.GONE
            holder.tvUser.text = msg.content
        } else {
            holder.layoutBot.visibility = View.VISIBLE
            holder.layoutUser.visibility = View.GONE
            holder.tvBot.text = msg.content
        }
    }

    override fun getItemCount() = messages.size
}