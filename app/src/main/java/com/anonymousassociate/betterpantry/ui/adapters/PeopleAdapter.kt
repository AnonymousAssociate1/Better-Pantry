package com.anonymousassociate.betterpantry.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.anonymousassociate.betterpantry.R
import com.anonymousassociate.betterpantry.models.Associate
import com.anonymousassociate.betterpantry.models.CafeInfo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob

sealed class PeopleItem {
    data class Header(val cafeNumber: String, val displayName: String, val isExpanded: Boolean) : PeopleItem()
    data class Person(val associate: Associate, val cafeNumber: String) : PeopleItem()
    object ResetButton : PeopleItem()
}

class PeopleAdapter(
    private var people: List<Associate>,
    private var favorites: Set<String>,
    private var associateCafes: Map<String, Set<String>>,
    private var cafeList: List<CafeInfo>?,
    private val settingsPreferences: com.anonymousassociate.betterpantry.SettingsPreferences,
    private var userEnabledCafes: Set<String>,
    private val onPersonClick: (Associate) -> Unit,
    private val onFavoriteClick: (String) -> Unit,
    private val onPersonLongClick: (Associate) -> Unit,
    private val onResetClick: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var displayList: List<PeopleItem> = emptyList()
    private var currentQuery: String = ""
    private val collapsedCafes = mutableSetOf<String>()
    
    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var updateJob: Job? = null

    init {
        updateDisplayList()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        updateJob?.cancel()
    }

    fun updateList(newPeople: List<Associate>) {
        people = newPeople
        updateDisplayList()
    }

    fun updateData(
        newPeople: List<Associate>,
        newAssociateCafes: Map<String, Set<String>>,
        newCafeList: List<CafeInfo>?,
        newUserEnabledCafes: Set<String>
    ) {
        people = newPeople
        associateCafes = newAssociateCafes
        cafeList = newCafeList
        userEnabledCafes = newUserEnabledCafes
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
        updateJob?.cancel()
        updateJob = adapterScope.launch {
            val query = currentQuery
            val localPeople = people
            val localFavorites = favorites
            val localAssociateCafes = associateCafes
            val localCafeList = cafeList
            val localUserEnabledCafes = userEnabledCafes
            val localCollapsed = collapsedCafes.toSet()

            val newList = withContext(Dispatchers.Default) {
                val filtered = if (query.isEmpty()) {
                    localPeople
                } else {
                    localPeople.filter {
                        val resolvedName = settingsPreferences.getCoworkerDisplayName(it.employeeId, it.firstName, it.lastName, it.preferredName).lowercase()
                        resolvedName.contains(query.lowercase())
                    }
                }
                
                val resultList = mutableListOf<PeopleItem>()

                // Add Favorites section at the top
                val favoritesList = filtered.filter { it.employeeId != null && localFavorites.contains(it.employeeId) }
                if (favoritesList.isNotEmpty()) {
                    val isFavoritesExpanded = !localCollapsed.contains("favorites")
                    resultList.add(PeopleItem.Header("favorites", "Favorites", isFavoritesExpanded))
                    if (isFavoritesExpanded) {
                        val sortedFavs = favoritesList.sortedWith(compareBy { settingsPreferences.getCoworkerDisplayName(it.employeeId, it.firstName, it.lastName, it.preferredName).lowercase() })
                        sortedFavs.forEach { resultList.add(PeopleItem.Person(it, "favorites")) }
                    }
                }
                
                // Find all enabled cafes that the logged-in user is assigned to AND have at least one person
                val activeCafes = localUserEnabledCafes.filter { cafeNo ->
                    localAssociateCafes.values.any { cafeNo in it }
                }.sorted()

                if (activeCafes.isEmpty() && filtered.isNotEmpty()) {
                    // Fallback if no cafe numbers are found but we have people
                    val headerName = "All Coworkers"
                    resultList.add(PeopleItem.Header("all", headerName, !localCollapsed.contains("all")))
                    if (!localCollapsed.contains("all")) {
                        val sortedPeople = filtered.sortedWith(compareBy { settingsPreferences.getCoworkerDisplayName(it.employeeId, it.firstName, it.lastName, it.preferredName).lowercase() })
                        sortedPeople.forEach { resultList.add(PeopleItem.Person(it, "all")) }
                    }
                } else {
                    activeCafes.forEach { cafeNo ->
                        val displayName = settingsPreferences.getCafeDisplayName(cafeNo, localCafeList)
                        val isExpanded = !localCollapsed.contains(cafeNo)
                        
                        // Get people in this cafe
                        val peopleInCafe = filtered.filter { associate ->
                            val cafesForAssoc = localAssociateCafes[associate.employeeId] ?: emptySet()
                            cafeNo in cafesForAssoc
                        }

                        if (peopleInCafe.isNotEmpty()) {
                            resultList.add(PeopleItem.Header(cafeNo, displayName, isExpanded))
                            if (isExpanded) {
                                val sortedPeople = peopleInCafe.sortedWith(compareBy { settingsPreferences.getCoworkerDisplayName(it.employeeId, it.firstName, it.lastName, it.preferredName).lowercase() })
                                sortedPeople.forEach { resultList.add(PeopleItem.Person(it, cafeNo)) }
                            }
                        }
                    }
                }
                
                resultList.add(PeopleItem.ResetButton)
                resultList
            }

            displayList = newList
            notifyDataSetChanged()
        }
    }


    override fun getItemViewType(position: Int): Int {
        return when (displayList[position]) {
            is PeopleItem.Person -> 0
            is PeopleItem.Header -> 1
            is PeopleItem.ResetButton -> 2
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            0 -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_person, parent, false)
                PersonViewHolder(view)
            }
            1 -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_cafe_header, parent, false)
                HeaderViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_people_reset_button, parent, false)
                ResetButtonViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is PersonViewHolder -> {
                val item = displayList[position] as PeopleItem.Person
                holder.bind(item.associate, favorites.contains(item.associate.employeeId))
            }
            is HeaderViewHolder -> {
                val item = displayList[position] as PeopleItem.Header
                holder.bind(item)
            }
            is ResetButtonViewHolder -> {
                // No dynamic binding needed
            }
        }
    }

    override fun getItemCount(): Int = displayList.size

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cafeNameText: TextView = itemView.findViewById(R.id.cafeNameText)
        private val carrotIcon: ImageView = itemView.findViewById(R.id.carrotIcon)

        fun bind(header: PeopleItem.Header) {
            cafeNameText.text = header.displayName
            val iconRes = if (header.isExpanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right
            carrotIcon.setImageResource(iconRes)
            
            itemView.setOnClickListener {
                if (collapsedCafes.contains(header.cafeNumber)) {
                    collapsedCafes.remove(header.cafeNumber)
                } else {
                    collapsedCafes.add(header.cafeNumber)
                }
                updateDisplayList()
            }
        }
    }

    inner class PersonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.personName)
        private val starButton: ImageButton = itemView.findViewById(R.id.starButton)
        private val editNicknameButton: ImageButton = itemView.findViewById(R.id.editNicknameButton)

        fun bind(person: Associate, isStarred: Boolean) {
            val displayName = settingsPreferences.getCoworkerDisplayName(person.employeeId, person.firstName, person.lastName, person.preferredName)
            nameText.text = displayName
            
            val starRes = if (isStarred) R.drawable.ic_star_filled else R.drawable.ic_star_outline
            starButton.setImageResource(starRes)
            
            starButton.setOnClickListener {
                person.employeeId?.let { id -> onFavoriteClick(id) }
            }
            
            editNicknameButton.setOnClickListener {
                onPersonLongClick(person)
            }
            
            itemView.setOnClickListener { onPersonClick(person) }
        }
    }

    inner class ResetButtonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        init {
            itemView.findViewById<View>(R.id.resetButton).setOnClickListener {
                onResetClick()
            }
        }
    }
}