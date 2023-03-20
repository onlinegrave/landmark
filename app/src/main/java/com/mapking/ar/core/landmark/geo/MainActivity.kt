package com.mapking.ar.core.landmark.geo

import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.animation.Animation
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.snackbar.Snackbar
import com.mapking.ar.core.landmark.geo.common.helpers.FullScreenHelper
import com.mapking.ar.core.landmark.geo.databinding.ActivityMainBinding
import android.view.animation.AnimationUtils


class MainActivity : BaseActivity() {
    companion object {
        private const val TAG = "HelloGeoActivity"

        /** Milliseconds used for UI animations */
        const val ANIMATION_FAST_MILLIS = 50L
        const val ANIMATION_SLOW_MILLIS = 100L
        private const val IMMERSIVE_FLAG_TIMEOUT = 500L
    }

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    private lateinit var fabOpenAnim: Animation
    private lateinit var fabCloseAnim: Animation
    private lateinit var fabRotateForwardAnim: Animation
    private lateinit var fabRotateBackwardAnim: Animation

    private var isOpen = false

    public fun toggleDrawer() {
        if (!binding.drawerLayout.isDrawerOpen(Gravity.START)) binding.drawerLayout.openDrawer(
            Gravity.START
        );
        else binding.drawerLayout.closeDrawer(Gravity.END);
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBarMain.toolbar)
        binding.appBarMain.fab.setOnClickListener { view ->
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                .setAction("Action", null).show()
        }

        fabOpenAnim = AnimationUtils.loadAnimation(this, R.anim.fab_open)
        fabCloseAnim = AnimationUtils.loadAnimation(this, R.anim.fab_close)
        fabRotateForwardAnim = AnimationUtils.loadAnimation(this, R.anim.rotate_forward)
        fabRotateBackwardAnim = AnimationUtils.loadAnimation(this, R.anim.rotate_backward)

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_content_main)

        //Passing each menu ID as a set of Ids because each
        //menu should be considered as top level destinations.

        appBarConfiguration =
            AppBarConfiguration(setOf(R.id.main_fragment, R.id.setting_fragment), drawerLayout)

        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is preset
        menuInflater.inflate(R.menu.main, menu)
        return true;
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }


    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
}
