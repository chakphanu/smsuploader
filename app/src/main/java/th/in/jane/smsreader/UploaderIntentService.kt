package th.`in`.jane.smsreader

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.preference.PreferenceManager
import android.support.annotation.RequiresApi
import android.util.Log
import com.github.kittinunf.fuel.android.extension.responseJson
import com.github.kittinunf.fuel.httpPost
import org.json.JSONObject
import java.net.ConnectException
import java.net.MalformedURLException
import android.support.v4.app.NotificationCompat
import android.support.v4.app.NotificationCompat.PRIORITY_MAX
import android.support.v4.content.ContextCompat
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import com.auth0.android.jwt.JWT
import com.github.kittinunf.result.Result
import com.google.gson.Gson
import io.paperdb.Paper
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.fixedRateTimer


private const val ACTION_SMS = "th.`in`.jane.smsreader.action.SMS"
private const val ACTION_PING = "th.`in`.jane.smsreader.action.PING"

private const val EXTRA_PARAM_SMS =  "th.`in`.jane.smsreader.param.SMS"

class UploaderIntentService : IntentService("UploaderIntentService") {


    override fun onCreate() {
        super.onCreate()
        Log.d(TAG,"onCreate")

        mainUIIntent = Intent("th.`in`.jane.sms.info")

        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            this.registerReceiver(bcastBatt, ifilter)
        }

        bstatus = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        isCharging = bstatus == BatteryManager.BATTERY_STATUS_CHARGING || bstatus == BatteryManager.BATTERY_STATUS_FULL



        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }

        startNotification("Starting....")

        loadConfig()


        Paper.init(this)
        stat = Paper.book().read("stat", Stat())



        fixedRateTimer(name = "ping-timer",
                initialDelay = 30000, period = 60000) {
            Log.d(TAG,Date().toString() + " ping-timer")
            ping()
        }

        startRetryTimer()

    }

    private fun loadConfig(){
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
        if(sharedPref.getBoolean("hasConfig", false)) {
            hasConfig = true
            endpointSmss = sharedPref.getString("endpointSmss","")
            endpointPing = sharedPref.getString("endpointPing","")
            endpointJwt = sharedPref.getString("endpointJwt","")
            accessToken = sharedPref.getString("accessToken","")
            uid = sharedPref.getString("uid","")
            Log.d(TAG, "onCreate: jwtToken: $jwtToken.")

        }else{
            Log.d(TAG, "onCreate: hasConfig false.")
            sendLogToMain("please setup.")
        }
    }

    private fun startRetryTimer(){
        if(retryTimer != null) return
        Log.d(TAG, "TR00: startRetryTimer")
        retryTimer = fixedRateTimer(name = "retry-timer",
                initialDelay = 1000, period = 5000) {
            Log.d(TAG,Date().toString() + " retry-timer")
            retryUpload()
        }
    }

    private fun sendToMain(text: String){
        mainUIIntent!!.putExtra("info", text)
        this.sendBroadcast(mainUIIntent)
    }

    private fun sendLogToMain(text: String){
        val jsonMessage = JSONObject()
        jsonMessage.put("type","log")
        jsonMessage.put("message", text)
        sendToMain(jsonMessage.toString())
    }


    private fun stopRetryTimer(){
        if(retryTimer == null) return
        Log.d(TAG, "TR01: stopRetryTimer")
        retryTimer!!.cancel()
        retryTimer!!.purge()
        retryTimer = null
    }

    private fun startNotification(info: String) {
        val intent = Intent(applicationContext,MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        var pendingIntent = PendingIntent.getActivity(applicationContext,0, intent,PendingIntent.FLAG_UPDATE_CURRENT)


        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_INFO )
        val notification = notificationBuilder.setOngoing(true)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(PRIORITY_MAX)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentTitle("Success/Fail/Retry")
                .setContentText(info)
                .setContentIntent(pendingIntent)
                .build()
        startForeground(NOTIFICATION_ID_INFO, notification)

    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_INFO, NOTIFICATION_NAME_INFO, NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            enableLights(true)
        }
        getNotificationManager().createNotificationChannel(channel)
    }

    private fun getNotificationManager(): NotificationManager {
        return getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG,"onStartCommand intent: %s".format(intent.toString()) )
        onHandleIntent(intent)
        return START_STICKY // keep foreground
    }


    override fun onHandleIntent(intent: Intent?) {
        Log.d(TAG,"onHandleIntent intent: %s".format(intent.toString()) )
        Log.d(TAG, "onHandleIntent: jwtToken: $jwtToken.")

        ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())

        if(!hasConfig){
            loadConfig()
        }

        when (intent?.action) {
            ACTION_SMS -> {
                val smsJsonString = intent.getStringExtra(EXTRA_PARAM_SMS)
                handleActionSms(smsJsonString)
            }
            ACTION_PING -> {
                handleActionPing()
            }
        }
    }


    fun checkJwt(): Boolean{
        var newToken = false
        if(jwtToken.isEmpty()){
            Log.d(TAG,"CJ00: Token empty, get new")
            jwtToken = getJWT()
            Log.d(TAG,"CJ00: new token: $jwtToken")
            newToken = true
        }
        if(jwtToken.isEmpty()){
            Log.d(TAG,"CJ00: Cannot get new token, return false")
            return false
        }
        try {
            val current = Date()
            var jwt: JWT = JWT(jwtToken)
            val timeDIff = jwt.expiresAt!!.time - current.time
            // check expire near 15 minutes (millisecond)
            if(timeDIff < 900000){
                Log.d(TAG, "CJ01: request new JWT.")
                jwtToken = getJWT()
                JWT(jwtToken) // recheck
                newToken = true
            }
            if(newToken)
            {
                val jsonMessage = JSONObject()
                jsonMessage.put("type","jwt")
                jsonMessage.put("jwt", jwtToken)
                sendToMain(jsonMessage.toString())
                newToken = false
            }
            return true

        }catch (e: Exception) {
            Log.d(TAG, "CJE0 Exception: " + e.toString())
            return false
        }
    }


    fun getJWT():String {
        Log.d(TAG + " getJWT():>","getJWT")
        try {
            val json = JSONObject()
            json.put("accessToken",accessToken)
            // blocking mode
            val (request, _, result) = endpointJwt.httpPost().timeout(timeout).timeoutRead(timeoutRead)
                    .header(mapOf("Content-Type" to "application/json"))
                    .body(json.toString())
                    .responseJson() // result is Result<String, FuelError>
            result.fold(success = { json ->
                Log.d(TAG + " getJWT():>","getJWT success: " + json.obj().getString("jwtToken"))
                return json.obj().getString("jwtToken")
            }, failure = { error ->
                Log.e(TAG + " getJWT():>", "getJWT failure: " + error.toString())
                //Log.d(TAG + " getJWT():>", "getJWT request: " + request.toString())
                sendLogToMain(error.toString())
            })
        }catch(e: MalformedURLException){
            hasConfig = false
            val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
            sharedPref.edit().let {
                it.putBoolean("hasConfig", false)
                it.apply()
            }
            Log.d(TAG + " getJWT():>","getJWT MalformedURLException to $endpointJwt")
            Log.d(TAG + " getJWT():>",e.toString())
        }catch(e: ConnectException){
            Log.d(TAG + " getJWT():>","getJWT ConnectException to $endpointJwt")
            Log.d(TAG + " getJWT():>",e.toString())
        }catch(e: Exception){
            Log.d(TAG + " getJWT():>","getJWT Exception to $endpointJwt")
            Log.d(TAG + " getJWT():>",e.toString())
        }
        return ""
    }


    fun uploadSMS(smsJsonString: String){
        if(!hasConfig){
            sendLogToMain("please setup.")

            return
        }
        val json = JSONObject(smsJsonString)
        Log.d(TAG + " uploadSMS():>", "uploadSMS")
        Log.d(TAG + " uploadSMS():>", json.toString())

        checkJwt()
        if (jwtToken.isEmpty()){
            Log.d(TAG + " uploadSMS():>","uploadSMS jwtToken.isEmpty(), return")
            stat.incrFail()
            Paper.book().write("smsfail_" + UUID.randomUUID().toString(),
                    Gson().fromJson( json.toString(), SmsReceive::class.java))
            startNotification("%d/%d/%d".format(stat.Success, stat.Fail, stat.Retry))
            startRetryTimer()
            return
        }

        try{
            // blocking mode
            val (request, _, result) = endpointSmss.httpPost().timeout(timeout).timeoutRead(timeoutRead)
                    .header(mapOf(
                            "Content-Type" to "application/json",
                            "Authorization" to "Bearer ${jwtToken}"
                    ))
                    .body(json.toString())
                    .responseJson()

            when (result) {
                    is Result.Failure -> {
                        stat.incrFail()
                        Paper.book().write("smsfail_" + UUID.randomUUID().toString(),
                                Gson().fromJson( json.toString(), SmsReceive::class.java))
                        Log.d(TAG + " uploadSMS():>", "BE0 jwtToken: $jwtToken")
                        Log.d(TAG + " uploadSMS():>", "BE0 postUrl: $endpointSmss")
                        Log.d(TAG + " uploadSMS():>", "BE0 exception: " + result.getException().toString())
                        Log.d(TAG + " uploadSMS():>", "BE0 request: " + request.toString())
                        startRetryTimer()
                    }
                    is Result.Success -> {
                        stat.incrSuccess()
                        Log.d(TAG + " uploadSMS():>", "BI0 " + result.get().content)
                    }
            }
        }catch(e: MalformedURLException){
            Log.d(TAG + " uploadSMS():>","BE1 MalformedURLException to $endpointSmss")
            Log.d(TAG + " uploadSMS():>",e.toString())
        }catch(e: Exception){
            Log.d(TAG + " uploadSMS():>","BE2 Exception to $endpointSmss")
            Log.d(TAG + " uploadSMS():>",e.toString())
        }

        Paper.book().write("stat", stat)
        startNotification("%d/%d/%d".format(stat.Success,stat.Fail, stat.Retry))

        sendLogToMain(stat.toString())

    }

    fun retryUpload(){
        Log.d(TAG + " retryUpload():>", "retryUpload")
        if(!hasConfig){
            sendLogToMain("please setup.")
            return
        }

        checkJwt()
        if (jwtToken.isEmpty()){
            Log.d(TAG + " retryUpload():>","retryUpload jwtToken.isEmpty(), return")
            return
        }

        var allKeys:List<String> = Paper.book().getAllKeys().filter{ it.contains("smsfail_")}
        var smsReceive: SmsReceive

        if(allKeys.isEmpty()){
            stat.Retry = 0
            Paper.book().write("stat", stat)
            startNotification("%d/%d/%d".format(stat.Success, stat.Fail, stat.Retry))
            sendLogToMain(stat.toString())
            stopRetryTimer()
            return
        }

        stat.Retry = allKeys.size


        allKeys.forEach{
            try{
                smsReceive = Paper.book().read(it)
                Log.d(TAG + " retryUpload():>", "retryUpload allKeys.forEach: " + smsReceive.toString())
                val (request, _, result) = endpointSmss.httpPost().timeout(timeout).timeoutRead(timeoutRead)
                        .header(mapOf(
                                "Content-Type" to "application/json",
                                "Authorization" to "Bearer ${jwtToken}"
                        ))
                        .body(Gson().toJson(smsReceive).toString())
                        .responseJson()
                when (result) {
                    is Result.Failure -> {
                        Log.d(TAG + " retryUpload():>", "retryUpload R01 jwtToken: ${jwtToken}")
                        Log.d(TAG + " retryUpload():>", "retryUpload postUrl: ${endpointSmss}")
                        Log.d(TAG + " retryUpload():>", "retryUpload R01 exception: " + result.getException().toString())
                        Log.d(TAG + " retryUpload():>", "retryUpload R01 request: " + request.toString())
                    }
                    is Result.Success -> {
                        stat.incrSuccess()
                        stat.decrRetry()
                        Paper.book().delete(it)
                        Log.d(TAG + " retryUpload():>", "retryUpload E0 " + result.get().content)
                    }
                }
            }catch(e: MalformedURLException){
                Log.d(TAG + " retryUpload():>","retryUpload E2 MalformedURLException to $endpointSmss")
                Log.d(TAG + " retryUpload():>",e.toString())
            }catch(e: Exception){
                Log.d(TAG + " retryUpload():>","retryUpload E2 Exception to $endpointSmss")
                Log.d(TAG + " retryUpload():>",e.toString())
            }
        }

        Paper.book().write("stat", stat)
        startNotification("%d/%d/%d".format(stat.Success, stat.Fail, stat.Retry))
        sendLogToMain(stat.toString())
    }


    fun ping(){
        if(!hasConfig){
            sendLogToMain("please setup.")
            return
        }

        checkJwt()

        if (jwtToken.isEmpty()){
            Log.d(TAG + " ping():>","ping jwtToken.isEmpty(), return")
            return
        }

        if ( !checkPermissions() ) {
            return
        }

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
            Log.d(TAG + " ping():>",e.toString())
        }

        try {
            val (request, _, result) = endpointPing.httpPost().timeout(timeout).timeoutRead(timeoutRead)
                    .header(mapOf(
                            "Content-Type" to "application/json",
                            "Authorization" to "Bearer ${jwtToken}"
                    ))
                    .body(json.toString())
                    .responseJson()
            when (result) {
                is Result.Failure -> {
                    Log.d(TAG + " ping():>", "P02 jwtToken: ${jwtToken}")
                    Log.d(TAG + " ping():>", "P02 endpointPing: ${endpointPing}")
                    Log.d(TAG + " ping():>", "P02: " + result.getException().toString())
                    Log.d(TAG + " ping():>", "P02 request: " + request.toString())
                    val jsonMessage = JSONObject()
                    jsonMessage.put("type","ping")
                    jsonMessage.put("status",false)
                    jsonMessage.put("message", "$ts Down: " + result.getException().toString())
                    sendToMain(jsonMessage.toString())
                }
                is Result.Success -> {
                    Log.d(TAG + " ping() request: >",request.toString())
                    Log.d(TAG + " ping():>", "P01: " + result.get().content)
                    val jsonMessage = JSONObject()
                    jsonMessage.put("type","ping")
                    jsonMessage.put("status",true)
                    jsonMessage.put("message", "$ts UP")
                    sendToMain(jsonMessage.toString())
                }
            }
        }catch(e: MalformedURLException){
            hasConfig = false
            Log.d(TAG + " ping():>","P04 MalformedURLException to $endpointPing")
            Log.d(TAG + " ping():>","P04: " + e.toString())
            val jsonMessage = JSONObject()
            jsonMessage.put("type","ping")
            jsonMessage.put("status",false)
            jsonMessage.put("message", "$ts Down: " + e.toString())
            sendToMain(jsonMessage.toString())
        }catch(e: ConnectException){
            Log.d(TAG + " ping():>","P05 ConnectException to $endpointPing")
            Log.d(TAG + " ping():>","P05: " + e.toString())
            val jsonMessage = JSONObject()
            jsonMessage.put("type","ping")
            jsonMessage.put("status",false)
            jsonMessage.put("message", "$ts Down: " + e.toString())
            sendToMain(jsonMessage.toString())

        }catch(e: Exception){
            Log.d(TAG + " ping():>","P06 Exception to $endpointPing")
            Log.d(TAG + " ping():>","P06: " + e.toString())
            val jsonMessage = JSONObject()
            jsonMessage.put("type","ping")
            jsonMessage.put("status",false)
            jsonMessage.put("message", "$ts Down: " + e.toString())
            sendToMain(jsonMessage.toString())
        }
    }


    val perms = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_COARSE_LOCATION
    )
    fun checkPermissions(): Boolean {
        for(perm: String in perms){
            if(ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED)
            {
                return false
            }
        }
        return true
    }

    /**
     * Handle action Foo in the provided background thread with the provided
     * parameters.
     */
    private fun handleActionSms(sms: String) {
        Log.d(TAG, "handleActionSms")
        uploadSMS(sms)
    }

    /**
     * Handle action Ping in the provided background thread with the provided
     * parameters.
     */
    private fun handleActionPing() {
        Log.d(TAG, "handleActionPing")
        ping()
    }


    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        unregisterReceiver(bcastBatt)
    }





    companion object {

        private const val NOTIFICATION_ID_INFO = 88
        private const val NOTIFICATION_CHANNEL_INFO = "info"
        private const val NOTIFICATION_NAME_INFO = "Info"

        val timeout = 3000 // 3000 milliseconds = 3 seconds.
        val timeoutRead = 10000 // 10000 milliseconds = 10 seconds.

        var stat = Stat()

        var retryTimer: Timer? = null

        var endpointSmss: String = ""
        var endpointPing: String = ""
        var endpointJwt: String = ""
        var accessToken: String = ""
        var jwtToken: String = ""
        var uid: String = ""

        var hasConfig: Boolean = false

        val TAG: String = "UpSrv"

        var mainUIIntent: Intent?  =  null

        var batteryPct: Float? = 1f
        var isCharging: Boolean = true
        var bstatus: Int = -1

        var ts = ""

        val bcastBatt = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try{
                    batteryPct = intent.let { battIntent ->
                        val level: Int = battIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale: Int = battIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                        level / scale.toFloat()
                    }
                    bstatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    isCharging = bstatus == BatteryManager.BATTERY_STATUS_CHARGING || bstatus == BatteryManager.BATTERY_STATUS_FULL
                }catch (e: Exception){
                    Log.d(TAG,e.toString())
                }

            }
        }



        /**
         * Starts this service to perform action Sms with the given parameters. If
         * the service is already performing a task this action will be queued.
         *
         * @see IntentService
         */
        @JvmStatic
        fun startActionSms(context: Context, param1: String) {
            val intent = Intent(context, UploaderIntentService::class.java).apply {
                action = ACTION_SMS
                putExtra(EXTRA_PARAM_SMS, param1)
            }
            context.startService(intent)
        }

        /**
         * Starts this service to perform action Ping. If
         * the service is already performing a task this action will be queued.
         *
         * @see IntentService
         */
        @JvmStatic
        fun startActionPing(context: Context) {
            val intent = Intent(context, UploaderIntentService::class.java).apply {
                action = ACTION_PING
            }
            context.startService(intent)
        }
    }
}
