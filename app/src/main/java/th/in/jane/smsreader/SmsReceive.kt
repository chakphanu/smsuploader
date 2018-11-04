package th.`in`.jane.smsreader

data class SmsReceive(
        var type: String? = "",
        var message: String?="",
        var from: String?="",
        var timestamp: Long?=0,
        var device_id: String?="",
        var line_number: String?="",
        var operator_name: String?="",
        var imei: String?=""
)