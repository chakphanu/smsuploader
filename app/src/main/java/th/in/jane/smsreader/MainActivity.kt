package th.`in`.jane.smsreader

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.BatteryManager
import android.os.Bundle
import android.preference.PreferenceManager
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.view.Menu
import android.view.MenuItem
import android.util.Log
import com.github.kittinunf.fuel.android.extension.responseJson
import com.github.kittinunf.fuel.httpPost
import com.github.kittinunf.result.Result
import com.google.gson.Gson
import io.paperdb.Paper
import kotlinx.android.synthetic.main.activity_main.*


import org.json.JSONObject
import java.net.ConnectException
import java.net.MalformedURLException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.fixedRateTimer
import kotlin.concurrent.withLock

@SuppressLint("ByteOrderMark")
class MainActivity : AppCompatActivity() {

    var endpointPing: String = ""
    var accessToken: String = ""



    var batteryPct: Float? = 1f
    var isCharging: Boolean = true
    var bstatus: Int = -1

    var hasConfig: Boolean = false

    val lock = ReentrantLock()

    val bcastBatt = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {

            val batteryPct: Float? = intent.let { battIntent ->
                val level: Int = battIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale: Int = battIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                level / scale.toFloat()
            }

            try{
                this@MainActivity.bstatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                this@MainActivity.isCharging = bstatus == BatteryManager.BATTERY_STATUS_CHARGING || bstatus == BatteryManager.BATTERY_STATUS_FULL
                this@MainActivity.batteryPct = batteryPct
            }catch (e: Exception){
                Log.d(TAG,e.toString())
            }

        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
        Paper.init(this)



        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)


        val bundle = getIntent().getExtras()
        if (bundle != null) {

        }


        if(checkPermissions()){
            if(sharedPref.getBoolean("hasConfig", false))
            {
                hasConfig = true
                endpointPing = sharedPref.getString("endpointPing","")
                accessToken = sharedPref.getString("accessToken","")
            }else{
                quickSetup()
            }

        }else{
            setupPermissions()
            finish()
        }





        startInterval()


        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            this.registerReceiver(bcastBatt, ifilter)
        }
        bstatus = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        isCharging = bstatus == BatteryManager.BATTERY_STATUS_CHARGING || bstatus == BatteryManager.BATTERY_STATUS_FULL




    }

    fun quickSetup(){

        val intentSetup = Intent(this, SetupActivity::class.java)
        startActivityForResult(intentSetup, 6969)
    }

    fun ping(){
        if(!hasConfig){
            return
        }
        if ( !checkPermissions() ) {
            return
        }

        val postUrl = endpointPing


        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager


        val json = JSONObject()
        json.put("type" , "ping")


        try{
            json.put("batteryPct", batteryPct)
            json.put("isCharging", isCharging)
            json.put("imei", tm.imei)
            json.put("imsi", tm.subscriberId)
            json.put("networkOperatorName", tm.networkOperatorName)
            json.put("networkOperatorId",tm.networkOperator)
            json.put("line1Number", tm.line1Number)
            json.put("simSerialNumber", tm.simSerialNumber)
            json.put("dataState",tm.dataState)
            json.put("deviceId",tm.deviceId)
            json.put("deviceSoftwareVersion",android.os.Build.VERSION.RELEASE)
            json.put("phoneModel",android.os.Build.MODEL)

            var cellInfo = tm.allCellInfo.get(0)

            when (cellInfo) {
                is CellInfoGsm -> {
                    json.put("cellType","gsm")
                    json.put("cellSignalStrengthLevel", cellInfo.cellSignalStrength.level)
                    json.put("cellSignalStrengthDbm", cellInfo.cellSignalStrength.dbm)
                    json.put("cellSignalStrengthAsuLevel", cellInfo.cellSignalStrength.asuLevel)
                    json.put("cellIdentityCid",cellInfo.cellIdentity.cid)
                }
                is CellInfoWcdma -> {
                    json.put("cellType","wcdma")
                    json.put("cellSignalStrengthLevel", cellInfo.cellSignalStrength.level)
                    json.put("cellSignalStrengthDbm", cellInfo.cellSignalStrength.dbm)
                    json.put("cellSignalStrengthAsuLevel", cellInfo.cellSignalStrength.asuLevel)
                    json.put("cellIdentityCid",cellInfo.cellIdentity.cid)
                }
                is CellInfoLte -> {
                    json.put("cellType","lte")
                    json.put("cellSignalStrengthLevel", cellInfo.cellSignalStrength.level)
                    json.put("cellSignalStrengthDbm", cellInfo.cellSignalStrength.dbm)
                    json.put("cellSignalStrengthAsuLevel", cellInfo.cellSignalStrength.asuLevel)
                    json.put("cellIdentityCid",cellInfo.cellIdentity.ci)

                }
                else -> {
                    json.put("cellType","unknow")
                }
            }

        }catch (e: Exception){
            Log.d(TAG,e.toString())
        }





        try {

            postUrl.httpPost()
                    .header(mapOf("Content-Type" to "application/json"))
                    .header(mapOf("Authorization" to "Bearer ${accessToken}"))
                    .body(json.toString())
                    .responseJson { request, response, result ->
                        when (result) {
                            is Result.Failure -> {
                                Log.d(TAG, result.getException().toString())
                            }
                            is Result.Success -> {
                                Log.d(TAG, result.get().content)
                            }
                        }
                    }
        }catch(e: MalformedURLException){
            hasConfig = false
            val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
            sharedPref.edit().let {
                it.putBoolean("hasConfig", false)
                it.apply()
            }
            Log.d(TAG,"MalformedURLException to $postUrl")
            Log.d(TAG,e.toString())
        }catch(e: ConnectException){
            Log.d(TAG,"ConnectException to $postUrl")
            Log.d(TAG,e.toString())

        }catch(e: Exception){
            Log.d(TAG,"Exception to $postUrl")
            Log.d(TAG,e.toString())
        }
    }

    fun startInterval(){
        val fixedRateTimer = fixedRateTimer(name = "ping-timer",
                initialDelay = 1000, period = 60000) {
            ping()

            this@MainActivity.runOnUiThread(Runnable {
                var stat: Stat = Paper.book().read("stat", Stat(0,0))
                tvSuccess.text = stat.Success.toString()
                tvFail.text = stat.Fail.toString()
            })
        }

        val fixedRateTimerRetry = fixedRateTimer(name = "retry-timer",
                initialDelay = 1000, period = 5000) {
            retryUpload()
        }


    }

    fun retryUpload(){
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
        val postUrl = sharedPref.getString("endpointSmss","")
        val accessToken = sharedPref.getString("accessToken","")
        var stat: Stat = Paper.book().read("stat", Stat(0,0))


        var allKeys:List<String> = Paper.book().getAllKeys().filter{ it.contains("smsfail_")}
        var smsReceive: SmsReceive

        allKeys.forEach{
            Log.d(TAG + "smsfail: ", it)
            try{
                smsReceive = Paper.book().read(it)
                Log.d(TAG, smsReceive.toString())
                postUrl.httpPost()
                        .header(mapOf("Content-Type" to "application/json", "Authorization" to "Bearer ${accessToken}"))
                        .body(Gson().toJson(smsReceive).toString())
                        .responseJson { request, response, result ->
                            when (result) {
                                is Result.Failure -> {


                                    Log.d(TAG, "E0 " + result.getException().toString())
                                }
                                is Result.Success -> {
                                    lock.withLock {
                                        stat = Paper.book().read("stat", Stat(0,0))
                                        stat.incrSuccess()
                                        Paper.book().write("stat", stat)
                                    }
                                    Paper.book().delete(it)
                                    Log.d(TAG, "E0 " + result.get().content)
                                }
                            }
                        }

            }catch(e: MalformedURLException){
                sharedPref.edit().let {
                    it.putBoolean("hasConfig", false)
                    it.apply()
                }
                Log.d(TAG,"E2 MalformedURLException to $postUrl")
                Log.d(TAG,e.toString())
            }catch(e: Exception){
                Log.d(TAG,"E2 Exception to $postUrl")
                Log.d(TAG,e.toString())
            }


            this@MainActivity.runOnUiThread(Runnable {
                tvSuccess.text = stat.Success.toString()
                tvFail.text = stat.Fail.toString()
                tvQueue.text = allKeys.size.toString()
                Log.d(TAG, allKeys.size.toString())
            })
        }





    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 6969) {
            if (resultCode == RESULT_OK) {
                val returnString = data?.getStringExtra(Intent.EXTRA_TEXT);
                Log.d(TAG,returnString)

                val config = Gson().fromJson(returnString, Config::class.java)


                val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)

                sharedPref.edit().let {
                    it.putBoolean("hasConfig", true)
                    it.putString("accessToken", config.accessToken)
                    it.putString("endpointSmss",config.endpointSmss)
                    it.putString("endpointPing",config.endpointPing)
                    it.apply()
                }

                endpointPing = config.endpointPing


                hasConfig = true


                finish()
                startActivity(getIntent())






            }
        }


    }



    val TAG = "SMS Reader App"

    val permissionRequestCode = 101
    val perms = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_COARSE_LOCATION
            //Manifest.permission.WRITE_EXTERNAL_STORAGE
    )


    fun checkPermissions(): Boolean {

        for(perm: String in perms){
            if(ContextCompat.checkSelfPermission(this, perm) != PERMISSION_GRANTED)
            {
                return false
            }
        }

        return true


    }

    fun setupPermissions() {

        if ( !checkPermissions() ) {
            Log.i(TAG, "Need Permission to RECEIVE_SMS")
            ActivityCompat.requestPermissions(this, perms, permissionRequestCode)

        }


    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == permissionRequestCode) {
            if (grantResults.contains(PERMISSION_DENIED)) {
                ActivityCompat.requestPermissions(this, perms, permissionRequestCode)
            }
        }
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> {
                quickSetup()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bcastBatt)

    }



}
