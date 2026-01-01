package com.anonymousassociate.betterpantry.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.anonymousassociate.betterpantry.R
import com.anonymousassociate.betterpantry.models.Associate

sealed class PeopleItem {
    data class Person(val associate: Associate) : PeopleItem()
    object Separator : PeopleItem()
}

class PeopleAdapter(
    private var people: List<Associate>,
    private var favorites: Set<String>,
    private val onPersonClick: (Associate) -> Unit,
    private val onFavoriteClick: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var displayList: List<PeopleItem> = emptyList()
    private var currentQuery: String = ""

    init {
        updateDisplayList()
    }

    fun updateList(newPeople: List<Associate>) {
        people = newPeople
        updateDisplayList()
    }
    
    fun updateFavorites(newFavorites: Set<String>) {
        favorites = newFavorites
        updateDisplayList()
    }

    fun filter(query: String) {
        currentQuery = query
        updateDisplayList()
    }
    
    private fun updateDisplayList() {
        val filtered = if (currentQuery.isEmpty()) {
            people
        } else {
            people.filter {
                val fullName = "${it.firstName} ${it.lastName}".lowercase()
                val preferred = "${it.preferredName} ${it.lastName}".lowercase()
                fullName.contains(currentQuery.lowercase()) || preferred.contains(currentQuery.lowercase())
            }
        }
        
        val starred = filtered.filter { favorites.contains(it.employeeId) }
        val others = filtered.filter { !favorites.contains(it.employeeId) }
        
        val newList = mutableListOf<PeopleItem>()
        if (starred.isNotEmpty()) {
            starred.forEach { newList.add(PeopleItem.Person(it)) }
            if (others.isNotEmpty()) {
                newList.add(PeopleItem.Separator)
            }
        }
        others.forEach { newList.add(PeopleItem.Person(it)) }
        
        displayList = newList
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (displayList[position]) {
            is PeopleItem.Person -> 0
            is PeopleItem.Separator -> 1
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == 0) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_person, parent, false)
            PersonViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_separator, parent, false)
            object : RecyclerView.ViewHolder(view) {}
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is PersonViewHolder) {
            val item = displayList[position] as PeopleItem.Person
            holder.bind(item.associate, favorites.contains(item.associate.employeeId))
        }
    }

    override fun getItemCount(): Int = displayList.size

    inner class PersonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.personName)
        private val starButton: ImageButton = itemView.findViewById(R.id.starButton)

        fun bind(person: Associate, isStarred: Boolean) {
            val displayName = if (!person.preferredName.isNullOrEmpty()) {
                "${person.preferredName} ${person.lastName}"
            } else {
                "${person.firstName} ${person.lastName}"
            }
            nameText.text = displayName
            
            val starRes = if (isStarred) R.drawable.ic_star_filled else R.drawable.ic_star_outline
            starButton.setImageResource(starRes)
            
            starButton.setOnClickListener {
                person.employeeId?.let { id -> onFavoriteClick(id) }
            }
            
            itemView.setOnClickListener { onPersonClick(person) }
        }
    }
}