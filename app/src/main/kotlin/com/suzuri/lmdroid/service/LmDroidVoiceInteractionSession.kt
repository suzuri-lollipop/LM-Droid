package com.suzuri.lmdroid.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.View
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.suzuri.lmdroid.LmDroidApplication
import com.suzuri.lmdroid.MainActivity
import com.suzuri.lmdroid.ui.ViewModelFactory
import com.suzuri.lmdroid.ui.assist.AssistScreen
import com.suzuri.lmdroid.ui.assist.AssistViewModel
import com.suzuri.lmdroid.ui.theme.LmDroidTheme

/**
 * The actual session that handles the assistant UI overlay.
 * Implements standard Jetpack interfaces to host Compose and ViewModels correctly.
 */
class LmDroidVoiceInteractionSession(context: Context) : VoiceInteractionSession(context),
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner, ActivityResultRegistryOwner {

    private lateinit var viewModel: AssistViewModel

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val vModelStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = vModelStore
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override val activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I : Any?, O : Any?> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            // Not supported in session overlay, but prevents crash when permission is requested
        }
    }

    override fun onCreate() {
        Log.d("LmDroidSession", "onCreate")
        // Must be called before super.onCreate() or right after to ensure SavedState is ready
        savedStateRegistryController.performRestore(null)
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateContentView(): View {
        Log.d("LmDroidSession", "onCreateContentView")
        val container = (context.applicationContext as LmDroidApplication).container
        val viewModelFactory = ViewModelFactory(container)

        return ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@LmDroidVoiceInteractionSession)
            setViewTreeViewModelStoreOwner(this@LmDroidVoiceInteractionSession)
            setViewTreeSavedStateRegistryOwner(this@LmDroidVoiceInteractionSession)

            viewModel = ViewModelProvider(this@LmDroidVoiceInteractionSession, viewModelFactory)[AssistViewModel::class.java]

            setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides this@LmDroidVoiceInteractionSession) {
                    LmDroidTheme {
                        AssistScreen(
                            viewModel = viewModel,
                            onOpenApp = {
                                val intent = Intent(context, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                                finish()
                            },
                            onDismiss = { finish() }
                        )
                    }
                }
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        Log.d("LmDroidSession", "onShow: args=$args, flags=$showFlags")
        super.onShow(args, showFlags)
        context.sendBroadcast(Intent(WakeWordService.ACTION_PAUSE))
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        
        if (::viewModel.isInitialized) {
            viewModel.onRetry()
        }
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        Log.d("LmDroidSession", "onHandleAssist")
    }

    override fun onHide() {
        super.onHide()
        context.sendBroadcast(Intent(WakeWordService.ACTION_RESUME))
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onDestroy() {
        super.onDestroy()
        context.sendBroadcast(Intent(WakeWordService.ACTION_RESUME))
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        vModelStore.clear()
    }
}
