package org.openhotspot.tablet.qr

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** ML Kit (bundled model) reading QR codes from CameraX frames. */
private class MlKitQrAnalyzer(private val gate: QrGate) : ImageAnalysis.Analyzer {
    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val media = imageProxy.image
        if (media == null) { imageProxy.close(); return }
        val image = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it.rawValue }?.let(gate::offer) }
            .addOnCompleteListener { imageProxy.close() } // the next frame is delivered only after this
    }

    fun close() = scanner.close()
}

/**
 * Live camera preview that feeds [gate]. The caller must hold the CAMERA permission. Camera and
 * analyzer are released when the composable leaves the screen.
 */
@Composable
fun CameraQrScanner(gate: QrGate, modifier: Modifier = Modifier.fillMaxSize(), onCameraError: (String) -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember(gate) { MlKitQrAnalyzer(gate) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycleOwner, gate) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().also { it.setAnalyzer(executor, analyzer) }
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, backOrAny(p), preview, analysis)
            } catch (e: Exception) {
                onCameraError("Could not open the camera (${e.javaClass.simpleName})")
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
            analyzer.close()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

private fun backOrAny(p: ProcessCameraProvider): CameraSelector =
    if (p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA

/** True if this device has any camera at all (some tablets have none). */
fun hasAnyCamera(context: Context) = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
