package th.`in`.jane.smsreader

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Bundle
import android.os.StrictMode
import android.preference.PreferenceManager
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.text.method.ScrollingMovementMethod
import android.view.Menu
import android.view.MenuItem
import android.util.Log
import com.auth0.android.jwt.JWT
import com.google.gson.Gson
import kotlinx.android.synthetic.main.activity_main.*
import org.apache.commons.collections4.queue.CircularFifoQueue
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*


@SuppressLint("ByteOrderMark")
class MainActivity : AppCompatActivity() {

    var accessToken: String = ""
    var deviceId: Int = 0

    var hasConfig: Boolean = false

    var logBuffer = CircularFifoQueue<String>(100)




    val bcastInfo = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val info = intent.getStringExtra("info")
            try{
                val jsonMessage = JSONObject(info)
                when(jsonMessage.getString("type")){
                    "ping" -> {
                        this@MainActivity.runOnUiThread(Runnable {
                            tvKeepAlive.text = "Keep alive: " + jsonMessage.getString("message")
                            if(jsonMessage.getBoolean("status")){
                                toolbar.setBackgroundResource(R.color.green)
                            }else{
                                toolbar.setBackgroundResource(R.color.red)
                            }

                        })
                    }
                    "jwt" -> {
                        val jwtString = jsonMessage.getString("jwt")
                        try {
                            val jwt: JWT = JWT(jwtString)
                            this@MainActivity.runOnUiThread(Runnable {
                                tvDeviceName.text = "Device name: " + jwt.getClaim("device_name").asString()
                                tvForwardTo.text = "Forward to: " + jwt.getClaim("partner_name").asString()
                                tvForwardToken.text = "Forward token: " + jwt.getClaim("partner_token").asString()
                            })
                        }catch (e: Exception) {
                            Log.d(TAG, "CJE0 Exception: " + e.toString())
                        }
                    }
                    "log" -> {
                        tvAppend(jsonMessage.getString("message"))
                    }
                }
            }catch (e: java.lang.Exception){
                tvAppend(info)
            }


        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")

        if (android.os.Build.VERSION.SDK_INT > 9)
        {
            val  policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
            StrictMode.setThreadPolicy(policy)
        }

        val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)

        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)


        val bundle = getIntent().getExtras()
        if (bundle != null) {

        }

        if(checkPermissions()){
            if(sharedPref.getBoolean("hasConfig", false))
            {
                hasConfig = true
                accessToken = sharedPref.getString("accessToken","")
                deviceId = sharedPref.getInt("deviceId", 0)
                tvDeviceId.text = "DID: " + deviceId.toString()
                tvUid.text = "Username: " + sharedPref.getString("uid","")
            }else{
                quickSetup()
            }

        }else{
            setupPermissions()
            finish()
        }


        this.registerReceiver(bcastInfo, IntentFilter("th.`in`.jane.sms.info"))


        tvAppend("I01: SMS Uploader Started.")
        tvAppend("I02: UID: %s.".format(sharedPref.getString("uid","not found")))

        tvInfo.setMovementMethod(ScrollingMovementMethod())

        tvAppend("I03: create foreground intentservice")
        val uploaderIntentService = Intent(this, UploaderIntentService::class.java)
        startService(uploaderIntentService)

    }



    fun quickSetup(){

        val intentSetup = Intent(this, SetupActivity::class.java)
        startActivityForResult(intentSetup, 6969)
    }


    fun tvAppend(txt: String)
    {
        //val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd HH:mm:ss")).toString()
        val ts = SimpleDateFormat("dd HH:mm:ss").format(Date())
        logBuffer.add("$ts > $txt")
        this@MainActivity.runOnUiThread(Runnable {
            tvInfo.text = logBuffer.joinToString(separator = "\n")
        })
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        try{
            if (requestCode == 6969) {
                if (resultCode == RESULT_OK) {
                    val returnString = data?.getStringExtra(Intent.EXTRA_TEXT);
                    Log.d(TAG,returnString)

                    val config = Gson().fromJson(returnString, Config::class.java)


                    val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)

                    sharedPref.edit().let {
                        it.putBoolean("hasConfig", true)
                        it.putString("uid", config.uid)
                        it.putString("accessToken", config.accessToken)
                        it.putString("endpointSms", config.endpointSms)
                        it.putString("endpointPing", config.endpointPing)
                        it.putString("endpointJwt", config.endpointJwt)
                        it.putInt("deviceId", config.deviceId)
                        it.apply()
                    }


                    hasConfig = true

                    finish()
                    startActivity(getIntent())
                }
            }
        }catch (e: Exception) {
            this@MainActivity.runOnUiThread(Runnable {
                tvInfo.append("config failed: %s\n".format(e.toString()))
            })
        }




    }



    val TAG = "MainActivity"

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
        unregisterReceiver(bcastInfo)

    }



}
