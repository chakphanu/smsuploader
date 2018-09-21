package th.`in`.jane.smsreader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import com.github.kittinunf.fuel.android.extension.responseJson
import com.github.kittinunf.fuel.httpPost
import com.github.kittinunf.result.Result
import com.google.gson.Gson
import io.paperdb.Paper
import org.json.JSONObject
import java.net.MalformedURLException
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


class SmsBroadcastReceiver: BroadcastReceiver(){
    var TAG = "SMS Uploader: "

    val lock = ReentrantLock()






    override fun onReceive(context: Context, intent: Intent) {
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(context)
        if(!sharedPref.getBoolean("hasConfig", false))
        {
            Toast.makeText(context, "SMS Reader not complete setup", Toast.LENGTH_LONG).show()
            return
        }

        var smsBody: String = ""
        val json = JSONObject()
        var smsReceive = SmsReceive()

        val smsMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

        for (smsMessage in smsMessages) {
            smsBody += smsMessage.getMessageBody()
        }

        smsReceive.type = "smsReceive"
        smsReceive.body = smsBody
        smsReceive.originatingAddress = smsMessages[0].getOriginatingAddress()
        smsReceive.timestampMillis = smsMessages[0].getTimestampMillis()

        json.put("type" , "smsReceive")
        json.put("body", smsBody)
        json.put("originatingAddress", smsMessages[0].getOriginatingAddress())
        json.put("timestampMillis", smsMessages[0].getTimestampMillis())


        Paper.init(context)








        try
        {
            uploadSMS(sharedPref,json,context)
            Toast.makeText(context, "SMS Reader: %s".format(smsBody), Toast.LENGTH_LONG).show()

        }
        catch (e:Exception) {


        }





    }


    fun uploadSMS(sharedPref: SharedPreferences, json: JSONObject,context: Context){
        val postUrl = sharedPref.getString("endpointSmss","")
        val accessToken = sharedPref.getString("accessToken","")

        var stat: Stat

        //val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager



        try{
            postUrl.httpPost()
                    .header(mapOf("Content-Type" to "application/json", "Authorization" to "Bearer ${accessToken}"))
                    .body(json.toString())
                    .responseJson { request, response, result ->
                        when (result) {
                            is Result.Failure -> {
                                lock.withLock {
                                    stat = Paper.book().read("stat", Stat(0,0))
                                    stat.incrFail()
                                    Paper.book().write("stat", stat)
                                }



                                Paper.book().write("smsfail_" + UUID.randomUUID().toString(),
                                        Gson().fromJson( json.toString(), SmsReceive::class.java))

                                Log.d(TAG, "E0 " + result.getException().toString())
                                //Toast.makeText(context, "E0 A SMS Reader(upload failed): %s".format(result.getException().toString()), Toast.LENGTH_LONG).show()
                            }
                            is Result.Success -> {
                                lock.withLock {
                                    stat = Paper.book().read("stat", Stat(0,0))
                                    stat.incrSuccess()
                                    Paper.book().write("stat", stat)
                                }

                                Log.d(TAG, "E0 " + result.get().content)
                                //Toast.makeText(context, "A SMS Reader(upload success): %s".format(result.get().content), Toast.LENGTH_LONG).show()
                            }
                        }
                    }

        }catch(e: MalformedURLException){
            sharedPref.edit().let {
                it.putBoolean("hasConfig", false)
                it.apply()
            }
            Log.d(TAG,"E1 MalformedURLException to $postUrl")
            Log.d(TAG,e.toString())
        }catch(e: Exception){
            Log.d(TAG,"E1 Exception to $postUrl")
            Log.d(TAG,e.toString())
        }

    }
}