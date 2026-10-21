package com.smswebgateway.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.smswebgateway.android.net.PairingPayloadParser
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** CameraX + ZXing core 自行解码二维码（不依赖 Play Services / ML Kit）。解码内容必须是 {"server_url","pairing_token"} JSON。 */
@Composable
fun ScanScreen(modifier: Modifier, onResult: (String, String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it; denied = !it
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Box(modifier.fillMaxSize()) {
        if (granted) {
            CameraPreview(onResult)
            Text(
                "将摄像头对准网页上的绑定二维码", color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp),
            )
        } else {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (denied) "未授予相机权限，无法扫码。可以改用「手动输入」，或前往系统设置授予相机权限。" else "扫码需要相机权限",
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("授予相机权限") }
                Button(onClick = { openAppSettings(ctx) }) { Text("打开系统设置") }
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) { Text("返回") }
    }
}

@Composable
private fun CameraPreview(onResult: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val done = remember { AtomicBoolean(false) }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
        }
    }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            val view = PreviewView(c)
            val future = ProcessCameraProvider.getInstance(c)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { img ->
                    if (!done.get()) {
                        val text = decode(reader, img)
                        if (text != null) {
                            val payload = PairingPayloadParser.parse(text)
                            if (payload != null && done.compareAndSet(false, true)) {
                                view.post { onResult(payload.serverUrl, payload.pairingToken) }
                            }
                        }
                    }
                    img.close()
                }
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (_: Exception) {
                }
            }, ContextCompat.getMainExecutor(c))
            view
        },
    )
}

private fun decode(reader: MultiFormatReader, image: ImageProxy): String? {
    return try {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val data = ByteArray(buf.remaining()).also { buf.get(it) }
        val source = PlanarYUVLuminanceSource(data, rowStride, image.height, 0, 0, image.width, image.height, false)
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (e: Exception) {
        null
    } finally {
        reader.reset()
    }
}
