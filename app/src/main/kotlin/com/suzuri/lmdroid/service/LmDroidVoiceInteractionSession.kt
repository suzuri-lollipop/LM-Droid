package com.suzuri.lmdroid.service

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import com.suzuri.lmdroid.LmDroidApplication
import com.suzuri.lmdroid.MainActivity
import com.suzuri.lmdroid.ui.ViewModelFactory
import com.suzuri.lmdroid.ui.assist.AssistScreen
import com.suzuri.lmdroid.ui.assist.AssistViewModel
import com.suzuri.lmdroid.ui.theme.LmDroidTheme

/**
 * The actual session that handles the assistant UI overlay.
 */
class LmDroidVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private lateinit var viewModel: AssistViewModel
    
    private val lifecycleOwner = object : LifecycleOwner {
        override val lifecycle: Lifecycle get() = lifecycleRegistry
    }
    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(lifecycleOwner)

    private val viewModelStoreOwner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    private val savedStateRegistryOwner = object : SavedStateRegistryOwner {
        private val controller = SavedStateRegistryController.create(this)
        override val savedStateRegistry get() = controller.savedStateRegistry
        override val lifecycle get() = lifecycleRegistry
        fun performRestore(savedState: Bundle?) = controller.performRestore(savedState)
    }

    private val activityResultRegistryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I : Any?, O : Any?> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?
            ) {
                // Not supported in session overlay, but prevents crash
            }
        }
    }

    override fun onCreate() {
        savedStateRegistryOwner.performRestore(null)
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateContentView(): View {
        val container = (context.applicationContext as LmDroidApplication).container
        val viewModelFactory = ViewModelFactory(container)

        return ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@LmDroidVoiceInteractionSession.lifecycleOwner)
            setViewTreeViewModelStoreOwner(this@LmDroidVoiceInteractionSession.viewModelStoreOwner)
            setViewTreeSavedStateRegistryOwner(this@LmDroidVoiceInteractionSession.savedStateRegistryOwner)

            viewModel = ViewModelProvider(this@LmDroidVoiceInteractionSession.viewModelStoreOwner, viewModelFactory)[AssistViewModel::class.java]

            setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides this@LmDroidVoiceInteractionSession.activityResultRegistryOwner) {
                    LmDroidTheme {
                        AssistScreen(
                            viewModel = viewModel,
                            onOpenApp = {
                                val intent = android.content.Intent(context, MainActivity::class.java).apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
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
        super.onShow(args, showFlags)
        context.sendBroadcast(android.content.Intent(WakeWordService.ACTION_PAUSE))
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        
        // Ensure the assistant starts listening or resets its state when shown.
        if (::viewModel.isInitialized) {
            viewModel.onRetry()
        }
    }

    override fun onHide() {
        super.onHide()
        context.sendBroadcast(android.content.Intent(WakeWordService.ACTION_RESUME))
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onDestroy() {
        super.onDestroy()
        context.sendBroadcast(android.content.Intent(WakeWordService.ACTION_RESUME))
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStoreOwner.viewModelStore.clear()
    }
}
