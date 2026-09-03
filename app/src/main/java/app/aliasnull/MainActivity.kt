package app.aliasnull

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // The manifest launches this activity with Theme.AliasNull.Starting so the
        // Android 12+ system splash is themed correctly. Re-apply the normal
        // application theme before the window is created; otherwise the window that
        // appears after the splash would keep the launch theme, which reads as a
        // permanent splash/stuck screen.
        setTheme(R.style.Theme_AliasNull)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AliasNullApp()
        }
    }
}
