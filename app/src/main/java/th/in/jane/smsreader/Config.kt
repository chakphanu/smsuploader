package th.`in`.jane.smsreader

data class Config(
        val uid: String,
        val deviceName: String,
        val deviceId: Int,
        val endpointSms: String,
        val endpointPing: String,
        val endpointJwt: String,
        val accessToken: String
)