package th.`in`.jane.smsreader

data class SmsReceive(
        var type: String? = "",
        var message: String?="",
        var from: String?="",
        var timestamp: Long?=0
)