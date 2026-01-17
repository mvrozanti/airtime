package com.nosmoke.timer.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.nosmoke.timer.data.StateManager
import com.nosmoke.timer.fragments.AnalyticsFragment
import com.nosmoke.timer.fragments.ConfigFragment
import com.nosmoke.timer.fragments.PlacesFragment

class MainPagerAdapter(
    fragmentActivity: FragmentActivity,
    private val stateManager: StateManager
) : FragmentStateAdapter(fragmentActivity) {
    
    override fun getItemCount(): Int = 3
    
    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> {
                val fragment = ConfigFragment.newInstance(stateManager)
                fragment.setStateManager(stateManager)
                fragment
            }
            1 -> {
                val fragment = PlacesFragment()
                fragment.setStateManager(stateManager)
                fragment
            }
            2 -> AnalyticsFragment()
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }
}

