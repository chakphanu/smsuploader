package th.`in`.jane.smsreader

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.preference.PreferenceManager
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import com.google.android.gms.vision.CameraSource
import com.google.android.gms.vision.Detector
import com.google.android.gms.vision.barcode.Barcode
import com.google.android.gms.vision.barcode.BarcodeDetector

class SetupActivity : AppCompatActivity() {

    val TAG = "SMS Reader Quick Setup"


    private lateinit var detector: BarcodeDetector
    private lateinit var cameraSource: CameraSource

    private lateinit var svBarcode: SurfaceView

    private var quickString: String = ""


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_setup)

        detector = BarcodeDetector.Builder(this).setBarcodeFormats(Barcode.QR_CODE).build()
        detector.setProcessor(object : Detector.Processor<Barcode>{
            override fun release() {
            }

            override fun receiveDetections(detections: Detector.Detections<Barcode>?) {
                var barcodes = detections?.detectedItems
                if(barcodes!!.size() >0 && quickString.isEmpty()){
                    quickString = barcodes.valueAt(0).displayValue
                    Log.d(TAG,quickString)
                    val intentReturn = Intent()
                    intentReturn.putExtra(Intent.EXTRA_TEXT, quickString)
                    setResult(Activity.RESULT_OK, intentReturn)
                    finish()
                }
            }

        })


        cameraSource = CameraSource.Builder(this,detector)
                //.setRequestedPreviewSize(640,640)
                //.setRequestedFps(1f)
                .setAutoFocusEnabled(true)
                .build()


        svBarcode = findViewById(R.id.sv_barcode)

        var btCancle: Button = findViewById(R.id.btCancle)
        btCancle.setOnClickListener({view ->
            finish()
        })

        svBarcode.holder.addCallback(object : SurfaceHolder.Callback2{
            override fun surfaceRedrawNeeded(holder: SurfaceHolder?) {}
            override fun surfaceChanged(holder: SurfaceHolder?, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder?) {
                cameraSource.stop()

            }

            override fun surfaceCreated(holder: SurfaceHolder?) {
                cameraSource.start(holder)
            }

        })



    }

    override fun onDestroy() {
        val intentReturn = Intent()
        intentReturn.putExtra(Intent.EXTRA_TEXT, quickString)
        setResult(Activity.RESULT_OK, intentReturn)

        super.onDestroy()
        detector.release()
        cameraSource.stop()
        cameraSource.release()



    }
}