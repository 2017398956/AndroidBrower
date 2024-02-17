package personal.nfl.browser

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import personal.nfl.browser.ui.theme.AndroidBrowerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (personal.nfl.browser.ui.BuildConfig.APPLICATION_ID != "personal.nfl.browser") {
            WebViewBrowserActivity.url = personal.nfl.browser.ui.BuildConfig.baseUrl
            val intent = Intent(this, WebViewBrowserActivity::class.java)
            if (!personal.nfl.browser.ui.BuildConfig.APPLICATION_ID?.contains("browser")) {
                intent.putExtra(
                    WebViewBrowserActivity.INIT_SCREEN_ORIENTATION,
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                )
                Log.d("NFL", "设置 webview 的方向为横屏")
            }
            startActivity(intent)
            finish()
        }

        setContent {
            AndroidBrowerTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    openUrl()
                    // Greeting("Android")
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String) {
    Text(text = "Hello $name!")
}

@Composable
fun openUrl() {
    val context = LocalContext.current
    Column() {
        var url by remember { mutableStateOf("https://192.168.1.110:8443/#browse") }
        OutlinedTextField(value = url, onValueChange = {
            url = it
        },
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp, 10.dp, 10.dp, 10.dp), label = {
                Text(text = "请输入要访问的地址")
            })
        Button(onClick = {
            WebViewBrowserActivity.url = url
            context.startActivity(Intent(context , WebViewBrowserActivity::class.java))
        }, modifier = Modifier
            .padding(10.dp, 10.dp, 10.dp, 10.dp)
            .border(1.dp, Color.Black)) {
            Text(text = "打开")
        }
    }

}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    AndroidBrowerTheme {
        Greeting("Android")
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview2() {
    AndroidBrowerTheme {
        openUrl()
    }
}