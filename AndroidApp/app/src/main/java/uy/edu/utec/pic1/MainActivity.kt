package uy.edu.utec.pic1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import uy.edu.utec.pic1.ui.AppRoot
import uy.edu.utec.pic1.ui.theme.UtecorroeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as App
        setContent { UtecorroeTheme { AppRoot(app) } }
    }
}
